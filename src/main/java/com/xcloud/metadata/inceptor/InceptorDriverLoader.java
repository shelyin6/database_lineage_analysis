package com.xcloud.metadata.inceptor;

import com.xcloud.metadata.config.MetadataProperties;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads the Inceptor JDBC driver from an external directory in an <b>isolated class loader</b>.
 *
 * <p>Why isolation: several vendor SDK jars (for example {@code inceptor-sdk-4.7.0.jar}) bundle
 * their own SLF4J binding. When such a jar is placed on the application class path - the usual
 * {@code -Dloader.path=./lib} trick - SLF4J finds two bindings and the application fails to start
 * with "LoggerFactory is not a Logback LoggerContext but Logback is on the classpath". Here the
 * driver jar is read by a dedicated {@link URLClassLoader} that is parent-first for platform and
 * logging packages ({@code java.*}, {@code org.slf4j.*}, {@code ch.qos.logback.*},
 * {@code org.apache.logging.log4j.*}, {@code org.springframework.*}) and jar-first for everything
 * else. The driver keeps its own libraries, logging inside the driver goes to the application's
 * SLF4J + Logback, and the application never sees a second logging backend.
 *
 * <p>The driver directory is also where the connection comes from: {@link Driver#connect} is called
 * directly, so the driver never has to be registered with {@link DriverManager} on the application
 * class path.
 */
@Component
public class InceptorDriverLoader implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(InceptorDriverLoader.class);

    /**
     * Packages that decide which logging backend is active. They must be answered by the
     * application only - <b>never</b> loaded from the vendor jar, not even as a fallback when the
     * application does not have the class. This is what stops a fat SDK jar from bringing its own
     * SLF4J binding into the process.
     */
    private static final List<String> BLOCKED_FROM_JAR_PREFIXES = List.of(
            "org.slf4j.",
            "ch.qos.logback.",
            "org.apache.commons.logging.");

    /** Packages that prefer the application copy, but may fall back to the vendor jar. */
    private static final List<String> PARENT_FIRST_PREFIXES = List.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.apache.logging.log4j.",
            "org.springframework.");

    private final InceptorProperties properties;

    private volatile boolean resolved;
    private volatile Driver driver;
    private volatile String description = "未加载";
    private volatile IsolatedDriverClassLoader isolatedLoader;
    private volatile boolean driverDirectoryScanned;

    public InceptorDriverLoader(InceptorProperties properties) {
        this.properties = properties;
    }

    public boolean isAvailable() {
        return resolve() != null;
    }

    /** Human readable description, for example {@code org.apache.hive.jdbc.HiveDriver（外挂目录隔离加载）}. */
    public String description() {
        resolve();
        return description;
    }

    public String driverClassName() {
        Driver current = resolve();
        return current == null ? null : current.getClass().getName();
    }

    public String driverDirectory() {
        return driverDirectoryPath().toAbsolutePath().toString();
    }

    /** Class loader holding the external driver jars; exposed for diagnostics. */
    ClassLoader driverClassLoader() {
        return isolatedClassLoader();
    }

    /**
     * Opens a connection through the isolated driver.
     *
     * <p>Callers must only issue SELECT statements; the connection is marked read-only when the
     * driver supports it.
     */
    public Connection connect(String url, String user, String password, int loginTimeoutSeconds)
            throws SQLException {
        Driver current = resolve();
        if (current == null) {
            throw new SQLException("未找到 Inceptor JDBC 驱动（" + description + "），请把驱动 jar 放入目录："
                    + driverDirectory());
        }
        Properties connectionProperties = new Properties();
        connectionProperties.put("user", user == null ? "" : user);
        connectionProperties.put("password", password == null ? "" : password);
        int seconds = Math.max(1, loginTimeoutSeconds);
        connectionProperties.put("loginTimeout", String.valueOf(seconds));
        connectionProperties.put("connectTimeout", String.valueOf(seconds * 1000));

        Connection connection = current.connect(url, connectionProperties);
        if (connection == null) {
            throw new SQLException("JDBC 驱动 " + current.getClass().getName()
                    + " 不识别该连接串：" + sanitize(url));
        }
        try {
            connection.setReadOnly(true);
        } catch (SQLException exception) {
            LOG.debug("驱动不支持 setReadOnly，已忽略：{}", exception.getMessage());
        }
        return connection;
    }

    private Driver resolve() {
        if (resolved) {
            return driver;
        }
        synchronized (this) {
            if (!resolved) {
                driver = loadDriver();
                resolved = true;
            }
            return driver;
        }
    }

    private Driver loadDriver() {
        IsolatedDriverClassLoader loader = isolatedClassLoader();
        ClassLoader applicationLoader = getClass().getClassLoader();
        String className = properties.getDriverClassName();

        if (className != null && !className.isBlank()) {
            Driver fromIsolated = instantiate(className, loader);
            if (fromIsolated != null) {
                description = fromIsolated.getClass().getName() + "（外挂目录隔离加载）";
                return fromIsolated;
            }
            Driver fromApplication = instantiate(className, applicationLoader);
            if (fromApplication != null) {
                description = fromApplication.getClass().getName() + "（应用类路径）";
                return fromApplication;
            }
            // Do not fall back to "some other Driver on the class path": the status endpoint would
            // then happily report the bundled SQLite driver as an available Inceptor driver.
            description = "未加载（找不到配置的驱动类 " + className + "，请把驱动 jar 放入 "
                    + driverDirectoryPath().toAbsolutePath() + "）";
            LOG.warn("未找到数据库驱动类 {}（外挂目录 {}）；/api/catalog/status 会显示 driverAvailable=false",
                    className, driverDirectoryPath().toAbsolutePath());
            return null;
        }

        ServiceLoader<Driver> candidates = ServiceLoader.load(
                Driver.class, loader == null ? applicationLoader : loader);
        for (Driver candidate : candidates) {
            description = candidate.getClass().getName()
                    + (loader == null ? "（应用类路径）" : "（ServiceLoader）");
            return candidate;
        }

        try {
            Driver registered = DriverManager.getDriver(
                    properties.getUrl() == null ? "" : properties.getUrl());
            description = registered.getClass().getName() + "（DriverManager 已注册）";
            return registered;
        } catch (SQLException exception) {
            description = "未加载（请检查驱动目录 " + driverDirectoryPath().toAbsolutePath() + "）";
            LOG.warn("未加载 Inceptor JDBC 驱动：{}", exception.getMessage());
            return null;
        }
    }

    private Driver instantiate(String className, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(className, true,
                    loader == null ? getClass().getClassLoader() : loader);
            return (Driver) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOG.debug("通过 {} 加载驱动 {} 失败：{}",
                    loader == null ? "应用类路径" : "隔离类加载器", className, exception.getMessage());
            return null;
        }
    }

    private IsolatedDriverClassLoader isolatedClassLoader() {
        if (driverDirectoryScanned) {
            return isolatedLoader;
        }
        synchronized (this) {
            if (driverDirectoryScanned) {
                return isolatedLoader;
            }
            Path directory = driverDirectoryPath();
            if (Files.isDirectory(directory)) {
                List<URL> urls = new ArrayList<>();
                try (var paths = Files.list(directory)) {
                    paths.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                                    .endsWith(".jar"))
                            .forEach(path -> {
                                try {
                                    urls.add(path.toUri().toURL());
                                } catch (Exception exception) {
                                    LOG.warn("忽略无法读取的驱动文件 {}：{}", path, exception.getMessage());
                                }
                            });
                } catch (IOException exception) {
                    LOG.warn("读取驱动目录失败 {}：{}", directory, exception.getMessage());
                }
                if (!urls.isEmpty()) {
                    LOG.info("从 {} 隔离加载 {} 个驱动 jar（不进入应用类路径，避免与 Logback/SLF4J 冲突）",
                            directory.toAbsolutePath(), urls.size());
                    isolatedLoader = new IsolatedDriverClassLoader(
                            urls.toArray(URL[]::new), getClass().getClassLoader());
                }
            } else {
                LOG.debug("驱动目录不存在：{}", directory.toAbsolutePath());
            }
            driverDirectoryScanned = true;
            return isolatedLoader;
        }
    }

    private Path driverDirectoryPath() {
        String configured = properties.getDriverDir();
        Path path = Path.of(configured == null || configured.isBlank() ? "lib" : configured);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        // Relative paths are resolved beside the jar, not against the working directory, so an
        // offline deployment that starts the jar from anywhere still finds <jar-dir>/lib.
        return MetadataProperties.findApplicationDirectory().resolve(path).normalize();
    }

    /** Releases the isolated class loader (and the jar file handles) on application shutdown. */
    @Override
    public void close() {
        IsolatedDriverClassLoader loader = isolatedLoader;
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException exception) {
            LOG.debug("关闭驱动类加载器失败：{}", exception.getMessage());
        } finally {
            isolatedLoader = null;
            driverDirectoryScanned = false;
            driver = null;
            resolved = false;
        }
    }

    private static String sanitize(String url) {
        if (url == null) {
            return "";
        }
        String sanitized = url.replaceAll("(?i)([?;&](user|username|password|pwd)=)[^;&]*", "$1***");
        return sanitized.replaceAll("(?i)//[^/@:]+:[^/@]+@", "//***@");
    }

    /**
     * Logging bindings are blocked from the jar entirely; platform and Spring packages prefer the
     * application copy; everything else is loaded from the vendor jar first.
     */
    static final class IsolatedDriverClassLoader extends URLClassLoader {

        IsolatedDriverClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (isBlockedFromJar(name)) {
                        // Deliberately NOT super.loadClass / findClass: URLClassLoader falls back to
                        // the jar when the parent misses, which would let the vendor's own
                        // org.slf4j.impl.* binding through and re-introduce the startup failure.
                        loaded = applicationLoader().loadClass(name);
                    } else if (isParentFirst(name)) {
                        loaded = super.loadClass(name, false);
                    } else {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException notFound) {
                            loaded = applicationLoader().loadClass(name);
                        }
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private static boolean isParentFirst(String name) {
            for (String prefix : PARENT_FIRST_PREFIXES) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isBlockedFromJar(String name) {
            for (String prefix : BLOCKED_FROM_JAR_PREFIXES) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        private ClassLoader applicationLoader() {
            ClassLoader parent = getParent();
            return parent == null ? ClassLoader.getSystemClassLoader() : parent;
        }
    }
}

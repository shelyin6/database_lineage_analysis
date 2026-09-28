package com.xcloud.metadata.inceptor;

import com.xcloud.metadata.config.MetadataProperties;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Prints the effective database source settings at start up.
 *
 * <p>Deployments get this wrong easily (config file in the wrong folder, driver jar missing), and
 * then wonder why the UI looks like the file-only version. One line in the log makes it obvious.
 * Credentials are never logged.
 */
@Component
public class InceptorStartupLogger implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(InceptorStartupLogger.class);

    private final InceptorProperties properties;
    private final InceptorDriverLoader driverLoader;

    public InceptorStartupLogger(InceptorProperties properties, InceptorDriverLoader driverLoader) {
        this.properties = properties;
        this.driverLoader = driverLoader;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOG.info("应用目录={}；external config locations={}",
                MetadataProperties.findApplicationDirectory(),
                System.getProperty("spring.config.additional-location"));
        LOG.info("工作目录={}；数据库接入 enabled={}，表={}，驱动目录={}，连接地址={}",
                Path.of(System.getProperty("user.dir")).toAbsolutePath(),
                properties.isEnabled(),
                properties.getProcedureTable(),
                driverLoader.driverDirectory(),
                sanitize(properties.getUrl()));
        if (properties.isEnabled()) {
            // Deliberately does NOT touch driverLoader.description()/isAvailable(): resolving the
            // driver here would run the vendor driver's static initialiser during start up and make
            // boot noticeably slower. The driver is loaded on first use and then kept.
            LOG.info("数据库接入已启用：驱动类={}（首次查询时才加载，不拖慢启动）；"
                            + "可用 GET /api/catalog/status 查看驱动、连接池与缓存统计，"
                            + "用 GET /api/catalog/status/verify 做连通性自检",
                    properties.getDriverClassName());
        } else {
            LOG.info("数据库接入未启用（metadata.inceptor.enabled=false）：外部 application.yml 需放在"
                    + "工作目录、jar 所在目录或其 config/ 子目录下，命令行参数优先级最高");
        }
    }

    private static String sanitize(String url) {
        if (url == null || url.isBlank()) {
            return "(未配置)";
        }
        String sanitized = url.replaceAll("(?i)([?;&](user|username|password|pwd)=)[^;&]*", "$1***");
        return sanitized.replaceAll("(?i)//[^/@:]+:[^/@]+@", "//***@");
    }
}

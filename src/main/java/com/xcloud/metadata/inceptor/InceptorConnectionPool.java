package com.xcloud.metadata.inceptor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tiny JDBC connection pool for the catalogue source.
 *
 * <p>Measured on the intranet: opening a session costs 0.1~0.6 s, and a metadata search only takes
 * ~0.3 s, so creating a fresh connection per request was a large share of the latency. Connections
 * are kept open, validated before reuse, dropped when they look broken and closed after an idle
 * timeout.
 */
@Component
public class InceptorConnectionPool implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(InceptorConnectionPool.class);

    private final InceptorProperties properties;
    private final InceptorDriverLoader driverLoader;
    private final Deque<IdleConnection> idle = new ArrayDeque<>();
    private final Object lock = new Object();

    private int openCount;
    private long createdCount;
    private long borrowedCount;
    private long reusedCount;
    private long discardedCount;
    private boolean closed;

    public InceptorConnectionPool(InceptorProperties properties, InceptorDriverLoader driverLoader) {
        this.properties = properties;
        this.driverLoader = driverLoader;
    }

    /** Borrows a connection; the caller must hand it back with {@link #release} or {@link #discard}. */
    public Connection borrow() throws SQLException {
        long deadline = System.currentTimeMillis() + Math.max(1, properties.getConnectionWaitMillis());
        synchronized (lock) {
            while (true) {
                purgeExpiredIdle();
                while (!idle.isEmpty()) {
                    IdleConnection candidate = idle.removeFirst();
                    if (isUsable(candidate.connection())) {
                        reusedCount++;
                        borrowedCount++;
                        return candidate.connection();
                    }
                    closeInternal(candidate.connection());
                }
                if (closed) {
                    throw new SQLException("连接池已关闭");
                }
                if (openCount < maxSize()) {
                    openCount++;
                    break;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new SQLException("连接池已满（上限 " + maxSize() + "），请稍后重试");
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("等待空闲连接被中断");
                }
            }
        }

        try {
            Connection connection = driverLoader.connect(
                    properties.getUrl(),
                    properties.getUsername(),
                    properties.getPassword(),
                    properties.getConnectTimeoutSeconds());
            synchronized (lock) {
                createdCount++;
                borrowedCount++;
            }
            return connection;
        } catch (SQLException exception) {
            synchronized (lock) {
                openCount = Math.max(0, openCount - 1);
                lock.notifyAll();
            }
            throw exception;
        }
    }

    /** Returns a healthy connection to the pool. */
    public void release(Connection connection) {
        if (connection == null) {
            return;
        }
        synchronized (lock) {
            if (closed || !isUsable(connection)) {
                closeInternal(connection);
            } else {
                idle.addFirst(new IdleConnection(connection, System.currentTimeMillis()));
            }
            lock.notifyAll();
        }
    }

    /** Drops a connection that failed mid query instead of reusing it. */
    public void discard(Connection connection) {
        if (connection == null) {
            return;
        }
        synchronized (lock) {
            closeInternal(connection);
            lock.notifyAll();
        }
    }

    public PoolStats stats() {
        synchronized (lock) {
            long hits = reusedCount;
            long total = borrowedCount;
            return new PoolStats(
                    openCount,
                    idle.size(),
                    maxSize(),
                    createdCount,
                    total,
                    hits,
                    discardedCount,
                    total == 0 ? 0 : (int) Math.round(hits * 100.0 / total));
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (lock) {
            closed = true;
            for (IdleConnection entry : idle) {
                closeQuietly(entry.connection());
            }
            idle.clear();
            openCount = 0;
            lock.notifyAll();
        }
        LOG.info("连接池已关闭（累计创建 {} 次，复用命中 {} 次）", createdCount, reusedCount);
    }

    private void purgeExpiredIdle() {
        long maxIdleMillis = Math.max(1, properties.getConnectionIdleSeconds()) * 1000L;
        long now = System.currentTimeMillis();
        Iterator<IdleConnection> iterator = idle.iterator();
        while (iterator.hasNext()) {
            IdleConnection entry = iterator.next();
            if (now - entry.idleSince() > maxIdleMillis) {
                iterator.remove();
                closeInternal(entry.connection());
            }
        }
    }

    private void closeInternal(Connection connection) {
        closeQuietly(connection);
        openCount = Math.max(0, openCount - 1);
        discardedCount++;
        lock.notifyAll();
    }

    private void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException exception) {
            LOG.debug("关闭连接失败：{}", exception.getMessage());
        }
    }

    /** Drivers that do not implement isValid fall back to a cheap isClosed check. */
    private boolean isUsable(Connection connection) {
        try {
            if (connection.isClosed()) {
                return false;
            }
            return connection.isValid(1);
        } catch (SQLException notSupported) {
            try {
                return !connection.isClosed();
            } catch (SQLException ignored) {
                return false;
            }
        }
    }

    private int maxSize() {
        return Math.max(1, properties.getPoolSize());
    }

    private record IdleConnection(Connection connection, long idleSince) {
    }

    public record PoolStats(
            int open,
            int idle,
            int maxSize,
            long created,
            long borrowed,
            long reused,
            long discarded,
            int reusePercent
    ) {
    }
}

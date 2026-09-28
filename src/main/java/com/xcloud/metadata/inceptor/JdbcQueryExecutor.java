package com.xcloud.metadata.inceptor;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs one catalogue operation under a hard time limit.
 *
 * <p>{@code Statement.setQueryTimeout} is only a hint and Hive-family drivers commonly ignore it, so
 * the HTTP worker thread must never wait forever: the JDBC work runs on a small pool and the caller
 * waits at most {@code metadata.inceptor.request-timeout-seconds}, then abandons the call and
 * reports a timeout. A permit bounds how many calls may be in flight, so repeated timeouts fail
 * fast instead of silently piling up requests on the database.
 */
@Component
public class JdbcQueryExecutor implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcQueryExecutor.class);

    /** Concurrent catalogue operations; the tool is single user, four is plenty. */
    private static final int MAX_CONCURRENT = 4;

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT);
    private final ExecutorService pool = Executors.newCachedThreadPool(new DaemonThreadFactory());

    public <T> T execute(String operation, long timeoutMillis, JdbcCall<T> call) {
        if (!permits.tryAcquire()) {
            throw new InceptorUnavailableException("已有 " + MAX_CONCURRENT
                    + " 个数据库操作在执行（上一次查询可能仍未返回），请稍后重试");
        }
        long startedAt = System.nanoTime();
        Future<T> future = pool.submit(() -> {
            try {
                return call.run();
            } finally {
                permits.release();
            }
        });

        try {
            T result = future.get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
            LOG.debug("{} 完成，耗时 {}ms", operation, (System.nanoTime() - startedAt) / 1_000_000L);
            return result;
        } catch (TimeoutException exception) {
            future.cancel(true);
            LOG.warn("{} 超过 {}ms 未返回，已放弃等待（数据库端可能仍在执行）", operation, timeoutMillis);
            throw new InceptorTimeoutException(operation + "超过 " + Math.max(1, timeoutMillis / 1000)
                    + " 秒未返回，已放弃等待；请缩小查询范围（指定 database 或更精确的过程名），"
                    + "或调大 metadata.inceptor.request-timeout-seconds");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new InceptorUnavailableException(operation + "被中断");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new InceptorUnavailableException(operation + "失败：" + cause.getMessage());
        }
    }

    /** Same guard for operations that do not return a value. */
    public void executeVoid(String operation, long timeoutMillis, JdbcTask task) {
        execute(operation, timeoutMillis, () -> {
            task.run();
            return null;
        });
    }

    @Override
    @PreDestroy
    public void close() {
        pool.shutdownNow();
    }

    /** JDBC work that may throw checked exceptions. */
    @FunctionalInterface
    public interface JdbcCall<T> {
        T run() throws Exception;
    }

    /** JDBC work without a result. */
    @FunctionalInterface
    public interface JdbcTask {
        void run() throws Exception;
    }

    private static final class DaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "xcloud-jdbc-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}

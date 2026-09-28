package com.xcloud.metadata.inceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code Statement.setQueryTimeout} is a hint that Hive-family drivers usually ignore, so the
 * executor is the only thing that stops a slow remote query from hanging the request forever.
 */
class JdbcQueryExecutorTest {

    private final JdbcQueryExecutor executor = new JdbcQueryExecutor();

    @AfterEach
    void closeExecutor() {
        executor.close();
    }

    @Test
    void returnsTheResultWhenTheCallFinishesInTime() {
        String result = executor.execute("测试操作", 5_000, () -> "ok");
        assertEquals("ok", result);
    }

    @Test
    void abandonsTheCallWhenItExceedsTheRequestTimeout() {
        InceptorTimeoutException exception = assertThrows(InceptorTimeoutException.class, () ->
                executor.execute("读取存储过程内容", 200, () -> {
                    Thread.sleep(5_000);
                    return "too late";
                }));
        assertTrue(exception.getMessage().contains("已放弃等待"));
        assertTrue(exception.getMessage().contains("request-timeout-seconds"));
    }

    @Test
    void propagatesRuntimeFailures() {
        assertThrows(ProcedureNotFoundException.class, () ->
                executor.executeVoid("查询", 5_000, () -> {
                    throw new ProcedureNotFoundException("未找到存储过程");
                }));
    }
}

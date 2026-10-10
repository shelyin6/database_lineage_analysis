package com.xcloud.metadata.inceptor;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import org.junit.jupiter.api.Test;

/**
 * When a read is abandoned on timeout the JDBC call keeps running and still holds its pooled
 * connection; with a small pool one or two orphans used to fill it up and every later request failed.
 */
class ConnectionPoolLeakTest {

    @Test
    void abandonedBorrowIsReclaimedInsteadOfBlockingThePool() throws Exception {
        InceptorProperties properties = new InceptorProperties();
        properties.setPoolSize(1);
        properties.setConnectionWaitMillis(500);
        properties.setConnectionMaxBorrowSeconds(1);

        InceptorDriverLoader driverLoader = mock(InceptorDriverLoader.class);
        Connection abandoned = mock(Connection.class);
        Connection fresh = mock(Connection.class);
        for (Connection connection : new Connection[]{abandoned, fresh}) {
            when(connection.isClosed()).thenReturn(false);
            when(connection.isValid(anyInt())).thenReturn(true);
        }
        when(driverLoader.connect(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(abandoned, fresh);

        InceptorConnectionPool pool = new InceptorConnectionPool(properties, driverLoader);
        try {
            pool.borrow();          // borrowed and never returned: the request was abandoned
            Thread.sleep(1100);     // let it pass connection-max-borrow-seconds

            Connection next = pool.borrow();

            assertSame(fresh, next, "the pool must open a new connection instead of failing");
            assertTrue(pool.stats().leaked() >= 1, "the abandoned connection must be counted as reclaimed");
            verify(abandoned).close();
        } finally {
            pool.close();
        }
    }
}

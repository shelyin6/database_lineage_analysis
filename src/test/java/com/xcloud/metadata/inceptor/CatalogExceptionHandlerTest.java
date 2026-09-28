package com.xcloud.metadata.inceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The UI distinguishes "too slow" from "not reachable" by status code, so the mapping has to stay
 * stable: 504 timeout, 503 unavailable, 404 unknown procedure.
 */
class CatalogExceptionHandlerTest {

    private final CatalogExceptionHandler handler = new CatalogExceptionHandler();

    @Test
    void timeoutIsReportedAsGatewayTimeout() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleTimeout(new InceptorTimeoutException("读取存储过程内容超过 60 秒未返回，已放弃等待"));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("已放弃等待"));
    }

    @Test
    void unavailableSourceIsReportedAsServiceUnavailable() {
        ResponseEntity<Map<String, Object>> response = handler.handleUnavailable(
                new InceptorUnavailableException("数据库接入未启用：请设置 metadata.inceptor.enabled=true"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("enabled"));
    }

    @Test
    void unknownProcedureIsReportedAsNotFound() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleNotFound(new ProcedureNotFoundException("未找到存储过程 ads.p_missing"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("p_missing"));
    }
}

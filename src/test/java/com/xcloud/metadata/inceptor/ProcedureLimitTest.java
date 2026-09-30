package com.xcloud.metadata.inceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Regression guard for the intranet report "the catalogue only contains 200 procedures".
 *
 * <p>The interactive page size is {@code max-rows} (200), but a catalogue refresh asks for
 * {@code catalogue-max-entries}; clamping every call to 200 silently truncated a 10k procedure
 * catalogue, which also made the 存储过程 tab look like it only searched 200 rows.
 */
class ProcedureLimitTest {

    @Test
    void interactiveSearchUsesTheConfiguredPageSize() {
        assertEquals(200, InceptorProcedureRepository.effectiveLimit(null, 200, 10000));
        assertEquals(200, InceptorProcedureRepository.effectiveLimit(0, 200, 10000));
        assertEquals(50, InceptorProcedureRepository.effectiveLimit(50, 200, 10000));
    }

    @Test
    void catalogueRefreshMayExceedThePageSizeUpToItsCeiling() {
        assertEquals(10000, InceptorProcedureRepository.effectiveLimit(10000, 200, 10000));
        assertEquals(10000, InceptorProcedureRepository.effectiveLimit(50000, 200, 10000));
        assertEquals(3000, InceptorProcedureRepository.effectiveLimit(3000, 200, 10000));
    }

    @Test
    void theCeilingAlwaysWins() {
        assertEquals(500, InceptorProcedureRepository.effectiveLimit(5000, 200, 500));
        assertEquals(1, InceptorProcedureRepository.effectiveLimit(5000, 200, 0));
    }
}

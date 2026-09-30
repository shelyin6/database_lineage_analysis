package com.xcloud.metadata.inceptor;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only database catalogue endpoints.
 *
 * <p>Everything under {@code /api/catalog} requires {@code metadata.inceptor.enabled=true}; the
 * endpoints only ever SELECT from the configured metadata table.
 */
@RestController
@RequestMapping("/api/catalog")
public class ProcedureCatalogController {

    private final ProcedureCatalogService service;

    public ProcedureCatalogController(ProcedureCatalogService service) {
        this.service = service;
    }

    /** Feature flag, sanitized endpoint information, connection pool and source cache counters. */
    @GetMapping("/status")
    public CatalogStatus status() {
        return service.status();
    }

    /** Optional connectivity probe (issues a single SELECT against the metadata table). */
    @GetMapping("/status/verify")
    public CatalogStatus verify() {
        return service.verifyConnection();
    }

    /**
     * Procedure lookup by name.
     *
     * @param keyword  procedure name; fuzzy match by default ({@code p_loan} matches
     *                 {@code p_loan_detail}), exact match when {@code exact=true}
     * @param database optional database filter such as {@code ads}
     * @param owner    optional owner filter such as {@code hive}
     * @param exact    exact name match instead of fuzzy match
     * @param limit    optional row limit, capped by {@code metadata.inceptor.max-rows}
     */
    @GetMapping("/procedures")
    public List<CatalogProcedureSummary> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String owner,
            @RequestParam(defaultValue = "false") boolean exact,
            @RequestParam(required = false) Integer limit
    ) {
        return service.search(keyword, database, owner, exact, limit);
    }

    /**
     * Full analysis of one procedure: target tables, source tables, parameters, header documentation
     * and the raw source text. The source is served from the cache when it was read before.
     */
    @GetMapping("/procedures/{database}/{name}/profile")
    public CatalogProcedureProfile profile(@PathVariable String database, @PathVariable String name) {
        return service.profile(database, name);
    }

    /**
     * Procedure list used by the 存储过程 tab when the database source is enabled: the same fuzzy
     * keyword / database / owner conditions as {@link #search}, but merged with the local index so
     * already parsed procedures show their target/source table counts and the rest are marked as
     * "尚未解析". Unlike the file based list this query always runs against the database, so it is not
     * limited to the procedures that happen to be indexed locally.
     */
    @GetMapping("/procedure-list")
    public java.util.List<CatalogProcedureItem> procedureList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String owner,
            @RequestParam(defaultValue = "false") boolean exact,
            @RequestParam(required = false) Integer limit
    ) {
        return service.searchForView(keyword, database, owner, exact, limit);
    }

    /**
     * Bounded batch analysis driven by a search condition, for example {@code {"database":"ads"}} or
     * {@code {"keyword":"p_loan"}}. A blank condition walks the whole catalogue, limited by
     * {@code metadata.inceptor.max-analyze-procedures}.
     */
    @PostMapping("/analyze")
    public CatalogBatchAnalysis analyze(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String owner,
            @RequestParam(defaultValue = "false") boolean exact,
            @RequestParam(required = false) Integer limit
    ) {
        return service.analyzeByCondition(new CatalogSearchRequest(keyword, database, owner, exact, limit));
    }

    /** Clears the source text cache (useful right after a deployment, when procedures changed). */
    @PostMapping("/cache/clear")
    public CatalogStatus clearCache() {
        return service.clearCache();
    }

    /**
     * Loads the procedure catalogue (name/owner/create time only, no source text) into the local
     * index, which is what makes database procedures appear in the 存储过程 tab. A blank condition
     * walks the whole catalogue, bounded by {@code metadata.inceptor.catalogue-max-entries}.
     */
    @PostMapping("/refresh")
    public CatalogStatus refresh(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String owner,
            @RequestParam(defaultValue = "false") boolean exact,
            @RequestParam(required = false) Integer limit
    ) {
        return service.refreshCatalogue(keyword, database, owner, exact, limit);
    }

    /**
     * Drops the locally indexed procedures.
     *
     * @param catalogue when true the procedure names are dropped as well, so the 存储过程 tab falls
     *                  back to the local .sql files only
     */
    @PostMapping("/index/clear")
    public CatalogStatus clearIndex(
            @RequestParam(name = "catalogue", defaultValue = "false") boolean includeCatalogue
    ) {
        return service.clearIndex(includeCatalogue);
    }
}

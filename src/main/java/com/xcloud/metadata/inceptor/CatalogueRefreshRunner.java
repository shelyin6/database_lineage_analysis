package com.xcloud.metadata.inceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Loads the database procedure catalogue in the background right after start up.
 *
 * <p>This is the piece that makes the viewer behave like the original project on an intranet machine
 * that has no local .sql files: the 存储过程 tab lists the procedures of
 * {@code system.procedures_v} (name, owner, create time) instead of being empty, and each of them can
 * be opened to read and index its source text.
 *
 * <p>The refresh is a metadata-only query, runs on its own thread, and never fails or delays the
 * start up: if the database is unreachable the previously persisted index is kept.
 */
@Component
public class CatalogueRefreshRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogueRefreshRunner.class);

    private final InceptorProperties properties;
    private final ProcedureCatalogService catalogService;

    public CatalogueRefreshRunner(InceptorProperties properties, ProcedureCatalogService catalogService) {
        this.properties = properties;
        this.catalogService = catalogService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled() || !properties.isCatalogueRefreshOnStartup()) {
            LOG.info("启动时不刷新数据库过程目录（enabled={}，catalogue-refresh-on-startup={}）",
                    properties.isEnabled(), properties.isCatalogueRefreshOnStartup());
            return;
        }
        Thread refresh = new Thread(this::refresh, "inceptor-catalogue-refresh");
        refresh.setDaemon(true);
        refresh.start();
        LOG.info("已在后台线程刷新数据库过程目录（只查过程名，不读 full_text），"
                + "完成后“存储过程”页签会列出 system.procedures_v 中的过程");
    }

    private void refresh() {
        try {
            CatalogStatus status = catalogService.refreshCatalogue(null, null, null, false, null);
            LOG.info("启动刷新完成：过程目录 {} 条，已解析 {} 条；未解析的过程在“存储过程”页签点开时按需读取",
                    status.index().catalogueSize(), status.index().parsedSize());
        } catch (RuntimeException exception) {
            LOG.warn("启动刷新数据库过程目录失败：{}（索引保持上一次结果，可在“数据库”页签手动刷新）",
                    exception.getMessage());
        }
    }
}

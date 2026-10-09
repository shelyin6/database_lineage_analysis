const { createApp } = Vue;

createApp({
    data() {
        return {
            activeTab: "tables",
            summary: null,
            schemas: [],
            query: "",
            schema: "",
            relation: "",
            tableType: "",
            tableStatus: "",
            tables: [],
            procedures: [],
            selectedTable: null,
            selectedProcedure: null,
            loading: false,
            error: "",
            collapsedGroups: {},
            tableListCache: {},
            procedureListCache: {},
            overviewTables: [],
            overviewQuery: "",
            overviewSchemaFilters: [],
            overviewGroupFilters: [[], [], []],
            overviewStatusFilters: [],
            overviewPage: 1,
            overviewPageSize: 50,
            overviewFilterOpen: "",
            collapsedOutlineNodes: {},
            tableDetailCache: {},
            procedureDetailCache: {},
            catalogStatus: null,
            catalogOwner: "",
            catalogExact: false,
            catalogProfile: null,
            catalogBatch: null,
            catalogError: "",
            catalogClearing: false,
            catalogRefreshing: false,
            detailLoading: false,
            lastSelectedTableId: "",
            lastSelectedProcedureId: "",
            lastTableState: null,
            lastProcedureState: null,
            tableCommentDraft: "",
            codeValueDrafts: {},
            annotationEditing: false,
            codeValueEditing: {},
            savingAnnotation: false,
            savingCodeColumn: "",
            success: "",
            successTimer: null,
            tableDetailTab: "basic",
            profileDraft: null,
            profileEditing: false,
            generalCodeSets: [],
            generalCodeModal: false,
            generalCodeDraft: null,
            dictionaryModal: null,
            lineageModal: null,
            lineageLoading: false,
            lineageError: "",
            lineageExpanded: {},
            navigationIndex: 0,
            navigationMaxIndex: 0,
            popStateHandler: null,
            shortcutHandler: null,
            favoriteIds: {},
            showFavoritesOnly: false,
            detailFocus: false,
            columnQuery: "",
            colorTheme: "light",
            sidebarCollapsed: false,
            groupingMode: "system",
            procedureFocusLine: 0,
            authLoading: true,
            currentUser: null,
            loginForm: { username: "", password: "" },
            loginError: "",
            loginSubmitting: false,
            loginLogsModal: false,
            loginLogs: [],
            loginLogsLoading: false,
            customGroupThemes: [],
            customThemeId: null,
            customThemeDraft: "",
            customThemeSaving: false,
            customThemeDeleting: false,
            customGroups: [],
            customGroupAssignments: {},
            customGroupModal: false,
            customGroupDraft: { objectType: "TABLE", groupName: "", parentId: null },
            customGroupSaving: false,
            groupAssignmentSaving: "",
            adjustments: [],
            adjustmentListModal: false,
            adjustmentModal: null,
            adjustmentDraft: "",
            adjustmentSaving: false
        };
    },
    computed: {
        isAdmin() {
            return this.currentUser?.admin === true;
        },
        activeItems() {
            if (this.activeTab === "tables") return this.tables;
            if (this.activeTab === "procedures") return this.procedures;
            return this.overviewTables;
        },
        visibleItems() {
            if (!this.showFavoritesOnly) return this.activeItems;
            return this.activeItems.filter(item => this.isFavorite(item));
        },
        activeItemCount() {
            return this.visibleItems.length;
        },
        schemaOptions() {
            // With the database source enabled the procedure list is a live query against
            // system.procedures_v, so the filter has to offer the databases of the catalogue
            // instead of the schemas of the locally parsed tables.
            if (this.activeTab === "procedures" && this.databaseProcedureSearch()) {
                const databases = this.catalogStatus?.index?.databases || [];
                if (databases.length > 0) return databases;
            }
            return this.schemas;
        },
        adjustmentCount() {
            return this.adjustments.length;
        },
        currentCustomTheme() {
            return this.customGroupThemes.find(theme => Number(theme.id) === Number(this.customThemeId))
                || this.customGroupThemes[0]
                || null;
        },
        customThemeName() {
            return this.currentCustomTheme?.themeName || "默认主题";
        },
        overviewVisibleTables() {
            const needle = this.normalizeObjectKey(this.overviewQuery);
            if (!needle) return this.overviewTables;
            return this.overviewTables.filter(table => [
                table.schema,
                table.name,
                table.qualifiedName,
                table.comment,
                table.manualComment,
                table.chineseName
            ].some(value => this.normalizeObjectKey(value).includes(needle)));
        },
        overviewTree() {
            return this.groupingMode === "custom"
                ? this.buildCustomOverviewTree(this.overviewVisibleTables)
                : this.buildSystemOverviewTree(this.overviewVisibleTables);
        },
        overviewTableRows() {
            return this.buildOverviewTableRows(this.overviewVisibleTables);
        },
        tableNavigationTree() {
            if (this.activeTab !== "tables") return [];
            return this.buildTableNavigationTree(this.visibleItems);
        },
        tableTreeRows() {
            const rows = [];
            const visit = (nodes, level) => {
                for (const node of nodes) {
                    const hasChildren = Array.isArray(node.children) && node.children.length > 0;
                    rows.push({ key: node.key, node, level, hasChildren });
                    if (hasChildren && !this.isTableTreeCollapsed(node)) {
                        visit(node.children, level + 1);
                    }
                }
            };
            visit(this.tableNavigationTree, 1);
            return rows;
        },
        overviewNavigationTree() {
            if (this.activeTab !== "overview") return [];
            return this.buildTableNavigationTree(this.overviewFilteredTableRows.map(row => row.table));
        },
        outlineTreeRows() {
            const rows = [];
            const visit = (nodes, level) => {
                for (const node of nodes) {
                    const hasChildren = Array.isArray(node.children) && node.children.length > 0;
                    rows.push({ key: node.key, node, level, hasChildren });
                    if (hasChildren && !this.isOutlineTreeCollapsed(node)) {
                        visit(node.children, level + 1);
                    }
                }
            };
            visit(this.overviewNavigationTree, 1);
            return rows;
        },
        overviewGroupColumns() {
            const depth = this.overviewTableRows.reduce(
                (maximum, row) => Math.max(maximum, row.groupPath.length),
                1
            );
            return Array.from({ length: depth }, (_, index) => ({
                index,
                label: `分组 ${index + 1}`
            }));
        },
        overviewSchemaOptions() {
            return [...new Set(this.overviewTableRows.map(row => row.schema))]
                .sort((left, right) => this.overviewSchemaOrder(left) - this.overviewSchemaOrder(right) || left.localeCompare(right));
        },
        overviewGroupLevelOptions() {
            return Array.from({ length: 3 }, (_, level) => [...new Set(
                this.overviewTableRows
                    .map(row => row.groupPath[level])
                    .filter(Boolean)
            )].sort((left, right) => left.localeCompare(right)));
        },
        overviewStatusOptions() {
            return [
                { value: "normal", label: "正常" },
                { value: "orphan", label: "孤表" },
                { value: "temporary", label: "临时表" },
                { value: "adjustment", label: "已标记调整" }
            ];
        },
        overviewFilteredTableRows() {
            return this.overviewTableRows.filter(row => {
                if (this.overviewSchemaFilters.length > 0 && !this.overviewSchemaFilters.includes(row.schema)) return false;
                for (let level = 0; level < 3; level++) {
                    const selected = this.overviewGroupFilters[level] || [];
                    if (selected.length > 0 && !selected.includes(row.groupPath[level] || "")) return false;
                }
                return this.overviewStatusFilters.length === 0
                    || this.overviewStatusFilters.some(status => this.overviewStatusMatches(row, status));
            });
        },
        overviewPageCount() {
            return Math.max(1, Math.ceil(this.overviewFilteredTableRows.length / this.overviewPageSize));
        },
        overviewPageRows() {
            const page = Math.min(Math.max(1, Number(this.overviewPage) || 1), this.overviewPageCount);
            const start = (page - 1) * this.overviewPageSize;
            return this.overviewFilteredTableRows.slice(start, start + this.overviewPageSize);
        },
        overviewPageStart() {
            if (this.overviewFilteredTableRows.length === 0) return 0;
            const page = Math.min(Math.max(1, Number(this.overviewPage) || 1), this.overviewPageCount);
            return (page - 1) * this.overviewPageSize;
        },
        overviewPageEnd() {
            return this.overviewPageStart + this.overviewPageRows.length;
        },
        overviewPaginationPages() {
            const total = this.overviewPageCount;
            const current = Math.min(Math.max(1, Number(this.overviewPage) || 1), total);
            const start = Math.max(1, Math.min(current - 2, total - 4));
            const end = Math.min(total, start + 4);
            return Array.from({ length: end - start + 1 }, (_, index) => start + index);
        },
        overviewHasFilters() {
            return Boolean(
                this.overviewQuery
                || this.overviewSchemaFilters.length
                || this.overviewGroupFilters.some(filter => filter.length)
                || this.overviewStatusFilters.length
            );
        },
        hasActiveFilters() {
            return Boolean(this.query.trim() || this.schema || this.relation || this.tableType || this.tableStatus || this.showFavoritesOnly);
        },
        visibleColumns() {
            const columns = this.selectedTable?.table?.columns ?? [];
            const needle = this.columnQuery.trim().toLowerCase();
            if (!needle) return columns;
            return columns.filter(column => [column.name, column.comment, column.dataType, column.typeName]
                .some(value => String(value || "").toLowerCase().includes(needle)));
        },
        tableGroups() {
            if (this.activeTab !== "tables") return [];

            return this.buildObjectGroups(this.visibleItems, {
                ALMP: ["DM_I", "DM_A", "DM_S", "DM_O", "临时表", "其他"],
                IDS: ["DM_S", "临时表", "其他"]
            }, table => this.objectGroupDescriptor("TABLE", table), "table");
        },
        procedureGroups() {
            if (this.activeTab !== "procedures") return [];

            return this.buildObjectGroups(this.visibleItems, {
                ALMP: ["PROC_DM_I", "PROC_DM_A", "PROC_DM_S", "PROC_DM_O", "临时表", "其他"],
                IDS: ["PROC_DM_S", "临时表", "其他"]
            }, procedure => this.objectGroupDescriptor("PROCEDURE", procedure), "procedure");
        },
        selectedTableColumns() {
            return this.selectedTable?.table?.columns ?? [];
        },
        selectedProcedureLines() {
            if (!this.selectedProcedure) return [];
            const startLine = Number(this.selectedProcedure.startLine || 1);
            return String(this.selectedProcedure.rawSql || "").split(/\r?\n/).map((text, index) => ({
                number: startLine + index,
                text
            }));
        },
        lineageTreeRows() {
            const tree = this.lineageModal?.tree;
            if (!tree) return [];
            const rows = [];
            const visit = (node, depth, key) => {
                rows.push({ node, depth, key });
                if (node.children?.length && this.lineageExpanded[key] === true) {
                    node.children.forEach((child, index) => visit(child, depth + 1, `${key}.${index}`));
                }
            };
            visit(tree, 0, "root");
            return rows;
        },
        detailTitle() {
            if (this.activeTab === "overview") {
                return "表总览大纲";
            }
            if (this.activeTab === "tables" && this.selectedTable) {
                return this.selectedTable.table.qualifiedName;
            }
            if (this.activeTab === "procedures" && this.selectedProcedure) {
                return this.selectedProcedure.qualifiedName;
            }
            return "请选择左侧对象";
        },
        canGoBack() {
            return this.navigationIndex > 0;
        },
        canGoForward() {
            return this.navigationIndex < this.navigationMaxIndex;
        },
        filterSummary() {
            const count = this.formatNumber(this.activeItemCount);
            if (this.activeTab === "overview") return `${count} 张表`;
            return this.activeTab === "tables" ? `${count} 张表` : `${count} 个过程`;
        }
    },
    watch: {
        overviewQuery() {
            this.overviewPage = 1;
        },
        groupingMode() {
            this.overviewPage = 1;
        },
        overviewSchemaFilters: {
            handler() {
                this.overviewPage = 1;
            },
            deep: true
        },
        overviewGroupFilters: {
            handler() {
                this.overviewPage = 1;
            },
            deep: true
        },
        overviewStatusFilters: {
            handler() {
                this.overviewPage = 1;
            },
            deep: true
        },
        overviewPageSize() {
            this.overviewPage = 1;
        }
    },
    async mounted() {
        this.loadPreferences();
        this.shortcutHandler = event => this.handleKeyboardShortcut(event);
        window.addEventListener("keydown", this.shortcutHandler);
        this.popStateHandler = () => {
            void this.handlePopState();
        };
        window.addEventListener("popstate", this.popStateHandler);
        await this.bootstrap();
    },
        beforeUnmount() {
            if (this.popStateHandler) {
                window.removeEventListener("popstate", this.popStateHandler);
            }
            if (this.shortcutHandler) {
                window.removeEventListener("keydown", this.shortcutHandler);
            }
            if (this.successTimer) window.clearTimeout(this.successTimer);
    },
    methods: {
        async bootstrap() {
            this.authLoading = true;
            this.loginError = "";
            try {
                this.currentUser = await this.getJson("/api/auth/me", { suppressUnauthorized: true });
                await this.loadWorkspace();
            } catch (error) {
                if (error.status === 401) {
                    this.currentUser = null;
                    return;
                }
                this.loginError = error.message || "无法验证登录状态";
            } finally {
                this.authLoading = false;
            }
        },
        async loadWorkspace() {
            const route = this.readRoute();
            this.applyRoute(route);
            this.initializeHistoryState();
            await Promise.all([this.loadSummary(), this.loadSchemas(), this.loadAnnotationOverview()]);
            await this.loadCatalogStatus();
            await this.search({ history: "replace", objectId: route.objectId, focusLine: route.focusLine });
        },
        async openOverview() {
            this.rememberTabState();
            if (this.activeTab === "overview") {
                this.activeTab = "tables";
                this.overviewQuery = "";
                await this.search({ history: "push" });
                return;
            }
            this.activeTab = "overview";
            this.selectedTable = null;
            this.selectedProcedure = null;
            this.clearOverviewFilters();
            await this.search({ history: "push" });
        },
        loadPreferences() {
            try {
                const stored = JSON.parse(localStorage.getItem("sql-metadata-viewer-preferences") || "{}");
                this.favoriteIds = stored.favoriteIds && typeof stored.favoriteIds === "object" ? stored.favoriteIds : {};
                this.collapsedGroups = stored.collapsedGroups && typeof stored.collapsedGroups === "object" ? stored.collapsedGroups : {};
                this.collapsedOutlineNodes = stored.collapsedOutlineNodes && typeof stored.collapsedOutlineNodes === "object"
                    ? stored.collapsedOutlineNodes
                    : {};
                this.colorTheme = stored.colorTheme === "dark" ? "dark" : "light";
                this.sidebarCollapsed = stored.sidebarCollapsed === true;
                this.groupingMode = stored.groupingMode === "custom" ? "custom" : "system";
                this.customThemeId = Number(stored.customThemeId) > 0 ? Number(stored.customThemeId) : null;
                this.detailFocus = this.sidebarCollapsed;
                document.documentElement.dataset.theme = this.colorTheme;
            } catch (error) {
                this.favoriteIds = {};
                this.collapsedGroups = {};
                this.groupingMode = "system";
                this.customThemeId = null;
            }
        },
        savePreferences() {
            localStorage.setItem("sql-metadata-viewer-preferences", JSON.stringify({
                favoriteIds: this.favoriteIds,
                collapsedGroups: this.collapsedGroups,
                collapsedOutlineNodes: this.collapsedOutlineNodes,
                colorTheme: this.colorTheme,
                sidebarCollapsed: this.sidebarCollapsed,
                groupingMode: this.groupingMode,
                customThemeId: this.customThemeId
            }));
        },
        toggleTheme() {
            this.colorTheme = this.colorTheme === "dark" ? "light" : "dark";
            document.documentElement.dataset.theme = this.colorTheme;
            this.savePreferences();
        },
        toggleSidebar() {
            this.detailFocus = !this.detailFocus;
            this.sidebarCollapsed = this.detailFocus;
            this.savePreferences();
        },
        setGroupingMode(mode) {
            if (mode !== "system" && mode !== "custom") return;
            this.groupingMode = mode;
            this.collapsedGroups = {};
            this.savePreferences();
        },
        async setCustomTheme(themeId) {
            const normalized = Number(themeId);
            if (!this.customGroupThemes.some(theme => Number(theme.id) === normalized)) return;
            this.customThemeId = normalized;
            this.collapsedGroups = {};
            this.collapsedOutlineNodes = {};
            this.overviewPage = 1;
            this.savePreferences();
            try {
                await this.loadAnnotationOverview(normalized);
                this.showSuccess(`已切换到分组主题：${this.customThemeName}`);
            } catch (error) {
                this.error = error.message;
            }
        },
        handleKeyboardShortcut(event) {
            if (!this.currentUser) return;
            const target = event.target;
            const isTyping = target && ["INPUT", "SELECT", "TEXTAREA"].includes(target.tagName);
            if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
                event.preventDefault();
                this.$refs.searchInput?.focus();
                return;
            }
            if (!isTyping && event.key === "/") {
                event.preventDefault();
                this.$refs.searchInput?.focus();
                return;
            }
            if (event.key === "Escape" && this.detailFocus) {
                this.detailFocus = false;
                this.sidebarCollapsed = false;
                this.savePreferences();
            }
        },
        favoriteKey(item) {
            const kind = Array.isArray(item?.targetTables) ? "procedure" : "table";
            return `${kind}:${item?.id || ""}`;
        },
        isFavorite(item) {
            return this.favoriteIds[this.favoriteKey(item)] === true;
        },
        toggleFavorite(item, event) {
            event?.stopPropagation();
            const key = this.favoriteKey(item);
            const wasFavorite = this.isFavorite(item);
            if (wasFavorite) {
                delete this.favoriteIds[key];
                this.showSuccess("已取消收藏");
            } else {
                this.favoriteIds[key] = true;
                this.showSuccess("已加入收藏");
            }
            this.savePreferences();
            if (wasFavorite && this.showFavoritesOnly) {
                void this.search({ history: "replace" });
            }
        },
        toggleFavoritesOnly() {
            this.showFavoritesOnly = !this.showFavoritesOnly;
        },
        clearFilters() {
            this.query = "";
            this.schema = "";
            this.relation = "";
            this.tableType = "";
            this.tableStatus = "";
            this.showFavoritesOnly = false;
            this.search();
        },
        async openMetric(metric) {
            this.rememberTabState();
            this.query = "";
            this.schema = "";
            this.tableType = "";
            this.tableStatus = "";
            this.showFavoritesOnly = false;
            if (metric === "procedures") {
                this.activeTab = "procedures";
                this.relation = "";
            } else {
                this.activeTab = "tables";
                this.relation = metric === "orphan" ? "orphan" : "";
            }
            this.selectedTable = null;
            this.selectedProcedure = null;
            await this.search({ history: "push" });
        },
        collapseAllGroups() {
            for (const group of [...this.tableGroups, ...this.procedureGroups]) {
                this.collapsedGroups[group.key] = true;
            }
            this.savePreferences();
        },
        expandAllGroups() {
            for (const group of [...this.tableGroups, ...this.procedureGroups]) {
                this.collapsedGroups[group.key] = false;
            }
            this.savePreferences();
        },
        async loadSummary() {
            this.summary = await this.getJson("/api/summary");
        },
        async loadSchemas() {
            this.schemas = await this.getJson("/api/schemas");
        },
        async loadAnnotationOverview(themeId = this.customThemeId) {
            const params = new URLSearchParams();
            if (Number(themeId) > 0) params.set("themeId", String(themeId));
            const query = params.toString();
            const overview = await this.getJson(`/api/annotations/overview${query ? `?${query}` : ""}`);
            this.applyAnnotationOverview(overview);
        },
        applyAnnotationOverview(overview) {
            this.customGroupThemes = Array.isArray(overview?.customGroupThemes) ? overview.customGroupThemes : [];
            const requestedThemeId = Number(overview?.activeCustomGroupThemeId || this.customThemeId || 0);
            this.customThemeId = this.customGroupThemes.some(theme => Number(theme.id) === requestedThemeId)
                ? requestedThemeId
                : Number(this.customGroupThemes[0]?.id || 1);
            this.customGroups = Array.isArray(overview?.customGroups) ? overview.customGroups : [];
            this.customGroupAssignments = overview?.assignments && typeof overview.assignments === "object"
                ? overview.assignments
                : {};
            this.adjustments = Array.isArray(overview?.adjustments) ? overview.adjustments : [];
            this.savePreferences();
        },
        async reload() {
            this.loading = true;
            this.error = "";
            try {
                this.summary = await this.getJson("/api/reload", { method: "POST" });
                this.clearCaches();
                await this.search();
                this.showSuccess("SQL 已重新解析");
            } catch (error) {
                this.error = error.message;
            } finally {
                this.loading = false;
            }
        },
        async login() {
            this.loginError = "";
            this.loginSubmitting = true;
            try {
                this.currentUser = await this.getJson("/api/auth/login", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(this.loginForm),
                    suppressUnauthorized: true
                });
                this.loginForm.password = "";
                this.clearCaches();
                await this.loadWorkspace();
            } catch (error) {
                this.currentUser = null;
                this.loginError = error.status === 401 ? "账号或密码错误" : (error.message || "登录失败");
            } finally {
                this.loginSubmitting = false;
            }
        },
        async logout() {
            try {
                await this.getJson("/api/auth/logout", { method: "POST", suppressUnauthorized: true });
            } catch (error) {
                if (error.status !== 401) {
                    this.loginError = error.message || "退出登录失败";
                }
            }
            this.resetAuthenticatedState();
        },
        resetAuthenticatedState() {
            this.currentUser = null;
            this.summary = null;
            this.schemas = [];
            this.tables = [];
            this.procedures = [];
            this.overviewTables = [];
            this.selectedTable = null;
            this.selectedProcedure = null;
            this.lastSelectedTableId = "";
            this.lastSelectedProcedureId = "";
            this.lastTableState = null;
            this.lastProcedureState = null;
            this.catalogProfile = null;
            this.catalogBatch = null;
            this.loginLogsModal = false;
            this.loginLogs = [];
            this.customGroupThemes = [];
            this.customThemeId = null;
            this.customThemeDraft = "";
            this.customGroups = [];
            this.customGroupAssignments = {};
            this.customGroupModal = false;
            this.adjustments = [];
            this.adjustmentListModal = false;
            this.adjustmentModal = null;
            this.clearCaches();
        },
        async openLoginLogs() {
            if (!this.isAdmin) return;
            this.loginLogsModal = true;
            this.loginLogsLoading = true;
            try {
                this.loginLogs = await this.getJson("/api/auth/login-logs");
            } catch (error) {
                this.error = error.message;
                this.loginLogsModal = false;
            } finally {
                this.loginLogsLoading = false;
            }
        },
        async search(options = {}) {
            const historyMode = ["push", "replace", "none"].includes(options.history)
                ? options.history
                : "push";
            const objectId = options.objectId || "";
            const focusLine = Number(options.focusLine || 0);
            const focusColumn = options.focusColumn || "";
            // "restore" (tab switch) falls back to the first row, a deep link does not: selecting an
            // unrelated object would be more confusing than showing "nothing found".
            const fallbackToFirst = options.fallbackToFirst === true;
            this.loading = true;
            this.error = "";
            try {
                const params = new URLSearchParams();
                if (this.query.trim()) params.set("q", this.query.trim());
                if (this.schema) params.set("schema", this.schema);
                if (this.activeTab === "tables" && this.relation) params.set("relation", this.relation);
                if (this.activeTab === "tables" && this.tableType) params.set("tableType", this.tableType);
                if (this.activeTab === "tables" && this.tableStatus) params.set("tableStatus", this.tableStatus);
                const cacheKey = params.toString();
                if (this.activeTab === "tables") {
                this.tables = await this.getList("tables", cacheKey);
                    if (this.tables.length > 0) {
                        const selectable = this.showFavoritesOnly ? this.tables.filter(item => this.isFavorite(item)) : this.tables;
                        const selected = this.findListItem(selectable, objectId)
                            || (objectId && !fallbackToFirst ? null : selectable[0]);
                        if (!selected) {
                            this.selectedTable = null;
                        } else {
                        await this.selectTable(selected, { history: "none", focusColumn });
                        }
                    } else {
                        this.selectedTable = null;
                    }
                } else if (this.activeTab === "procedures") {
                this.procedures = await this.fetchProcedureList(this.procedureSearchKey());
                if (this.procedures.length > 0) {
                        const selectable = this.showFavoritesOnly ? this.procedures.filter(item => this.isFavorite(item)) : this.procedures;
                        const selected = this.findListItem(selectable, objectId) || selectable[0];
                        if (!selected) {
                            this.selectedProcedure = null;
                        } else {
                            await this.selectProcedure(selected, { history: "none", focusLine });
                        }
                    } else {
                        this.selectedProcedure = null;
                    }
                } else {
                    this.overviewTables = await this.getList("tables", "");
                    this.selectedTable = null;
                    this.selectedProcedure = null;
                }
                this.syncHistory(historyMode);
            } catch (error) {
                this.error = error.message;
            } finally {
                this.loading = false;
            }
        },
        /** Remembers the filters of a tab, so switching back does not lose keyword/library filters. */
        rememberTabState(tab = this.activeTab) {
            if (tab === "tables") {
                this.lastTableState = {
                    query: this.query,
                    schema: this.schema,
                    relation: this.relation,
                    tableType: this.tableType,
                    tableStatus: this.tableStatus
                };
            } else if (tab === "procedures") {
                this.lastProcedureState = {
                    query: this.query,
                    schema: this.schema
                };
            }
        },
        restoreTabState(tab) {
            const state = tab === "tables" ? this.lastTableState : tab === "procedures" ? this.lastProcedureState : null;
            if (!state) return;
            this.query = state.query || "";
            this.schema = state.schema || "";
            if (tab === "tables") {
                this.relation = state.relation || "";
                this.tableType = state.tableType || "";
                this.tableStatus = state.tableStatus || "";
            }
        },
        async switchTab(tab) {
            if (this.activeTab !== tab) {
                this.rememberTabState(this.activeTab);
            }
            this.activeTab = tab;
            this.columnQuery = "";
            if (tab === "tables") {
                this.selectedProcedure = null;
                this.restoreTabState("tables");
            } else if (tab === "procedures") {
                this.selectedTable = null;
                this.restoreTabState("procedures");
            }
            // Re-open the object that was selected in this tab last time instead of jumping back to
            // the first row (for example after following a dependency table and coming back).
            const restoreId = tab === "tables"
                ? this.lastSelectedTableId
                : tab === "procedures" ? this.lastSelectedProcedureId : "";
            await this.search({ history: "push", objectId: restoreId, fallbackToFirst: true });
        },
        async selectTable(table, options = {}) {
            this.selectedProcedure = null;
            this.catalogProfile = null;
            this.procedureFocusLine = 0;
            this.selectedTable = await this.getDetail("tables", table.id);
            this.lastSelectedTableId = this.selectedTable?.table?.id || table.id;
            this.tableDetailTab = options.focusColumn ? "columns" : "basic";
            this.columnQuery = options.focusColumn || "";
            this.prepareTableEditors();
            this.syncHistory(options.history === "none" ? "none" : "push");
        },
        async selectProcedure(procedure, options = {}) {
            this.selectedTable = null;
            this.procedureFocusLine = Number(options.focusLine || 0);
            const wasUnparsed = this.isUnparsedProcedure(procedure);
            this.detailLoading = true;
            try {
                if (this.isDatabaseProcedureItem(procedure)) {
                    // Database procedures are read through the catalogue endpoint: it returns the parsed
                    // analysis together with the "读取方式（缓存命中/耗时）" details, and it indexes the
                    // result so the table and lineage views pick it up.
                    const database = encodeURIComponent(procedure.schema || "");
                    const name = encodeURIComponent(procedure.name || "");
                    this.catalogProfile = await this.getJson(`/api/catalog/procedures/${database}/${name}/profile`);
                    this.selectedProcedure = this.catalogProfile.analysis;
                } else {
                    this.catalogProfile = null;
                    this.selectedProcedure = await this.getDetail("procedures", procedure.id);
                }
            } finally {
                this.detailLoading = false;
            }
            this.lastSelectedProcedureId = this.selectedProcedure?.id || procedure.id;
            if (wasUnparsed) {
                // Reading a database procedure fills in its parameters / referenced tables, and can add
                // new (inferred) tables. Refresh the visible lists so the left cards show the numbers
                // right away instead of only after a restart.
                await this.refreshVisibleList();
                await this.loadCatalogStatus();
            }
            if (this.procedureFocusLine > 0) {
                this.$nextTick(() => this.scrollToProcedureLine(this.procedureFocusLine));
            }
            this.syncHistory(options.history === "none" ? "none" : "push");
        },
        async refreshCatalogIndex() {
            this.loading = true;
            this.catalogRefreshing = true;
            this.catalogError = "";
            try {
                const params = new URLSearchParams();
                if (this.query.trim()) params.set("keyword", this.query.trim());
                if (this.schema) params.set("database", this.schema);
                if (this.catalogOwner.trim()) params.set("owner", this.catalogOwner.trim());
                if (this.catalogExact) params.set("exact", "true");
                this.catalogStatus = await this.getJson(`/api/catalog/refresh?${params.toString()}`, { method: "POST" });
                this.showSuccess(`已刷新过程目录：${this.catalogStatus.index.catalogueSize} 条，可在“存储过程”页签查看`);
                this.clearCaches();
                await this.refreshVisibleList();
            } catch (error) {
                this.catalogError = error.message;
            } finally {
                this.catalogRefreshing = false;
                this.loading = false;
            }
        },
        async loadCatalogStatus() {
            try {
                this.catalogStatus = await this.getJson("/api/catalog/status");
            } catch (error) {
                this.catalogError = error.message;
            }
        },
        async analyzeCatalogBatch() {
            this.loading = true;
            this.catalogError = "";
            try {
                const params = new URLSearchParams();
                if (this.query.trim()) params.set("keyword", this.query.trim());
                if (this.schema) params.set("database", this.schema);
                if (this.catalogOwner.trim()) params.set("owner", this.catalogOwner.trim());
                if (this.catalogExact) params.set("exact", "true");
                this.catalogBatch = await this.getJson(`/api/catalog/analyze?${params.toString()}`, { method: "POST" });
                this.selectedProcedure = null;
                this.catalogProfile = null;
                this.showSuccess(`已解析并索引 ${this.catalogBatch.analyzed} 个过程，结果已并入“存储过程/表”页签`);
                this.clearCaches();
                await this.refreshVisibleList();
            } catch (error) {
                this.catalogBatch = null;
                this.catalogError = error.message;
            } finally {
                this.loading = false;
                await this.loadCatalogStatus();
            }
        },
        async clearCatalogCache() {
            this.catalogClearing = true;
            try {
                this.catalogStatus = await this.getJson("/api/catalog/cache/clear", { method: "POST" });
                this.success = "源码缓存已清空";
            } catch (error) {
                this.catalogError = error.message;
            } finally {
                this.catalogClearing = false;
            }
        },
        isDatabaseProcedureItem(procedure) {
            return String(procedure?.sourceFile || "").startsWith("inceptor://");
        },
        isUnparsedProcedure(procedure) {
            if (procedure?.parsed === false) return true;
            if (procedure?.parsed === true) return false;
            return this.isDatabaseProcedureItem(procedure) && String(procedure?.description || "").startsWith("尚未解析");
        },
        isInferredTable(table) {
            return String(table?.sourceFile || "").startsWith("inceptor://");
        },
        tableActive(table) {
            return this.selectedTable?.table?.id === table.id;
        },
        procedureActive(procedure) {
            return this.selectedProcedure?.id === procedure.id;
        },
        buildObjectGroups(items, groupDefinitions, descriptorResolver, kind) {
            const grouped = new Map();
            for (const item of items) {
                const descriptor = descriptorResolver(item);
                const schema = descriptor.schema || (item.schema || "").toUpperCase();
                const name = descriptor.name || "其他";
                const key = descriptor.key || `${kind}:${schema}:${name}`;
                if (!grouped.has(key)) {
                    grouped.set(key, {
                        key,
                        schema,
                        name,
                        custom: descriptor.custom === true,
                        items: []
                    });
                }
                grouped.get(key).items.push(item);
            }

            const groups = [];
            for (const group of grouped.values()) {
                if (group.custom) groups.push(group);
            }
            for (const [schema, names] of Object.entries(groupDefinitions)) {
                for (const name of names) {
                    const group = grouped.get(`${kind}:${schema}:${name}`);
                    if (group) groups.push(group);
                }
            }

            for (const group of grouped.values()) {
                if (!group.custom && !groupDefinitions[group.schema]) groups.push(group);
            }
            return groups;
        },
        buildTableNavigationTree(items) {
            const schemaNodes = new Map();
            for (const table of items) {
                const schema = (table.schema || "未分配").toUpperCase();
                if (!schemaNodes.has(schema)) {
                    schemaNodes.set(schema, {
                        key: `navigation:schema:${schema}`,
                        type: "group",
                        name: schema,
                        schema,
                        count: 0,
                        children: []
                    });
                }
                const schemaNode = schemaNodes.get(schema);
                schemaNode.count++;
                const assignment = this.groupingMode === "custom"
                    ? this.customGroupFor("TABLE", table)
                    : null;
                const groupPath = this.groupingMode === "custom"
                    ? (assignment ? this.customGroupPath("TABLE", assignment.id) : ["未归组"])
                    : [this.tableGroupName(table)];
                let parent = schemaNode;
                const pathKey = [];
                groupPath.forEach((name, index) => {
                    pathKey.push(name);
                    const groupKey = `navigation:${schema}:${this.groupingMode}:${pathKey.join("\u0001")}`;
                    let node = parent.children.find(child => child.key === groupKey);
                    if (!node) {
                        node = {
                            key: groupKey,
                            type: "group",
                            name,
                            count: 0,
                            children: []
                        };
                        parent.children.push(node);
                    }
                    node.count++;
                    parent = node;
                });
                parent.children.push({
                    key: `navigation:table:${table.id}`,
                    type: "table",
                    name: table.name,
                    table
                });
            }

            const sortTree = nodes => {
                nodes.sort((left, right) => {
                    const leftTable = left.type === "table" ? 1 : 0;
                    const rightTable = right.type === "table" ? 1 : 0;
                    return leftTable - rightTable
                        || String(left.name || "").localeCompare(String(right.name || ""));
                });
                for (const node of nodes) {
                    if (node.children?.length) {
                        node.children = sortTree(node.children);
                        node.defaultCollapsed = false;
                    }
                }
                return nodes;
            };
            return sortTree([...schemaNodes.values()].sort((left, right) =>
                this.overviewSchemaOrder(left.name) - this.overviewSchemaOrder(right.name)
                || left.name.localeCompare(right.name)
            ));
        },
        isTableTreeCollapsed(node) {
            if (node.type === "table") return false;
            if (Object.prototype.hasOwnProperty.call(this.collapsedGroups, node.key)) {
                return this.collapsedGroups[node.key] === true;
            }
            return node.defaultCollapsed === true;
        },
        toggleTableTreeNode(node) {
            if (node.type === "table") return;
            this.collapsedGroups[node.key] = !this.isTableTreeCollapsed(node);
            this.savePreferences();
        },
        setTableTreeCollapsed(collapsed) {
            const visit = nodes => {
                for (const node of nodes) {
                    if (node.type !== "table") {
                        this.collapsedGroups[node.key] = collapsed;
                        visit(node.children || []);
                    }
                }
            };
            visit(this.tableNavigationTree);
            this.savePreferences();
        },
        isOutlineTreeCollapsed(node) {
            return this.collapsedOutlineNodes[node.key] === true;
        },
        toggleOutlineTreeNode(node) {
            if (node.type === "table") return;
            this.collapsedOutlineNodes[node.key] = !this.isOutlineTreeCollapsed(node);
            this.savePreferences();
        },
        setOutlineTreeCollapsed(collapsed) {
            const visit = nodes => {
                for (const node of nodes) {
                    if (node.type !== "table") {
                        this.collapsedOutlineNodes[node.key] = collapsed;
                        visit(node.children || []);
                    }
                }
            };
            visit(this.overviewNavigationTree);
            this.savePreferences();
        },
        buildSystemOverviewTree(items) {
            const schemaDefinitions = {
                ALMP: ["DM_I", "DM_A", "DM_S", "DM_O", "临时表", "其他"],
                IDS: ["DM_S", "临时表", "其他"]
            };
            const schemas = new Map();
            for (const table of items) {
                const schema = (table.schema || "未分配").toUpperCase();
                const groupName = this.tableGroupName(table);
                const groupKey = `overview:system:${schema}:${groupName}`;
                if (!schemas.has(schema)) {
                    schemas.set(schema, {
                        key: `overview:${schema}`,
                        type: "group",
                        name: schema,
                        count: 0,
                        children: []
                    });
                }
                const schemaNode = schemas.get(schema);
                schemaNode.count++;
                let groupNode = schemaNode.children.find(group => group.key === groupKey);
                if (!groupNode) {
                    groupNode = {
                        key: groupKey,
                        type: "group",
                        name: groupName,
                        count: 0,
                        children: []
                    };
                    schemaNode.children.push(groupNode);
                }
                groupNode.count++;
                groupNode.children.push({
                    key: `overview:${table.id}`,
                    type: "table",
                    table
                });
            }

            return [...schemas.values()]
                .sort((left, right) => this.overviewSchemaOrder(left.name) - this.overviewSchemaOrder(right.name) || left.name.localeCompare(right.name))
                .map(schemaNode => {
                    const preferred = schemaDefinitions[schemaNode.name] || [];
                    const groups = schemaNode.children;
                    groups.sort((left, right) => {
                        const leftOrder = preferred.indexOf(left.name);
                        const rightOrder = preferred.indexOf(right.name);
                        const normalizedLeft = leftOrder < 0 ? Number.MAX_SAFE_INTEGER : leftOrder;
                        const normalizedRight = rightOrder < 0 ? Number.MAX_SAFE_INTEGER : rightOrder;
                        return normalizedLeft - normalizedRight || left.name.localeCompare(right.name);
                    });
                    return {
                        ...schemaNode,
                        children: groups.map(group => ({
                            ...group,
                            children: group.children.sort((left, right) =>
                                String(left.table.name || "").localeCompare(String(right.table.name || "")))
                        }))
                    };
                });
        },
        buildCustomOverviewTree(items) {
            const groupNodes = new Map();
            for (const group of this.customGroups.filter(item => item.objectType === "TABLE")) {
                groupNodes.set(Number(group.id), {
                    key: `overview:custom-group:${group.id}`,
                    type: "group",
                    name: group.groupName,
                    groupId: Number(group.id),
                    count: 0,
                    children: []
                });
            }

            const roots = [];
            for (const group of this.customGroups.filter(item => item.objectType === "TABLE")) {
                const node = groupNodes.get(Number(group.id));
                const parent = group.parentId == null ? null : groupNodes.get(Number(group.parentId));
                if (parent && parent !== node) {
                    parent.children.push(node);
                } else if (node) {
                    roots.push(node);
                }
            }

            const unassignedBySchema = new Map();
            for (const table of items) {
                const assignment = this.customGroupFor("TABLE", table);
                const target = assignment ? groupNodes.get(Number(assignment.id)) : null;
                const tableNode = {
                    key: `overview:${table.id}`,
                    type: "table",
                    table
                };
                if (target) {
                    target.children.push(tableNode);
                    continue;
                }
                const schema = (table.schema || "未分配").toUpperCase();
                if (!unassignedBySchema.has(schema)) {
                    unassignedBySchema.set(schema, {
                        key: `overview:custom-unassigned:${schema}`,
                        type: "group",
                        name: schema,
                        count: 0,
                        children: []
                    });
                }
                unassignedBySchema.get(schema).children.push(tableNode);
            }

            if (unassignedBySchema.size > 0) {
                roots.push({
                    key: "overview:custom-unassigned",
                    type: "group",
                    name: "未归入自定义分组",
                    count: 0,
                    children: [...unassignedBySchema.values()]
                        .sort((left, right) => this.overviewSchemaOrder(left.name) - this.overviewSchemaOrder(right.name) || left.name.localeCompare(right.name))
                });
            }

            const sortTree = nodes => {
                nodes.sort((left, right) => {
                    const leftTable = left.type === "table" ? 1 : 0;
                    const rightTable = right.type === "table" ? 1 : 0;
                    const leftName = left.type === "table" ? left.table?.name || "" : left.name || "";
                    const rightName = right.type === "table" ? right.table?.name || "" : right.name || "";
                    return leftTable - rightTable || leftName.localeCompare(rightName);
                });
                for (const node of nodes) {
                    if (node.children?.length) sortTree(node.children);
                }
            };
            const countTree = node => {
                if (node.type === "table") return 1;
                node.count = (node.children || []).reduce((total, child) => total + countTree(child), 0);
                return node.count;
            };
            roots.forEach(countTree);
            sortTree(roots);
            return roots;
        },
        buildOverviewTableRows(items) {
            const rows = items.map(table => {
                const schema = (table.schema || "未分配").toUpperCase();
                let groupPath;
                if (this.groupingMode === "custom") {
                    const assignment = this.customGroupFor("TABLE", table);
                    groupPath = assignment
                        ? this.customGroupPath("TABLE", assignment.id)
                        : ["未归入自定义分组"];
                    if (groupPath.length === 0) groupPath = ["未归组"];
                } else {
                    groupPath = [this.tableGroupName(table)];
                }
                return {
                    key: `overview-table:${table.id}`,
                    table,
                    schema,
                    groupPath,
                    groupText: groupPath.join(" / ")
                };
            });
            return rows.sort((left, right) =>
                this.overviewSchemaOrder(left.schema) - this.overviewSchemaOrder(right.schema)
                || left.groupText.localeCompare(right.groupText)
                || String(left.table.name || "").localeCompare(String(right.table.name || ""))
            );
        },
        overviewStatusMatches(row, status) {
            if (status === "orphan") return row.table.orphan === true;
            if (status === "temporary") return this.isTemporaryTable(row.table.name);
            if (status === "adjustment") return this.isAdjusted("TABLE", row.table);
            if (status === "normal") {
                return !row.table.orphan
                    && !this.isTemporaryTable(row.table.name)
                    && !this.isAdjusted("TABLE", row.table);
            }
            return true;
        },
        overviewFilterLabel(filter) {
            const values = this.overviewFilterValues(filter);
            return values.length ? `已选 ${values.length}` : "全部";
        },
        overviewFilterValues(filter) {
            if (filter === "schema") return this.overviewSchemaFilters;
            if (filter === "status") return this.overviewStatusFilters;
            const match = /^group([1-3])$/.exec(filter);
            return match ? (this.overviewGroupFilters[Number(match[1]) - 1] || []) : [];
        },
        toggleOverviewFilter(filter) {
            this.overviewFilterOpen = this.overviewFilterOpen === filter ? "" : filter;
        },
        closeOverviewFilterMenus() {
            this.overviewFilterOpen = "";
        },
        isOverviewFilterSelected(filter, value) {
            return this.overviewFilterValues(filter).includes(value);
        },
        toggleOverviewFilterValue(filter, value) {
            const current = [...this.overviewFilterValues(filter)];
            const index = current.indexOf(value);
            if (index >= 0) current.splice(index, 1);
            else current.push(value);

            if (filter === "schema") {
                this.overviewSchemaFilters = current;
            } else if (filter === "status") {
                this.overviewStatusFilters = current;
            } else {
                const match = /^group([1-3])$/.exec(filter);
                if (match) {
                    const filters = this.overviewGroupFilters.map(items => [...items]);
                    filters[Number(match[1]) - 1] = current;
                    this.overviewGroupFilters = filters;
                }
            }
        },
        clearOverviewFilter(filter) {
            if (filter === "schema") {
                this.overviewSchemaFilters = [];
            } else if (filter === "status") {
                this.overviewStatusFilters = [];
            } else {
                const match = /^group([1-3])$/.exec(filter);
                if (match) {
                    const filters = this.overviewGroupFilters.map(items => [...items]);
                    filters[Number(match[1]) - 1] = [];
                    this.overviewGroupFilters = filters;
                }
            }
        },
        clearOverviewFilters() {
            this.overviewQuery = "";
            this.overviewSchemaFilters = [];
            this.overviewGroupFilters = [[], [], []];
            this.overviewStatusFilters = [];
            this.overviewPage = 1;
            this.overviewFilterOpen = "";
        },
        setOverviewPageSize(size) {
            const normalized = Number(size);
            this.overviewPageSize = [25, 50, 100, 200].includes(normalized) ? normalized : 50;
        },
        goOverviewPage(page) {
            const normalized = Number.parseInt(page, 10);
            this.overviewPage = Math.min(
                Math.max(Number.isFinite(normalized) ? normalized : 1, 1),
                this.overviewPageCount
            );
        },
        overviewSchemaOrder(schema) {
            return { ALMP: 1, IDS: 2 }[schema] || 99;
        },
        isOutlineCollapsed(node) {
            return this.collapsedOutlineNodes[node.key] !== false;
        },
        toggleOutlineNode(node) {
            this.collapsedOutlineNodes[node.key] = !this.isOutlineCollapsed(node);
            this.savePreferences();
        },
        expandOverviewOutline() {
            const visit = nodes => {
                for (const node of nodes) {
                    if (node.type !== "table") {
                        this.collapsedOutlineNodes[node.key] = false;
                        visit(node.children || []);
                    }
                }
            };
            visit(this.overviewTree);
            this.savePreferences();
        },
        collapseOverviewOutline() {
            const visit = nodes => {
                for (const node of nodes) {
                    if (node.type !== "table") {
                        this.collapsedOutlineNodes[node.key] = true;
                        visit(node.children || []);
                    }
                }
            };
            visit(this.overviewTree);
            this.savePreferences();
        },
        normalizeObjectKey(value) {
            return String(value || "")
                .replaceAll('"', "")
                .replace(/\s+/g, "")
                .toUpperCase();
        },
        objectKey(objectType, object) {
            if (typeof object === "string") return this.normalizeObjectKey(object);
            return this.normalizeObjectKey(object?.qualifiedName || object?.name || object?.id);
        },
        annotationKey(objectType, object) {
            return `${String(objectType || "").toUpperCase()}:${this.objectKey(objectType, object)}`;
        },
        objectName(objectType, object) {
            if (typeof object === "string") return object;
            return object?.qualifiedName || object?.name || object?.id || "";
        },
        customGroupFor(objectType, object) {
            return this.customGroupAssignments[this.annotationKey(objectType, object)] || null;
        },
        customGroupId(objectType, object) {
            const assignment = this.customGroupFor(objectType, object);
            return assignment ? String(assignment.id) : "";
        },
        customGroupOptions(objectType) {
            return this.customGroups.filter(group => group.objectType === objectType);
        },
        customGroupPath(objectType, groupId) {
            const groups = new Map(
                this.customGroups
                    .filter(group => group.objectType === objectType)
                    .map(group => [Number(group.id), group])
            );
            const path = [];
            const visited = new Set();
            let current = groups.get(Number(groupId));
            while (current && !visited.has(Number(current.id))) {
                visited.add(Number(current.id));
                path.unshift(current.groupName);
                current = current.parentId == null ? null : groups.get(Number(current.parentId));
            }
            return path;
        },
        adjustmentFor(objectType, object) {
            const key = this.annotationKey(objectType, object);
            return this.adjustments.find(item => this.annotationKey(item.objectType, item.objectKey) === key) || null;
        },
        isAdjusted(objectType, object) {
            return Boolean(this.adjustmentFor(objectType, object));
        },
        objectGroupDescriptor(objectType, object) {
            const kind = objectType === "TABLE" ? "table" : "procedure";
            if (this.groupingMode === "custom") {
                const assignment = this.customGroupFor(objectType, object);
                const path = assignment ? this.customGroupPath(objectType, assignment.id) : [];
                return {
                    key: assignment
                        ? `${kind}:custom:${assignment.id}`
                        : `${kind}:custom:unassigned`,
                    schema: "自定义分组",
                    name: path.length ? path.join(" / ") : "未归组",
                    custom: Boolean(assignment)
                };
            }
            const schema = (object.schema || "").toUpperCase();
            const name = objectType === "TABLE" ? this.tableGroupName(object) : this.procedureGroupName(object);
            return {
                key: `${kind}:${schema}:${name}`,
                schema,
                name,
                custom: false
            };
        },
        isGroupCollapsed(group) {
            return this.collapsedGroups[group.key] !== false;
        },
        toggleGroup(group) {
            this.collapsedGroups[group.key] = !this.isGroupCollapsed(group);
            this.savePreferences();
        },
        tableGroupName(table) {
            const schema = (table.schema || "").toUpperCase();
            const name = (table.name || "").toUpperCase();
            const isTemporary = name.includes("MID") || name.includes("TMP") || name.includes("TEMP");
            if (isTemporary) return "临时表";

            if (schema === "ALMP") {
                if (name.startsWith("DM_I")) return "DM_I";
                if (name.startsWith("DM_A")) return "DM_A";
                if (name.startsWith("DM_S")) return "DM_S";
                if (name.startsWith("DM_O")) return "DM_O";
            }
            if (schema === "IDS" && name.startsWith("DM_S")) return "DM_S";
            return "其他";
        },
        procedureGroupName(procedure) {
            const schema = (procedure.schema || "").toUpperCase();
            const name = (procedure.name || "").toUpperCase();
            const isTemporary = name.includes("MID") || name.includes("TMP") || name.includes("TEMP");
            if (isTemporary) return "临时表";

            if (schema === "ALMP") {
                if (name.startsWith("PROC_DM_I")) return "PROC_DM_I";
                if (name.startsWith("PROC_DM_A")) return "PROC_DM_A";
                if (name.startsWith("PROC_DM_S")) return "PROC_DM_S";
                if (name.startsWith("PROC_DM_O")) return "PROC_DM_O";
            }
            if (schema === "IDS" && name.startsWith("PROC_DM_S")) return "PROC_DM_S";
            return "其他";
        },
        async openCustomGroupModal() {
            try {
                await this.loadAnnotationOverview(this.customThemeId);
                this.customGroupModal = true;
            } catch (error) {
                this.error = error.message;
            }
        },
        async createCustomTheme() {
            const name = this.customThemeDraft.trim();
            if (!name) {
                this.error = "请输入主题名称";
                return;
            }
            this.customThemeSaving = true;
            this.error = "";
            try {
                const theme = await this.getJson("/api/custom-group-themes", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ themeName: name })
                });
                this.customThemeDraft = "";
                this.customThemeId = Number(theme.id);
                await this.loadAnnotationOverview(this.customThemeId);
                this.showSuccess("分组主题已保存");
            } catch (error) {
                this.error = error.message;
            } finally {
                this.customThemeSaving = false;
            }
        },
        async deleteCustomTheme(theme) {
            if (!theme || Number(theme.id) === 1 || this.customThemeDeleting) return;
            if (!window.confirm(`确认删除分组主题“${theme.themeName}”吗？主题内的分组和对象归属会一起删除。`)) return;
            this.customThemeDeleting = true;
            try {
                await this.getJson(`/api/custom-group-themes/${theme.id}`, { method: "DELETE" });
                this.customThemeId = 1;
                await this.loadAnnotationOverview(1);
                this.showSuccess("分组主题已删除");
            } catch (error) {
                this.error = error.message;
            } finally {
                this.customThemeDeleting = false;
            }
        },
        async createCustomGroup() {
            const name = this.customGroupDraft.groupName.trim();
            if (!name) {
                this.error = "请输入分组名称";
                return;
            }
            this.customGroupSaving = true;
            this.error = "";
            try {
                await this.getJson("/api/custom-groups", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        objectType: this.customGroupDraft.objectType,
                        groupName: name,
                        parentId: this.customGroupDraft.parentId ? Number(this.customGroupDraft.parentId) : null,
                        themeId: this.customThemeId
                    })
                });
                await this.loadAnnotationOverview(this.customThemeId);
                this.customGroupDraft.groupName = "";
                this.customGroupDraft.parentId = null;
                this.showSuccess("自定义分组已保存");
            } catch (error) {
                this.error = error.message;
            } finally {
                this.customGroupSaving = false;
            }
        },
        async deleteCustomGroup(group) {
            if (!window.confirm(`确认删除自定义分组“${group.groupName}”吗？组内对象会恢复到系统分组。`)) return;
            try {
                await this.getJson(`/api/custom-groups/${group.id}?themeId=${this.customThemeId}`, { method: "DELETE" });
                await this.loadAnnotationOverview(this.customThemeId);
                this.showSuccess("自定义分组已删除");
            } catch (error) {
                this.error = error.message;
            }
        },
        async assignCustomGroup(objectType, object, groupId) {
            const key = this.objectKey(objectType, object);
            if (!key) return;
            this.groupAssignmentSaving = this.annotationKey(objectType, object);
            try {
                const overview = await this.getJson("/api/custom-groups/assignment", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        objectType,
                        objectKey: key,
                        groupId: groupId ? Number(groupId) : null,
                        themeId: this.customThemeId
                    })
                });
                this.applyAnnotationOverview(overview);
                this.showSuccess(groupId ? "对象已归入自定义分组" : "对象已恢复系统分组");
            } catch (error) {
                this.error = error.message;
            } finally {
                this.groupAssignmentSaving = "";
            }
        },
        openAdjustmentEditor(objectType, object) {
            const mark = this.adjustmentFor(objectType, object);
            this.adjustmentModal = {
                objectType,
                objectKey: this.objectKey(objectType, object),
                objectName: this.objectName(objectType, object)
            };
            this.adjustmentDraft = mark?.note || "";
        },
        closeAdjustmentEditor() {
            this.adjustmentModal = null;
            this.adjustmentDraft = "";
        },
        async saveAdjustment(marked = true) {
            if (!this.adjustmentModal) return;
            this.adjustmentSaving = true;
            this.error = "";
            try {
                const overview = await this.getJson("/api/adjustments", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        objectType: this.adjustmentModal.objectType,
                        objectKey: this.adjustmentModal.objectKey,
                        objectName: this.adjustmentModal.objectName,
                        note: this.adjustmentDraft,
                        marked,
                        themeId: this.customThemeId
                    })
                });
                this.applyAnnotationOverview(overview);
                this.closeAdjustmentEditor();
                this.showSuccess(marked ? "已标记为需要调整" : "已取消调整标记");
            } catch (error) {
                this.error = error.message;
            } finally {
                this.adjustmentSaving = false;
            }
        },
        openAdjustmentList() {
            this.adjustmentListModal = true;
        },
        async jumpAdjustment(mark) {
            this.adjustmentListModal = false;
            if (mark.objectType === "TABLE") {
                await this.jumpTable(mark.objectKey, { clearFilters: true });
            } else {
                await this.jumpProcedure(mark.objectKey, { clearFilters: true });
            }
        },
        isTemporaryTable(name) {
            const normalized = (name || "").toUpperCase();
            return normalized.includes("MID") || normalized.includes("TMP") || normalized.includes("TEMP");
        },
        columnLineage(columnName) {
            const key = String(columnName || "").toUpperCase();
            return this.selectedTable?.columnLineage?.[key] || [];
        },
        lineageNodeKey(node) {
            return `${node.table || ""}.${node.column || ""}`;
        },
        lineageStatusLabel(status) {
            return {
                ROOT: "当前字段",
                DIRECT: "直接来源",
                ORIGINAL: "未继续识别到上游",
                NO_SOURCE: "未识别直接来源",
                CYCLE: "检测到循环",
                DEPTH_LIMIT: "达到下钻深度上限"
            }[status] || status || "未知";
        },
        lineageStatusClass(status) {
            return String(status || "").toLowerCase().replace(/_/g, "-");
        },
        lineageDepthLabel(depth) {
            return depth === 0 ? "目标" : `上游 ${depth}`;
        },
        async openLineageDrilldown(column) {
            if (!this.selectedTable?.table || !column?.name) return;
            this.lineageModal = {
                table: this.selectedTable.table.qualifiedName,
                column: column.name,
                tree: null
            };
            this.lineageExpanded = {};
            this.lineageLoading = true;
            this.lineageError = "";
            try {
                const params = new URLSearchParams({
                    table: this.selectedTable.table.qualifiedName,
                    column: column.name,
                    maxDepth: "30"
                });
                const tree = await this.getJson(`/api/columns/lineage?${params.toString()}`);
                this.lineageModal.tree = tree;
                this.lineageExpanded = { root: true };
            } catch (error) {
                this.lineageError = error.message;
            } finally {
                this.lineageLoading = false;
            }
        },
        closeLineageDrilldown() {
            this.lineageModal = null;
            this.lineageExpanded = {};
            this.lineageError = "";
        },
        toggleLineageNode(row) {
            if (!row.node.children?.length) return;
            this.lineageExpanded[row.key] = this.lineageExpanded[row.key] !== true;
        },
        async jumpLineageSource(item) {
            if (!item?.sourceTable) return;
            await this.jumpTable(item.sourceTable, {
                clearFilters: true,
                focusColumn: item.sourceColumn
            });
        },
        scrollToProcedureLine(line) {
            const element = document.getElementById(`procedure-line-${line}`);
            if (!element) return;
            element.scrollIntoView({ behavior: "smooth", block: "center" });
        },
        async jumpTable(name, options = {}) {
            this.rememberTabState();
            this.activeTab = "tables";
            this.selectedProcedure = null;
            if (options.clearFilters) {
                this.query = "";
                this.schema = "";
                this.relation = "";
                this.tableType = "";
                this.tableStatus = "";
                this.showFavoritesOnly = false;
                await this.search({
                    history: "push",
                    objectId: name,
                    focusColumn: options.focusColumn
                });
                return;
            }
            this.query = name;
            await this.search({ history: "push" });
        },
        async jumpProcedure(name, options = {}) {
            this.rememberTabState();
            this.activeTab = "procedures";
            this.selectedTable = null;
            if (options.clearFilters) {
                this.query = "";
                this.schema = "";
                this.relation = "";
                this.tableType = "";
                this.tableStatus = "";
                this.showFavoritesOnly = false;
                await this.search({ history: "push", objectId: name, focusLine: options.line });
                return;
            }
            // 数据库模式下关键字匹配的是 procedure_name（不带库名），所以 "ads.p_x" 这种引用要
            // 只取最后一段再查，否则模糊查询匹配不到。
            this.query = this.databaseProcedureSearch() && name.includes(".")
                ? name.slice(name.lastIndexOf(".") + 1)
                : name;
            await this.search({ history: "push" });
        },
        async handlePopState() {
            const state = window.history.state;
            if (state?.metadataViewer) {
                this.navigationIndex = Number.isInteger(state.index) ? state.index : 0;
                this.navigationMaxIndex = Number.isInteger(state.maxIndex) ? state.maxIndex : this.navigationIndex;
            }
            const route = this.readRoute();
            this.applyRoute(route);
            await this.search({ history: "none", objectId: route.objectId, focusLine: route.focusLine });
        },
        navigateHistory(direction) {
            if (this.loading) return;
            if (direction < 0 && !this.canGoBack) return;
            if (direction > 0 && !this.canGoForward) return;
            window.history.go(direction);
        },
        readRoute() {
            const params = new URLSearchParams(window.location.search);
            let tab = params.get("tab");
            if (tab === "catalog") {
                // 兼容旧书签：数据库页签已经合并进“存储过程”页签。
                tab = "procedures";
            }
            return {
                activeTab: ["procedures", "overview"].includes(tab) ? tab : "tables",
                query: params.get("q") || "",
                schema: params.get("schema") || "",
                relation: params.get("relation") || "",
                tableType: params.get("tableType") || "",
                tableStatus: params.get("tableStatus") || "",
                objectId: params.get("id") || "",
                focusLine: Number(params.get("line") || 0)
            };
        },
        applyRoute(route) {
            this.activeTab = route.activeTab;
            this.query = route.query;
            this.schema = route.schema;
            this.relation = route.relation;
            this.tableType = route.tableType;
            this.tableStatus = route.tableStatus;
            this.procedureFocusLine = Number(route.focusLine || 0);
            this.selectedTable = null;
            this.selectedProcedure = null;
        },
        findListItem(items, objectId) {
            if (!objectId) return null;
            const normalize = value => String(value || "")
                .replace(/["\s]/g, "")
                .toUpperCase();
            const target = normalize(objectId);
            return items.find(item => normalize(item.id) === target || normalize(item.qualifiedName) === target) || null;
        },
        syncHistory(mode = "push") {
            if (mode === "none") return;

            const params = new URLSearchParams();
            if (this.activeTab !== "tables") params.set("tab", this.activeTab);
            if (this.query.trim()) params.set("q", this.query.trim());
            if (this.schema) params.set("schema", this.schema);
            if (this.activeTab === "tables" && this.relation) params.set("relation", this.relation);
            if (this.activeTab === "tables" && this.tableType) params.set("tableType", this.tableType);
            if (this.activeTab === "tables" && this.tableStatus) params.set("tableStatus", this.tableStatus);

            const selectedId = this.activeTab === "tables"
                ? this.selectedTable?.table?.id
                : this.activeTab === "procedures" ? this.selectedProcedure?.id : "";
            if (selectedId) params.set("id", selectedId);
            if (this.activeTab === "procedures" && this.procedureFocusLine > 0) {
                params.set("line", String(this.procedureFocusLine));
            }

            const query = params.toString();
            const url = `${window.location.pathname}${query ? `?${query}` : ""}${window.location.hash}`;
            const currentUrl = `${window.location.pathname}${window.location.search}${window.location.hash}`;
            if (url === currentUrl) return;

            const currentState = window.history.state && typeof window.history.state === "object"
                ? window.history.state
                : {};
            if (mode === "replace") {
                window.history.replaceState({
                    ...currentState,
                    metadataViewer: true,
                    index: this.navigationIndex,
                    maxIndex: this.navigationMaxIndex
                }, "", url);
            } else {
                const nextIndex = this.navigationIndex + 1;
                window.history.replaceState({
                    ...currentState,
                    metadataViewer: true,
                    index: this.navigationIndex,
                    maxIndex: nextIndex
                }, "", currentUrl);
                window.history.pushState({
                    metadataViewer: true,
                    index: nextIndex,
                    maxIndex: nextIndex
                }, "", url);
                this.navigationIndex = nextIndex;
                this.navigationMaxIndex = nextIndex;
            }
        },
        initializeHistoryState() {
            const state = window.history.state;
            if (state?.metadataViewer) {
                this.navigationIndex = Number.isInteger(state.index) ? state.index : 0;
                this.navigationMaxIndex = Number.isInteger(state.maxIndex) ? state.maxIndex : this.navigationIndex;
                return;
            }

            const nextState = state && typeof state === "object" ? { ...state } : {};
            window.history.replaceState({
                ...nextState,
                metadataViewer: true,
                index: 0,
                maxIndex: 0
            }, "", window.location.href);
            this.navigationIndex = 0;
            this.navigationMaxIndex = 0;
        },
        clearCaches() {
            this.tableListCache = {};
            this.procedureListCache = {};
            this.tableDetailCache = {};
            this.procedureDetailCache = {};
        },
        async getList(kind, cacheKey, force = false) {
            const cache = kind === "tables" ? this.tableListCache : this.procedureListCache;
            if (!force && Object.prototype.hasOwnProperty.call(cache, cacheKey)) {
                return cache[cacheKey];
            }

            const items = await this.getJson(`/api/${kind}?${cacheKey}`);
            cache[cacheKey] = items;
            return items;
        },
        /** True when the procedure list is a live query against the database catalogue. */
        databaseProcedureSearch() {
            return this.catalogStatus?.enabled === true;
        },
        tableSearchKey() {
            const params = new URLSearchParams();
            if (this.query.trim()) params.set("q", this.query.trim());
            if (this.schema) params.set("schema", this.schema);
            if (this.relation) params.set("relation", this.relation);
            if (this.tableType) params.set("tableType", this.tableType);
            if (this.tableStatus) params.set("tableStatus", this.tableStatus);
            return params.toString();
        },
        /**
         * Query string of the 存储过程 tab. With the database source enabled it is a fuzzy search
         * against system.procedures_v (keyword/database), so a name that is not in the locally indexed
         * page is still found; otherwise it keeps the file based q/schema search.
         */
        procedureSearchKey() {
            const params = new URLSearchParams();
            if (this.query.trim()) params.set(this.databaseProcedureSearch() ? "keyword" : "q", this.query.trim());
            if (this.schema) params.set(this.databaseProcedureSearch() ? "database" : "schema", this.schema);
            return params.toString();
        },
        async fetchProcedureList(cacheKey, force = false) {
            if (!force && Object.prototype.hasOwnProperty.call(this.procedureListCache, cacheKey)) {
                return this.procedureListCache[cacheKey];
            }
            const url = this.databaseProcedureSearch()
                ? `/api/catalog/procedure-list?${cacheKey}`
                : `/api/procedures?${cacheKey}`;
            const items = await this.getJson(url);
            this.procedureListCache[cacheKey] = items;
            return items;
        },
        /**
         * Reloads the list of the active tab without touching the current selection.
         *
         * <p>Needed because database procedures are parsed on demand: after a procedure is read, or
         * after a catalogue refresh / batch index, the cards in the left list (parameters, referenced
         * tables, inferred tables) change on the server.
         */
        async refreshVisibleList() {
            this.tableListCache = {};
            this.procedureListCache = {};
            try {
                if (this.activeTab === "tables") {
                    this.tables = await this.getList("tables", this.tableSearchKey(), true);
                } else if (this.activeTab === "procedures") {
                    this.procedures = await this.fetchProcedureList(this.procedureSearchKey(), true);
                }
            } catch (error) {
                // Keep whatever is on screen; the next search will retry.
            }
        },
        async getDetail(kind, id) {
            const cache = kind === "tables" ? this.tableDetailCache : this.procedureDetailCache;
            if (cache[id]) return cache[id];

            const detail = await this.getJson(`/api/${kind}/detail?id=${encodeURIComponent(id)}`);
            cache[id] = detail;
            return detail;
        },
        prepareTableEditors() {
            if (!this.selectedTable) return;
            this.tableCommentDraft = this.selectedTable.manualComment || "";
            this.profileDraft = { ...this.selectedTable.profile };
            this.profileEditing = false;
            this.annotationEditing = false;
            this.codeValueEditing = {};
            const persisted = this.selectedTable.codeValues || {};
            this.codeValueDrafts = Object.fromEntries(this.selectedTable.table.columns.map(column => [
                column.name,
                (persisted[column.name] || []).map(item => ({ value: item.value, label: item.label }))
            ]));
        },
        codeValueRows(columnName) {
            return this.codeValueDrafts[columnName] || [];
        },
        addCodeValue(columnName) {
            if (!this.codeValueDrafts[columnName]) this.codeValueDrafts[columnName] = [];
            this.codeValueDrafts[columnName].push({ value: "", label: "" });
        },
        removeCodeValue(columnName, index) {
            this.codeValueDrafts[columnName].splice(index, 1);
        },
        editTableAnnotation() {
            this.tableCommentDraft = this.selectedTable?.manualComment || "";
            this.annotationEditing = true;
        },
        editProfile() {
            this.profileDraft = { ...this.selectedTable.profile };
            this.profileEditing = true;
        },
        cancelProfile() {
            this.profileDraft = { ...this.selectedTable.profile };
            this.profileEditing = false;
        },
        async saveProfile() {
            if (!this.selectedTable) return;
            this.savingAnnotation = true;
            try {
                const table = this.selectedTable.table;
                const profile = await this.getJson("/api/tables/profile", {
                    method: "POST", headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ schema: table.schema, table: table.name, ...this.profileDraft })
                });
                this.selectedTable.profile = profile;
                this.profileDraft = { ...profile };
                this.profileEditing = false;
                this.tableListCache = {};
                this.showSuccess("基础信息已保存");
            } catch (error) { this.error = error.message; }
            finally { this.savingAnnotation = false; }
        },
        dictionary(columnName) {
            return this.selectedTable?.codeValues?.[columnName] || { sourceType: "MANUAL", codeSet: "", values: [] };
        },
        async loadGeneralCodeSets() {
            this.generalCodeSets = await this.getJson("/api/general-code-sets");
        },
        async openGeneralCodeModal() {
            await this.loadGeneralCodeSets();
            this.generalCodeModal = true;
            this.generalCodeDraft = this.generalCodeSets[0]
                ? JSON.parse(JSON.stringify(this.generalCodeSets[0]))
                : { codeSet: "", name: "", values: [] };
        },
        selectGeneralCodeSet(item) { this.generalCodeDraft = JSON.parse(JSON.stringify(item)); },
        newGeneralCodeSet() { this.generalCodeDraft = { codeSet: "", name: "", values: [] }; },
        addGeneralCodeValue() { this.generalCodeDraft.values.push({ value: "", label: "" }); },
        async saveGeneralCodeSet() {
            try {
                this.generalCodeSets = await this.getJson("/api/general-code-sets", {
                    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(this.generalCodeDraft)
                });
                this.generalCodeDraft = JSON.parse(JSON.stringify(this.generalCodeSets.find(item => item.codeSet === this.generalCodeDraft.codeSet) || this.generalCodeDraft));
                this.showSuccess("通用码值已保存");
            } catch (error) { this.error = error.message; }
        },
        async referenceGeneralCodeSet(columnName, codeSet) {
            if (!codeSet || !this.selectedTable) return;
            try {
                const table = this.selectedTable.table;
                const result = await this.getJson("/api/tables/code-values/reference", {
                    method: "POST", headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ schema: table.schema, table: table.name, column: columnName, codeSet })
                });
                this.selectedTable.codeValues[columnName] = result.dictionary;
                this.codeValueEditing[columnName] = false;
                this.showSuccess(`${columnName} 已引用通用码值`);
            } catch (error) { this.error = error.message; }
        },
        openDictionary(columnName) {
            const dictionary = this.dictionary(columnName);
            this.dictionaryModal = { columnName, ...dictionary };
        },
        cancelTableAnnotation() {
            this.tableCommentDraft = this.selectedTable?.manualComment || "";
            this.annotationEditing = false;
        },
        isCodeValuesEditing(columnName) {
            return this.codeValueEditing[columnName] === true;
        },
        async editCodeValues(columnName) {
            const persisted = this.dictionary(columnName).values;
            this.codeValueDrafts[columnName] = persisted.map(item => ({ value: item.value, label: item.label }));
            await this.loadGeneralCodeSets();
            this.codeValueEditing[columnName] = true;
        },
        cancelCodeValues(columnName) {
            const persisted = this.dictionary(columnName).values;
            this.codeValueDrafts[columnName] = persisted.map(item => ({ value: item.value, label: item.label }));
            this.codeValueEditing[columnName] = false;
        },
        showSuccess(message) {
            this.success = message;
            if (this.successTimer) window.clearTimeout(this.successTimer);
            this.successTimer = window.setTimeout(() => {
                this.success = "";
                this.successTimer = null;
            }, 2600);
        },
        async saveTableAnnotation() {
            if (!this.selectedTable) return;
            this.savingAnnotation = true;
            this.error = "";
            try {
                const table = this.selectedTable.table;
                const result = await this.getJson("/api/tables/annotation", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ schema: table.schema, table: table.name, comment: this.tableCommentDraft })
                });
                this.selectedTable.manualComment = result.comment;
                this.tableCommentDraft = result.comment;
                this.annotationEditing = false;
                this.tableListCache = {};
                const current = this.tables.find(item => item.id === table.id);
                if (current) current.manualComment = result.comment;
                const overviewTable = this.overviewTables.find(item => item.id === table.id);
                if (overviewTable) overviewTable.manualComment = result.comment;
                this.showSuccess("业务备注已保存");
            } catch (error) {
                this.error = error.message;
            } finally {
                this.savingAnnotation = false;
            }
        },
        async saveCodeValues(columnName) {
            if (!this.selectedTable) return;
            this.savingCodeColumn = columnName;
            this.error = "";
            try {
                const table = this.selectedTable.table;
                const result = await this.getJson("/api/tables/code-values", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        schema: table.schema,
                        table: table.name,
                        column: columnName,
                        values: this.codeValueRows(columnName)
                    })
                });
                this.selectedTable.codeValues[columnName] = result.dictionary;
                this.codeValueDrafts[columnName] = result.dictionary.values.map(item => ({ value: item.value, label: item.label }));
                this.codeValueEditing[columnName] = false;
                this.showSuccess(`${columnName} 的码值已保存`);
            } catch (error) {
                this.error = error.message;
            } finally {
                this.savingCodeColumn = "";
            }
        },
        exportColumns() {
            if (!this.selectedTable) return;
            const rows = [
                ["序号", "字段名", "类型", "类型参数", "可空", "默认值", "注释", "直接来源", "溯源过程", "来源行号", "行号", "原始定义"],
                ...this.selectedTable.table.columns.map(column => [
                    column.ordinal,
                    column.name,
                    column.typeName || column.dataType || "",
                    column.typeArguments || "",
                    column.nullable ? "YES" : "NO",
                    column.defaultValue || "",
                    column.comment || "",
                    this.columnLineage(column.name).map(item => `${item.sourceTable}.${item.sourceColumn}`).join("；"),
                    this.columnLineage(column.name).map(item => item.qualifiedProcedureName).join("；"),
                    this.columnLineage(column.name).map(item => item.line).join("；"),
                    column.line,
                    column.rawDefinition || ""
                ])
            ];
            this.downloadCsv(`${this.selectedTable.table.qualifiedName}_columns.csv`, rows);
        },
        async copySql(sql) {
            try {
                await navigator.clipboard.writeText(sql || "");
                this.showSuccess("SQL 已复制到剪贴板");
            } catch (error) {
                this.error = "当前浏览器不允许访问剪贴板";
            }
        },
        downloadCsv(filename, rows) {
            const csv = rows.map(row => row.map(this.csvCell).join(",")).join("\n");
            const blob = new Blob(["\ufeff" + csv], { type: "text/csv;charset=utf-8" });
            const link = document.createElement("a");
            link.href = URL.createObjectURL(blob);
            link.download = filename;
            link.click();
            URL.revokeObjectURL(link.href);
        },
        csvCell(value) {
            const text = String(value ?? "");
            return `"${text.replaceAll('"', '""')}"`;
        },
        async getJson(url, options = {}) {
            const { suppressUnauthorized = false, ...fetchOptions } = options;
            const response = await fetch(url, { credentials: "same-origin", ...fetchOptions });
            if (!response.ok) {
                const text = await response.text();
                let message = text || `HTTP ${response.status}`;
                try {
                    const payload = JSON.parse(text);
                    message = payload.message || payload.error || message;
                } catch (ignored) {
                }
                const error = new Error(message);
                error.status = response.status;
                if (response.status === 401 && !suppressUnauthorized) {
                    this.resetAuthenticatedState();
                }
                throw error;
            }
            if (response.status === 204) return null;
            return response.json();
        },
        formatNumber(value) {
            return new Intl.NumberFormat("zh-CN").format(value ?? 0);
        },
        formatBytes(value) {
            if (!value) return "0 B";
            const units = ["B", "KB", "MB", "GB"];
            let size = value;
            let unit = 0;
            while (size >= 1024 && unit < units.length - 1) {
                size /= 1024;
                unit++;
            }
            return `${size.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
        },
        formatDateTime(value) {
            if (!value) return "未加载";
            const date = new Date(value);
            if (Number.isNaN(date.getTime())) return String(value);
            return new Intl.DateTimeFormat("zh-CN", {
                month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
            }).format(date);
        },
        formatLoginDateTime(value) {
            if (!value) return "";
            const date = new Date(value);
            if (Number.isNaN(date.getTime())) return String(value);
            return new Intl.DateTimeFormat("zh-CN", {
                year: "numeric", month: "2-digit", day: "2-digit",
                hour: "2-digit", minute: "2-digit", second: "2-digit",
                hour12: false
            }).format(date);
        }
    },
    template: `
    <div v-if="authLoading" class="auth-loading">正在验证登录状态...</div>
    <section v-else-if="!currentUser" class="login-shell">
        <form class="login-panel" @submit.prevent="login">
            <div class="login-mark" aria-hidden="true">SQL</div>
            <div class="login-title">
                <h1>SQL 元数据查看器</h1>
                <p>表结构、存储过程与数据流血缘</p>
            </div>
            <label>账号<input v-model.trim="loginForm.username" autocomplete="username" required autofocus></label>
            <label>密码<input v-model="loginForm.password" type="password" autocomplete="current-password" required></label>
            <div v-if="loginError" class="login-error">{{ loginError }}</div>
            <button class="primary login-submit" type="submit" :disabled="loginSubmitting">
                {{ loginSubmitting ? "正在登录..." : "登录" }}
            </button>
        </form>
    </section>
    <main v-else class="app-shell" :class="{ 'detail-focus': detailFocus, 'overview-mode': activeTab === 'overview' }">
        <header class="topbar">
            <div class="brand">
                <div class="brand-mark" aria-hidden="true">SQL</div>
                <div>
                    <h1>SQL 元数据查看器</h1>
                    <p>表结构、存储过程与数据流血缘</p>
                    <div v-if="summary" class="brand-status">最近解析 {{ formatDateTime(summary.loadedAt) }} · {{ summary.sourceFiles.length }} 个 SQL 文件</div>
                </div>
            </div>
            <div class="topbar-tools">
                <div class="history-actions" aria-label="页面历史导航">
                    <button
                        class="history-button"
                        type="button"
                        title="返回上一步"
                        aria-label="返回上一步"
                        :disabled="!canGoBack"
                        @click="navigateHistory(-1)">&larr;</button>
                    <button
                        class="history-button"
                        type="button"
                        title="前进到下一步"
                        aria-label="前进到下一步"
                        :disabled="!canGoForward"
                        @click="navigateHistory(1)">&rarr;</button>
                </div>
                <button class="toolbar-icon" type="button" :title="detailFocus ? '显示对象目录' : '专注详情'" :aria-label="detailFocus ? '显示对象目录' : '专注详情'" @click="toggleSidebar">
                    <span aria-hidden="true">{{ detailFocus ? '☰' : '▣' }}</span>
                </button>
                <button class="toolbar-icon" type="button" :title="colorTheme === 'dark' ? '切换浅色主题' : '切换深色主题'" :aria-label="colorTheme === 'dark' ? '切换浅色主题' : '切换深色主题'" @click="toggleTheme">
                    <span aria-hidden="true">{{ colorTheme === 'dark' ? '☀' : '◐' }}</span>
                </button>
                <div class="actions">
                    <button @click="reload" :disabled="loading">{{ loading ? "处理中" : "刷新解析" }}</button>
                    <button @click="openGeneralCodeModal">通用码值</button>
                    <button @click="openCustomGroupModal">自定义分组</button>
                    <button class="overview-button" @click="openOverview">
                        {{ activeTab === "overview" ? "返回对象目录" : "表总览" }}
                    </button>
                    <button class="adjustment-toolbar-button" @click="openAdjustmentList">
                        调整清单<span v-if="adjustmentCount" class="toolbar-count">{{ adjustmentCount }}</span>
                    </button>
                    <button class="primary" @click="search" :disabled="loading">搜索</button>
                </div>
                <div class="account-tools">
                    <div class="account-identity">
                        <strong>{{ currentUser.displayName }}</strong>
                        <span>{{ currentUser.username }}</span>
                    </div>
                    <button v-if="isAdmin" type="button" @click="openLoginLogs">登录日志</button>
                    <button type="button" @click="logout">退出</button>
                </div>
            </div>
        </header>

        <section class="workspace">
            <aside class="sidebar">
                <div class="tabs">
                    <button class="tab" :class="{active: activeTab === 'tables'}" @click="switchTab('tables')">表</button>
                    <button class="tab" :class="{active: activeTab === 'procedures'}" @click="switchTab('procedures')">存储过程</button>
                </div>
                <div class="grouping-mode-switch" aria-label="分组模式">
                    <span>分组模式</span>
                    <button type="button" :class="{active: groupingMode === 'system'}" @click="setGroupingMode('system')">系统分组</button>
                    <button type="button" :class="{active: groupingMode === 'custom'}" @click="setGroupingMode('custom')">自定义分组</button>
                    <select v-if="groupingMode === 'custom'" class="grouping-theme-select" :value="customThemeId" title="选择自定义分组主题" @change="setCustomTheme($event.target.value)">
                        <option v-for="theme in customGroupThemes" :key="theme.id" :value="theme.id">{{ theme.themeName }}</option>
                    </select>
                </div>
                <div class="filters">
                    <div class="search-input wide">
                        <input ref="searchInput" v-model="query" @keyup.enter="search" :placeholder="activeTab === 'procedures' && databaseProcedureSearch() ? '输入存储过程名（支持模糊匹配，留空列出前若干条）' : '搜索名称、注释、字段、引用表'">
                        <button v-if="query" class="clear-input" type="button" title="清空搜索" aria-label="清空搜索" @click="query = ''; search()">×</button>
                    </div>
                    <select v-model="schema" @change="search">
                        <option value="">{{ activeTab === 'procedures' && databaseProcedureSearch() ? '全部 database' : '全部 schema' }}</option>
                        <option v-for="item in schemaOptions" :key="item" :value="item">{{ item }}</option>
                    </select>
                    <input v-if="activeTab === 'procedures' && databaseProcedureSearch()" v-model="catalogOwner" @keyup.enter="search" placeholder="owner（可选，如 hive）">
                    <label v-if="activeTab === 'procedures' && databaseProcedureSearch()" class="favorite-filter" :class="{active: catalogExact}">
                        <input type="checkbox" v-model="catalogExact" @change="search">
                        精确匹配
                    </label>
                    <select v-if="activeTab === 'tables'" v-model="relation" @change="search">
                        <option value="">全部关联状态</option>
                        <option value="orphan">仅孤表</option>
                        <option value="linked">已关联过程</option>
                    </select>
                    <select v-if="activeTab === 'tables'" v-model="tableType" @change="search"><option value="">全部表类型</option><option>基表</option><option>配置表</option><option>回流表</option><option>系统表</option><option>拉链表</option></select>
                    <select v-if="activeTab === 'tables'" v-model="tableStatus" @change="search"><option value="">全部表状态</option><option>有效</option><option>下线</option><option>待清理</option></select>
                    <div class="filter-actions wide">
                        <label class="favorite-filter" :class="{active: showFavoritesOnly}">
                            <input type="checkbox" v-model="showFavoritesOnly" @change="search">
                            <span aria-hidden="true">★</span>
                            仅看收藏
                        </label>
                        <button v-if="hasActiveFilters" class="text-button" type="button" @click="clearFilters">清除筛选</button>
                    </div>
                </div>
                <div class="list-toolbar">
                    <div>
                        <strong>{{ filterSummary }}</strong>
                        <span v-if="activeTab === 'procedures' && databaseProcedureSearch()" class="list-toolbar-note">数据库实时模糊查询，最多 {{ catalogStatus.maxRows }} 条</span>
                        <span v-if="showFavoritesOnly" class="list-toolbar-note">已收藏</span>
                    </div>
                    <div class="list-toolbar-actions">
                        <button type="button" title="展开当前所有分组" @click="expandAllGroups">展开</button>
                        <button type="button" title="收起当前所有分组" @click="collapseAllGroups">收起</button>
                    </div>
                </div>
                <div class="object-list">
                    <template v-if="activeTab === 'tables'">
                        <section v-for="group in tableGroups" :key="group.key" class="object-group">
                            <button class="object-group-title" :aria-expanded="!isGroupCollapsed(group)" @click="toggleGroup(group)">
                                <span class="object-group-marker" aria-hidden="true"></span>
                                <span>{{ group.schema }} · {{ group.name }}</span>
                                <span class="object-group-count">{{ group.items.length }}</span>
                            </button>
                            <template v-if="!isGroupCollapsed(group)">
                                <button
                                    v-for="table in group.items"
                                    :key="table.id"
                                    class="object-item"
                                    :class="{active: tableActive(table)}"
                                    @click="selectTable(table)">
                                    <div class="object-name">
                                        <span class="badge">{{ table.schema }}</span>
                                        <span>{{ table.name }}</span>
                                        <span v-if="isFavorite(table)" class="favorite-indicator" title="已收藏" aria-label="已收藏">★</span>
                                        <span v-if="isInferredTable(table)" class="status-badge" title="该表由数据库存储过程推断，暂无字段信息">库内推断</span>
                                        <span v-if="table.orphan" class="status-badge danger">孤表</span>
                                        <span v-if="isAdjusted('TABLE', table)" class="status-badge adjustment">调整</span>
                                    </div>
                                    <div class="object-comment">{{ table.manualComment || table.comment || "无表注释" }}</div>
                                    <div class="object-meta">
                                        {{ table.sourceFile }}:{{ table.startLine }} · 字段 {{ table.columnCount }} · 加工 {{ table.targetProcedureCount }} · 来源 {{ table.sourceProcedureCount }}
                                    </div>
                                </button>
                            </template>
                        </section>
                    </template>

                    <template v-if="activeTab === 'procedures'">
                        <div class="catalog-status" v-if="catalogStatus && catalogStatus.enabled">
                            <div>接入：<strong>已启用</strong> · 表 {{ catalogStatus.procedureTable }}</div>
                            <div>连接：{{ catalogStatus.endpoint }}</div>
                            <div>驱动：{{ catalogStatus.driverAvailable ? catalogStatus.driverDescription : "未加载（" + catalogStatus.driverDirectory + "）" }}</div>
                            <div>连接池：复用 {{ catalogStatus.pool.reusePercent }}%（复用 {{ catalogStatus.pool.reused }} / 借用 {{ catalogStatus.pool.borrowed }}，创建 {{ catalogStatus.pool.created }}）</div>
                            <div>源码缓存：命中 {{ catalogStatus.cache.hitPercent }}%（命中 {{ catalogStatus.cache.hits }} / 未命中 {{ catalogStatus.cache.misses }}，条目 {{ catalogStatus.cache.entries }}）</div>
                            <div>过程目录：{{ catalogStatus.index.catalogueSize }} 条，已解析 {{ catalogStatus.index.parsedSize }}，待解析 {{ catalogStatus.index.pendingSize }}</div>
                            <div v-if="catalogStatus.index.catalogueLoadedAt">目录刷新时间 {{ formatDateTime(catalogStatus.index.catalogueLoadedAt) }}</div>
                            <div v-else class="muted">过程目录尚未刷新，启动后会自动刷新一次</div>
                            <div v-if="catalogStatus.index.lastError" class="catalog-error">{{ catalogStatus.index.lastError }}</div>
                            <div class="catalog-actions">
                                <button type="button" @click="loadCatalogStatus">刷新统计</button>
                                <button type="button" :disabled="loading || catalogRefreshing" @click="refreshCatalogIndex">
                                    {{ catalogRefreshing ? "刷新中…" : "刷新过程目录" }}
                                </button>
                                <button type="button" :disabled="loading" @click="analyzeCatalogBatch">解析并索引匹配过程</button>
                                <button type="button" :disabled="catalogClearing" @click="clearCatalogCache">
                                    {{ catalogClearing ? "清空中…" : "清空源码缓存" }}
                                </button>
                            </div>
                        </div>
                        <div class="catalog-status muted" v-else-if="catalogStatus">
                            <div>数据库接入未启用（metadata.inceptor.enabled=false）：当前只显示本地 SQL 文件解析结果。</div>
                        </div>
                        <div v-if="catalogError" class="catalog-error">{{ catalogError }}</div>
                        <section v-for="group in procedureGroups" :key="group.key" class="object-group">
                            <button class="object-group-title" :aria-expanded="!isGroupCollapsed(group)" @click="toggleGroup(group)">
                                <span class="object-group-marker" aria-hidden="true"></span>
                                <span>{{ group.schema }} · {{ group.name }}</span>
                                <span class="object-group-count">{{ group.items.length }}</span>
                            </button>
                            <template v-if="!isGroupCollapsed(group)">
                                <button
                                    v-for="procedure in group.items"
                                    :key="procedure.id"
                                    class="object-item"
                                    :class="{active: procedureActive(procedure)}"
                                    @click="selectProcedure(procedure)">
                                    <div class="object-name">
                                        <span class="badge">{{ procedure.schema }}</span>
                                        <span>{{ procedure.name }}</span>
                                        <span v-if="isFavorite(procedure)" class="favorite-indicator" title="已收藏" aria-label="已收藏">★</span>
                                        <span v-if="isUnparsedProcedure(procedure)" class="status-badge">未解析</span>
                                        <span v-if="isAdjusted('PROCEDURE', procedure)" class="status-badge adjustment">调整</span>
                                    </div>
                                    <div class="object-comment">{{ procedure.title || procedure.description || "无过程说明" }}</div>
                                    <div class="object-meta">
                                        <template v-if="isUnparsedProcedure(procedure)">来自数据库 · 点开后读取源码并解析</template>
                                        <template v-else>{{ procedure.sourceFile }}:{{ procedure.startLine }} · 参数 {{ procedure.parameterCount }} · 引用表 {{ procedure.referencedTableCount }} · 调用 {{ procedure.calledProcedureCount }}</template>
                                    </div>
                                </button>
                            </template>
                        </section>
                    </template>

                    <div v-if="visibleItems.length === 0" class="empty">没有匹配结果</div>
                </div>
            </aside>

            <article class="detail">
                <div v-if="activeTab === 'overview'" class="overview-page" @click="closeOverviewFilterMenus">
                    <div class="overview-page-header">
                        <div>
                            <h2>表总览大纲</h2>
                            <p>{{ groupingMode === "system" ? "使用系统规则按 Schema 和对象类型分组。" : "使用“" + customThemeName + "”主题的多级分组。" }} 表名后展示表注释。</p>
                        </div>
                        <div class="overview-page-actions">
                            <div class="grouping-mode-switch overview-mode-switch" aria-label="分组模式">
                                <span>分组模式</span>
                                <button type="button" :class="{active: groupingMode === 'system'}" @click="setGroupingMode('system')">系统分组</button>
                                <button type="button" :class="{active: groupingMode === 'custom'}" @click="setGroupingMode('custom')">自定义分组</button>
                                <select v-if="groupingMode === 'custom'" class="grouping-theme-select" :value="customThemeId" title="选择自定义分组主题" @change="setCustomTheme($event.target.value)">
                                    <option v-for="theme in customGroupThemes" :key="theme.id" :value="theme.id">{{ theme.themeName }}</option>
                                </select>
                            </div>
                            <div class="search-input overview-search">
                                <input v-model="overviewQuery" placeholder="筛选表名、注释、Schema">
                                <button v-if="overviewQuery" class="clear-input" type="button" title="清空总览筛选" aria-label="清空总览筛选" @click="overviewQuery = ''">×</button>
                            </div>
                            <button type="button" @click="openCustomGroupModal">管理分组</button>
                        </div>
                    </div>
                    <div class="overview-filter-bar">
                        <div class="overview-filter-dropdown" :class="{open: overviewFilterOpen === 'schema'}" @click.stop>
                            <button type="button" class="overview-filter-trigger" @click="toggleOverviewFilter('schema')">
                                <span>Schema</span><strong>{{ overviewFilterLabel('schema') }}</strong><span class="overview-filter-chevron">⌄</span>
                            </button>
                            <div v-if="overviewFilterOpen === 'schema'" class="overview-filter-menu">
                                <button v-for="item in overviewSchemaOptions" :key="item" type="button" class="overview-filter-option" :class="{selected: isOverviewFilterSelected('schema', item)}" @click="toggleOverviewFilterValue('schema', item)">
                                    <span class="overview-option-check">{{ isOverviewFilterSelected('schema', item) ? "✓" : "" }}</span>{{ item }}
                                </button>
                                <div v-if="overviewSchemaOptions.length === 0" class="overview-filter-empty">暂无选项</div>
                                <button v-if="overviewSchemaFilters.length" type="button" class="overview-filter-clear" @click="clearOverviewFilter('schema')">清空已选</button>
                            </div>
                        </div>
                        <div v-for="(options, level) in overviewGroupLevelOptions" :key="'overview-group-filter-' + level" class="overview-filter-dropdown" :class="{open: overviewFilterOpen === 'group' + (level + 1)}" @click.stop>
                            <button type="button" class="overview-filter-trigger" @click="toggleOverviewFilter('group' + (level + 1))">
                                <span>分组 {{ level + 1 }}</span><strong>{{ overviewFilterLabel('group' + (level + 1)) }}</strong><span class="overview-filter-chevron">⌄</span>
                            </button>
                            <div v-if="overviewFilterOpen === 'group' + (level + 1)" class="overview-filter-menu">
                                <button v-for="item in options" :key="item" type="button" class="overview-filter-option" :class="{selected: isOverviewFilterSelected('group' + (level + 1), item)}" @click="toggleOverviewFilterValue('group' + (level + 1), item)">
                                    <span class="overview-option-check">{{ isOverviewFilterSelected('group' + (level + 1), item) ? "✓" : "" }}</span>{{ item }}
                                </button>
                                <div v-if="options.length === 0" class="overview-filter-empty">暂无选项</div>
                                <button v-if="overviewFilterValues('group' + (level + 1)).length" type="button" class="overview-filter-clear" @click="clearOverviewFilter('group' + (level + 1))">清空已选</button>
                            </div>
                        </div>
                        <div class="overview-filter-dropdown" :class="{open: overviewFilterOpen === 'status'}" @click.stop>
                            <button type="button" class="overview-filter-trigger" @click="toggleOverviewFilter('status')">
                                <span>状态</span><strong>{{ overviewFilterLabel('status') }}</strong><span class="overview-filter-chevron">⌄</span>
                            </button>
                            <div v-if="overviewFilterOpen === 'status'" class="overview-filter-menu">
                                <button v-for="item in overviewStatusOptions" :key="item.value" type="button" class="overview-filter-option" :class="{selected: isOverviewFilterSelected('status', item.value)}" @click="toggleOverviewFilterValue('status', item.value)">
                                    <span class="overview-option-check">{{ isOverviewFilterSelected('status', item.value) ? "✓" : "" }}</span>{{ item.label }}
                                </button>
                                <button v-if="overviewStatusFilters.length" type="button" class="overview-filter-clear" @click="clearOverviewFilter('status')">清空已选</button>
                            </div>
                        </div>
                        <button v-if="overviewHasFilters" type="button" class="text-button" @click="clearOverviewFilters">清除总览筛选</button>
                    </div>
                    <div class="overview-summary-row">
                        <div class="overview-summary">
                            显示 {{ formatNumber(overviewFilteredTableRows.length ? overviewPageStart + 1 : 0) }}-{{ formatNumber(overviewPageEnd) }}
                            / {{ formatNumber(overviewFilteredTableRows.length) }} 张表，共 {{ formatNumber(overviewTables.length) }} 张
                        </div>
                        <div class="overview-pagination">
                            <label class="overview-page-size">每页
                                <select :value="overviewPageSize" @change="setOverviewPageSize($event.target.value)">
                                    <option value="25">25</option>
                                    <option value="50">50</option>
                                    <option value="100">100</option>
                                    <option value="200">200</option>
                                </select>
                            </label>
                            <button type="button" title="第一页" :disabled="overviewPage <= 1" @click="goOverviewPage(1)">首页</button>
                            <button type="button" title="上一页" :disabled="overviewPage <= 1" @click="goOverviewPage(overviewPage - 1)">上一页</button>
                            <button
                                v-for="page in overviewPaginationPages"
                                :key="page"
                                type="button"
                                class="overview-page-number"
                                :class="{active: page === overviewPage}"
                                @click="goOverviewPage(page)"
                            >{{ page }}</button>
                            <button type="button" title="下一页" :disabled="overviewPage >= overviewPageCount" @click="goOverviewPage(overviewPage + 1)">下一页</button>
                            <button type="button" title="最后一页" :disabled="overviewPage >= overviewPageCount" @click="goOverviewPage(overviewPageCount)">末页</button>
                            <label class="overview-page-jump">跳至
                                <input type="number" min="1" :max="overviewPageCount" :value="overviewPage" @change="goOverviewPage($event.target.value)">
                            </label>
                            <span class="overview-page-total">共 {{ overviewPageCount }} 页</span>
                        </div>
                    </div>
                    <div class="overview-content">
                        <aside class="overview-tree-panel">
                            <div class="overview-tree-header">
                                <strong>分组目录</strong>
                                <div class="overview-tree-actions">
                                    <button type="button" title="展开全部分组" @click="setOutlineTreeCollapsed(false)">展开</button>
                                    <button type="button" title="收起全部分组" @click="setOutlineTreeCollapsed(true)">收起</button>
                                </div>
                            </div>
                            <div class="overview-tree-list">
                                <div v-if="outlineTreeRows.length === 0" class="overview-filter-empty">暂无目录</div>
                                <div v-for="row in outlineTreeRows" :key="row.key" class="outline-tree-row">
                                    <button
                                        v-if="row.node.type !== 'table'"
                                        type="button"
                                        class="outline-tree-node"
                                        :class="{root: row.level === 1, deep: row.level > 2}"
                                        :style="{'--tree-depth': row.level}"
                                        :aria-expanded="row.hasChildren ? !isOutlineTreeCollapsed(row.node) : undefined"
                                        @click="row.hasChildren && toggleOutlineTreeNode(row.node)"
                                    >
                                        <span v-if="row.hasChildren" class="object-group-marker" :class="{expanded: !isOutlineTreeCollapsed(row.node)}" aria-hidden="true"></span>
                                        <span v-else class="tree-node-spacer" aria-hidden="true"></span>
                                        <span class="outline-tree-name">{{ row.node.name }}</span>
                                        <span class="object-group-count">{{ row.node.count }}</span>
                                    </button>
                                    <button
                                        v-else
                                        type="button"
                                        class="outline-tree-table"
                                        :class="{active: tableActive(row.node.table)}"
                                        :style="{'--tree-depth': row.level}"
                                        @click="jumpTable(row.node.table.qualifiedName, {clearFilters: true})"
                                    >
                                        <span class="outline-tree-table-marker">•</span>
                                        <span class="outline-tree-name mono">{{ row.node.table.name }}</span>
                                    </button>
                                </div>
                            </div>
                        </aside>
                        <section class="overview-results">
                            <div v-if="overviewFilteredTableRows.length === 0" class="empty">没有匹配的表</div>
                            <div v-else class="overview-table-wrap">
                                <table class="overview-table">
                            <thead>
                            <tr>
                                <th>#</th>
                                <th>Schema</th>
                                <th v-for="groupColumn in overviewGroupColumns" :key="'overview-group-' + groupColumn.index">{{ groupColumn.label }}</th>
                                <th>表名</th>
                                <th>表注释</th>
                                <th>字段数</th>
                                <th>数据来源过程</th>
                                <th>引用本表的存储过程</th>
                                <th>状态</th>
                                <th>快捷操作</th>
                            </tr>
                            </thead>
                            <tbody>
                            <tr v-for="(row, index) in overviewPageRows" :key="row.key">
                                <td class="overview-index">{{ overviewPageStart + index + 1 }}</td>
                                <td><span class="badge">{{ row.schema }}</span></td>
                                <template v-for="groupColumn in overviewGroupColumns" :key="row.key + '-' + groupColumn.index">
                                    <td class="overview-group-cell">{{ row.groupPath[groupColumn.index] || "—" }}</td>
                                </template>
                                <td>
                                    <button class="overview-table-link mono" type="button" @click="jumpTable(row.table.qualifiedName, {clearFilters: true})">
                                        {{ row.table.name }}
                                    </button>
                                </td>
                                <td class="overview-comment">{{ row.table.comment || "无表注释" }}</td>
                                <td>{{ formatNumber(row.table.columnCount) }}</td>
                                <td>{{ formatNumber(row.table.targetProcedureCount) }}</td>
                                <td>{{ formatNumber(row.table.sourceProcedureCount) }}</td>
                                <td>
                                    <span v-if="row.table.orphan" class="status-badge danger">孤表</span>
                                    <span v-if="isTemporaryTable(row.table.name)" class="status-badge temporary">临时表</span>
                                    <span v-if="isAdjusted('TABLE', row.table)" class="status-badge adjustment">调整</span>
                                    <span v-if="!row.table.orphan && !isTemporaryTable(row.table.name) && !isAdjusted('TABLE', row.table)" class="muted">正常</span>
                                </td>
                                <td class="overview-quick-actions">
                                    <select
                                        class="overview-group-select"
                                        :value="customGroupId('TABLE', row.table)"
                                        :disabled="groupAssignmentSaving === annotationKey('TABLE', row.table)"
                                        title="快速归入自定义分组"
                                        @change="assignCustomGroup('TABLE', row.table, $event.target.value)"
                                    >
                                        <option value="">未归入自定义分组</option>
                                        <option v-for="group in customGroupOptions('TABLE')" :key="group.id" :value="group.id">
                                            {{ customGroupPath(group.objectType, group.id).join(" / ") }}
                                        </option>
                                    </select>
                                    <button
                                        type="button"
                                        class="overview-adjust-button"
                                        :class="{marked: isAdjusted('TABLE', row.table)}"
                                        @click="openAdjustmentEditor('TABLE', row.table)"
                                    >
                                        {{ isAdjusted('TABLE', row.table) ? "已标记" : "标记调整" }}
                                    </button>
                                </td>
                            </tr>
                                </tbody>
                                </table>
                            </div>
                        </section>
                    </div>
                </div>
                <template v-else>
                <div class="detail-header">
                    <div v-if="error" class="error">{{ error }}</div>
                    <transition name="toast">
                        <div v-if="success" class="success-message" role="status">{{ success }}</div>
                    </transition>
                    <div class="detail-title">
                        <div>
                            <h2>{{ detailTitle }}</h2>
                            <div class="detail-subtitle" v-if="selectedTable">
                                {{ selectedTable.table.sourceFile }}:{{ selectedTable.table.startLine }}-{{ selectedTable.table.endLine }}
                            </div>
                            <div class="detail-subtitle" v-if="selectedProcedure">
                                {{ selectedProcedure.sourceFile }}:{{ selectedProcedure.startLine }}-{{ selectedProcedure.endLine }}
                            </div>
                        </div>
                        <div class="detail-actions">
                            <button v-if="selectedTable" class="toolbar-icon favorite-button" :class="{saved: isFavorite(selectedTable.table)}" type="button" :title="isFavorite(selectedTable.table) ? '取消收藏' : '收藏当前表'" :aria-label="isFavorite(selectedTable.table) ? '取消收藏' : '收藏当前表'" @click="toggleFavorite(selectedTable.table)">
                                <span aria-hidden="true">{{ isFavorite(selectedTable.table) ? '★' : '☆' }}</span>
                            </button>
                            <button v-if="selectedProcedure" class="toolbar-icon favorite-button" :class="{saved: isFavorite(selectedProcedure)}" type="button" :title="isFavorite(selectedProcedure) ? '取消收藏' : '收藏当前过程'" :aria-label="isFavorite(selectedProcedure) ? '取消收藏' : '收藏当前过程'" @click="toggleFavorite(selectedProcedure)">
                                <span aria-hidden="true">{{ isFavorite(selectedProcedure) ? '★' : '☆' }}</span>
                            </button>
                            <button v-if="selectedTable" class="adjustment-action" type="button" :class="{marked: isAdjusted('TABLE', selectedTable.table)}" @click="openAdjustmentEditor('TABLE', selectedTable.table)">
                                {{ isAdjusted('TABLE', selectedTable.table) ? '调整中' : '标记调整' }}
                            </button>
                            <button v-if="selectedProcedure" class="adjustment-action" type="button" :class="{marked: isAdjusted('PROCEDURE', selectedProcedure)}" @click="openAdjustmentEditor('PROCEDURE', selectedProcedure)">
                                {{ isAdjusted('PROCEDURE', selectedProcedure) ? '调整中' : '标记调整' }}
                            </button>
                            <button v-if="selectedTable" @click="exportColumns">导出字段 CSV</button>
                        </div>
                    </div>
                    <div class="tag-row" v-if="selectedTable">
                        <span class="tag">字段 {{ selectedTable.table.columnCount }}</span>
                        <span class="tag">字段注释 {{ selectedTable.table.commentedColumnCount }}</span>
                        <span class="tag" v-if="selectedTable.table.engine">引擎 {{ selectedTable.table.engine }}</span>
                        <span class="tag warn" v-if="selectedTable.table.partitionedBy">分区 {{ selectedTable.table.partitionedBy }}</span>
                        <span class="tag danger" v-if="!selectedTable.table.comment">缺少表注释</span>
                    </div>
                    <div class="tag-row" v-if="selectedProcedure">
                        <span class="tag">参数 {{ selectedProcedure.parameterCount }}</span>
                        <span class="tag">目标表 {{ selectedProcedure.targetTables.length }}</span>
                        <span class="tag">来源表 {{ selectedProcedure.sourceTables.length }}</span>
                        <span class="tag">调用过程 {{ selectedProcedure.calledProcedures.length }}</span>
                    </div>
                </div>

                <div class="detail-body">
                    <div v-if="!selectedTable && !selectedProcedure" class="empty">从左侧选择表或存储过程查看详情</div>
                    <div v-if="detailLoading && !selectedProcedure" class="empty">正在读取过程源码并解析（首次约 4~5 秒，之后走缓存）…</div>

                    <section class="section" v-if="selectedProcedure && catalogProfile">
                        <div class="section-title"><h3>数据库来源</h3></div>
                        <div class="kv">
                            <div class="key">对象</div><div class="value mono">{{ catalogProfile.qualifiedName }}</div>
                            <div class="key">拥有者</div><div class="value">{{ catalogProfile.ownerName }} / {{ catalogProfile.ownerType }}</div>
                            <div class="key">创建时间</div><div class="value">{{ catalogProfile.createTime || "未知" }}</div>
                            <div class="key">参数</div><div class="value mono">{{ catalogProfile.parameters || "-" }}</div>
                            <div class="key">源码长度</div><div class="value">{{ catalogProfile.sourceLength }} 字符</div>
                            <div class="key">读取方式</div>
                            <div class="value">
                                {{ catalogProfile.fromCache ? "源码缓存命中（本次未再读 full_text）" : "本次从数据库读取 full_text" }}
                                · 耗时 {{ catalogProfile.elapsedMillis }} ms
                            </div>
                            <div class="key" v-if="catalogProfile.headerAdded">解析提示</div>
                            <div class="value" v-if="catalogProfile.headerAdded">库中只存过程正文／参数行，分析时已自动补齐 CREATE PROCEDURE 头</div>
                        </div>
                    </section>

                    <section class="section" v-if="activeTab === 'procedures' && catalogBatch && !selectedProcedure">
                        <div class="section-title"><h3>批量分析结果</h3></div>
                        <div class="kv">
                            <div class="key">匹配 / 分析</div>
                            <div class="value">{{ catalogBatch.matched }} / {{ catalogBatch.analyzed }}{{ catalogBatch.truncated ? "（超过单次上限，已截断）" : "" }}</div>
                            <div class="key">读取方式</div>
                            <div class="value">{{ catalogBatch.fromCache ? "源码全部命中缓存" : "本次从数据库读取" }} · 耗时 {{ catalogBatch.elapsedMillis }} ms</div>
                            <div class="key" v-if="catalogBatch.skipped.length">跳过</div>
                            <div class="value" v-if="catalogBatch.skipped.length">{{ catalogBatch.skipped.join("；") }}</div>
                        </div>
                        <div class="section-title"><h3>目标表（去重 {{ catalogBatch.targetTables.length }}）</h3></div>
                        <div class="chips">
                            <button class="chip-button" v-for="item in catalogBatch.targetTables" :key="item" @click="jumpTable(item)">{{ item }}</button>
                            <span v-if="catalogBatch.targetTables.length === 0" class="muted">无</span>
                        </div>
                        <div class="section-title"><h3>来源表（去重 {{ catalogBatch.sourceTables.length }}）</h3></div>
                        <div class="chips">
                            <button class="chip-button" v-for="item in catalogBatch.sourceTables" :key="item" @click="jumpTable(item, {clearFilters: true})">{{ item }}</button>
                            <span v-if="catalogBatch.sourceTables.length === 0" class="muted">无</span>
                        </div>
                    </section>

                    <template v-if="selectedTable">
                        <div class="detail-tabs"><button :class="{active: tableDetailTab === 'basic'}" @click="tableDetailTab = 'basic'">基本信息</button><button :class="{active: tableDetailTab === 'relations'}" @click="tableDetailTab = 'relations'">来源与引用</button><button :class="{active: tableDetailTab === 'columns'}" @click="tableDetailTab = 'columns'">字段信息</button><button :class="{active: tableDetailTab === 'sql'}" @click="tableDetailTab = 'sql'">原始 SQL</button></div>
                        <section class="section" v-if="tableDetailTab === 'basic'">
                            <div class="section-title"><h3>基本信息</h3><button v-if="!profileEditing" @click="editProfile">编辑</button></div>
                            <div v-if="profileEditing" class="profile-form"><label>中文名称<input v-model="profileDraft.chineseName"></label><label>日期字段<input v-model="profileDraft.dateColumn"></label><label>增全量标记<input v-model="profileDraft.loadMode"></label><label>表类型<select v-model="profileDraft.tableType"><option value="">未设置</option><option>基表</option><option>配置表</option><option>回流表</option><option>系统表</option><option>拉链表</option></select></label><label>表状态<select v-model="profileDraft.tableStatus"><option>有效</option><option>下线</option><option>待清理</option></select></label><label class="profile-wide">业务备注<textarea v-model="profileDraft.comment" rows="3"></textarea></label><div class="profile-actions"><button @click="cancelProfile">取消</button><button class="primary" @click="saveProfile">保存</button></div></div>
                            <div class="kv">
                                <div class="key">表名</div><div class="value mono">{{ selectedTable.table.qualifiedName }}</div>
                                <div class="key">自定义分组</div>
                                <div class="value inline-control">
                                    <select :value="customGroupId('TABLE', selectedTable.table)" :disabled="groupAssignmentSaving === annotationKey('TABLE', selectedTable.table)" @change="assignCustomGroup('TABLE', selectedTable.table, $event.target.value)">
                                        <option value="">系统分组</option>
                                        <option v-for="group in customGroupOptions('TABLE')" :key="group.id" :value="String(group.id)">{{ group.groupName }}</option>
                                    </select>
                                </div>
                                <div class="key">中文名称</div><div class="value">{{ selectedTable.profile.chineseName || selectedTable.table.comment || "无" }}</div>
                                <div class="key">分区字段</div><div class="value">{{ selectedTable.table.partitionedBy || "无" }}</div>
                                <div class="key">日期字段</div><div class="value">{{ selectedTable.profile.dateColumn || "无" }}</div>
                                <div class="key">增全量标记</div><div class="value">{{ selectedTable.profile.loadMode || "无" }}</div>
                                <div class="key">业务备注</div><div class="value">{{ selectedTable.profile.comment || "无" }}</div>
                                <div class="key">表类型</div><div class="value">{{ selectedTable.profile.tableType || "未设置" }}</div>
                                <div class="key">表状态</div><div class="value">{{ selectedTable.profile.tableStatus || "有效" }}</div>
                                <div class="key">数据来源过程</div><div class="value">{{ selectedTable.targetProcedures.length }}</div>
                                <div class="key">引用本表的存储过程</div><div class="value">{{ selectedTable.sourceProcedures.length }}</div>
                                <div class="key">数据流状态</div><div class="value"><span v-if="selectedTable.orphan" class="status-badge danger">孤表</span><span v-else class="status-badge success">已关联过程</span></div>
                                <div class="key">调整标记</div><div class="value"><span v-if="isAdjusted('TABLE', selectedTable.table)" class="status-badge adjustment">需要调整</span><span v-else class="muted">未标记</span></div>
                            </div>
                        </section>

                        <section class="section annotation-section" v-if="false">
                            <div class="section-title">
                                <h3>业务备注</h3>
                                <button v-if="!annotationEditing" @click="editTableAnnotation">编辑</button>
                            </div>
                            <div v-if="!annotationEditing" class="annotation-display">
                                {{ selectedTable.manualComment || "暂未添加业务备注" }}
                            </div>
                            <div v-else class="annotation-editor">
                                <textarea v-model="tableCommentDraft" rows="3" placeholder="为该表添加业务备注"></textarea>
                                <div class="annotation-actions">
                                    <button @click="cancelTableAnnotation" :disabled="savingAnnotation">取消</button>
                                    <button class="primary" @click="saveTableAnnotation" :disabled="savingAnnotation">
                                        {{ savingAnnotation ? "保存中" : "保存备注" }}
                                    </button>
                                </div>
                            </div>
                        </section>

                        <section class="section process-relation-section source-process-section" v-if="tableDetailTab === 'relations' && selectedTable.targetProcedures.length">
                            <div class="section-title"><h3>数据来源过程</h3></div>
                            <div class="relation-list">
                                <div class="relation-item" v-for="item in selectedTable.targetProcedures" :key="item.id">
                                    <button class="chip-button relation-procedure" @click="jumpProcedure(item.qualifiedName, {clearFilters: true})">
                                        {{ item.qualifiedName }}
                                    </button>
                                    <div class="relation-label">来源表</div>
                                    <div class="chips" v-if="item.sourceTables.length">
                                        <button class="chip-button" :class="{temporary: isTemporaryTable(table)}" v-for="table in item.sourceTables" :key="table" @click="jumpTable(table, {clearFilters: true})">
                                            {{ table }}
                                        </button>
                                    </div>
                                    <div class="relation-empty" v-else>未识别到来源表</div>
                                </div>
                            </div>
                        </section>

                        <section class="section process-relation-section referenced-process-section" v-if="tableDetailTab === 'relations' && selectedTable.sourceProcedures.length">
                            <div class="section-title"><h3>引用本表的存储过程</h3></div>
                            <div class="relation-list">
                                <div class="relation-item" v-for="item in selectedTable.sourceProcedures" :key="item.id">
                                    <button class="chip-button relation-procedure" @click="jumpProcedure(item.qualifiedName, {clearFilters: true})">
                                        {{ item.qualifiedName }}
                                    </button>
                                    <div class="relation-label">目标表</div>
                                    <div class="chips" v-if="item.targetTables.length">
                                        <button class="chip-button" :class="{temporary: isTemporaryTable(table)}" v-for="table in item.targetTables" :key="table" @click="jumpTable(table, {clearFilters: true})">
                                            {{ table }}
                                        </button>
                                    </div>
                                    <div class="relation-empty" v-else>未识别到目标表</div>
                                </div>
                            </div>
                        </section>

                        <section class="section" v-if="tableDetailTab === 'columns'">
                            <div class="section-title">
                                <h3>字段 <span class="section-count">{{ visibleColumns.length }}/{{ selectedTable.table.columns.length }}</span></h3>
                                <div class="section-actions">
                                    <div class="column-search">
                                        <input v-model="columnQuery" placeholder="筛选字段">
                                        <button v-if="columnQuery" class="clear-input" type="button" title="清空字段筛选" aria-label="清空字段筛选" @click="columnQuery = ''">×</button>
                                    </div>
                                    <button @click="exportColumns">导出 CSV</button>
                                </div>
                            </div>
                            <div class="table-wrap">
                                <table>
                                    <thead>
                                    <tr>
                                        <th>#</th>
                                        <th>字段名</th>
                                        <th>类型</th>
                                        <th>参数</th>
                                        <th>可空</th>
                                        <th>默认值</th>
                                        <th>注释</th>
                                        <th>直接来源</th>
                                        <th>码值</th>
                                        <th>行号</th>
                                    </tr>
                                    </thead>
                                    <tbody>
                                    <tr v-for="column in visibleColumns" :key="column.ordinal">
                                        <td>{{ column.ordinal }}</td>
                                        <td class="mono">{{ column.name }}</td>
                                        <td class="mono">{{ column.typeName || column.dataType }}</td>
                                        <td class="mono">{{ column.typeArguments || "" }}</td>
                                        <td>{{ column.nullable ? "YES" : "NO" }}</td>
                                        <td class="mono">{{ column.defaultValue || "" }}</td>
                                        <td>{{ column.comment || "" }}</td>
                                        <td class="lineage-cell">
                                            <button class="drilldown-button" @click="openLineageDrilldown(column)">下钻</button>
                                            <template v-if="columnLineage(column.name).length">
                                                <div class="lineage-row" v-for="item in columnLineage(column.name)" :key="item.qualifiedProcedureName + '-' + item.line + '-' + item.sourceTable + '-' + item.sourceColumn">
                                                    <button class="lineage-source" :class="{temporary: isTemporaryTable(item.sourceTable)}" @click="jumpLineageSource(item)">
                                                        {{ item.sourceTable }}.{{ item.sourceColumn }}
                                                    </button>
                                                    <button class="trace-button" @click="jumpProcedure(item.qualifiedProcedureName, {clearFilters: true, line: item.line})">溯源</button>
                                                </div>
                                            </template>
                                            <span v-else class="muted">—</span>
                                        </td>
                                        <td class="code-values-cell">
                                            <template v-if="!isCodeValuesEditing(column.name)">
                                                <button v-if="dictionary(column.name).values.length" class="inline-edit-button" @click="openDictionary(column.name)">查看 {{ dictionary(column.name).values.length }} 项码值</button>
                                                <span v-else class="muted">未设置</span>
                                                <span v-if="dictionary(column.name).sourceType === 'GENERAL'" class="muted">引用：{{ dictionary(column.name).codeSet }}</span>
                                                <button class="inline-edit-button" @click="editCodeValues(column.name)">编辑</button>
                                            </template>
                                            <template v-else>
                                                <div class="code-value-row" v-for="(item, index) in codeValueRows(column.name)" :key="index">
                                                    <input v-model="item.value" placeholder="码值">
                                                    <input v-model="item.label" placeholder="码值说明">
                                                    <button class="icon-button" title="删除码值" @click="removeCodeValue(column.name, index)">×</button>
                                                </div>
                                                <div class="code-value-actions">
                                                    <button @click="addCodeValue(column.name)">添加</button>
                                                    <button @click="cancelCodeValues(column.name)" :disabled="savingCodeColumn === column.name">取消</button>
                                                    <button class="primary" @click="saveCodeValues(column.name)" :disabled="savingCodeColumn === column.name">
                                                        {{ savingCodeColumn === column.name ? "保存中" : "保存" }}
                                                    </button>
                                                </div>
                                                <div class="general-reference" v-if="generalCodeSets.length"><select @change="referenceGeneralCodeSet(column.name, $event.target.value)"><option value="">一键引用通用码值</option><option v-for="set in generalCodeSets" :value="set.codeSet">{{ set.codeSet }} - {{ set.name }}</option></select></div>
                                            </template>
                                        </td>
                                        <td>{{ column.line }}</td>
                                    </tr>
                                    <tr v-if="visibleColumns.length === 0">
                                        <td colspan="10" class="empty-table-cell">没有匹配字段</td>
                                    </tr>
                                    </tbody>
                                </table>
                            </div>
                        </section>

                        <section class="section" v-if="tableDetailTab === 'sql'">
                            <div class="section-title">
                                <h3>原始建表 SQL</h3>
                                <button @click="copySql(selectedTable.table.rawSql)">复制</button>
                            </div>
                            <pre>{{ selectedTable.table.rawSql }}</pre>
                        </section>
                    </template>

                    <template v-if="selectedProcedure">
                        <section class="section">
                            <div class="kv">
                                <div class="key">自定义分组</div>
                                <div class="value inline-control">
                                    <select :value="customGroupId('PROCEDURE', selectedProcedure)" :disabled="groupAssignmentSaving === annotationKey('PROCEDURE', selectedProcedure)" @change="assignCustomGroup('PROCEDURE', selectedProcedure, $event.target.value)">
                                        <option value="">系统分组</option>
                                        <option v-for="group in customGroupOptions('PROCEDURE')" :key="group.id" :value="String(group.id)">{{ group.groupName }}</option>
                                    </select>
                                </div>
                                <div class="key">调整标记</div>
                                <div class="value"><span v-if="isAdjusted('PROCEDURE', selectedProcedure)" class="status-badge adjustment">需要调整</span><span v-else class="muted">未标记</span></div>
                                <template v-for="(value, key) in selectedProcedure.documentation" :key="key">
                                    <div class="key">{{ key }}</div><div class="value">{{ value || "无" }}</div>
                                </template>
                                <div class="key" v-if="Object.keys(selectedProcedure.documentation).length === 0">过程说明</div>
                                <div class="value" v-if="Object.keys(selectedProcedure.documentation).length === 0">无头部注释</div>
                            </div>
                        </section>

                        <section class="section" v-if="selectedProcedure.parameters.length">
                            <div class="section-title"><h3>参数</h3></div>
                            <div class="table-wrap">
                                <table>
                                    <thead>
                                    <tr>
                                        <th>#</th>
                                        <th>参数名</th>
                                        <th>方向</th>
                                        <th>类型</th>
                                        <th>注释</th>
                                        <th>原始定义</th>
                                    </tr>
                                    </thead>
                                    <tbody>
                                    <tr v-for="param in selectedProcedure.parameters" :key="param.ordinal">
                                        <td>{{ param.ordinal }}</td>
                                        <td class="mono">{{ param.name }}</td>
                                        <td>{{ param.mode }}</td>
                                        <td class="mono">{{ param.dataType }}</td>
                                        <td>{{ param.comment || "" }}</td>
                                        <td class="mono">{{ param.rawDefinition }}</td>
                                    </tr>
                                    </tbody>
                                </table>
                            </div>
                        </section>

                        <section class="section" v-if="selectedProcedure.targetTables.length">
                            <div class="section-title"><h3>目标表</h3></div>
                            <div class="chips">
                                <button class="chip-button" :class="{temporary: isTemporaryTable(item)}" v-for="item in selectedProcedure.targetTables" :key="item" @click="jumpTable(item)">
                                    {{ item }}
                                </button>
                            </div>
                        </section>

                        <section class="section" v-if="selectedProcedure.sourceTables.length">
                            <div class="section-title"><h3>来源表</h3></div>
                            <div class="chips">
                                <button class="chip-button" :class="{temporary: isTemporaryTable(item)}" v-for="item in selectedProcedure.sourceTables" :key="item" @click="jumpTable(item, {clearFilters: true})">
                                    {{ item }}
                                </button>
                            </div>
                        </section>

                        <section class="section" v-if="selectedProcedure.calledProcedures.length">
                            <div class="section-title"><h3>调用过程</h3></div>
                            <div class="chips">
                                <button class="chip-button" v-for="item in selectedProcedure.calledProcedures" :key="item" @click="jumpProcedure(item)">
                                    {{ item }}
                                </button>
                            </div>
                        </section>

                        <section class="section">
                            <div class="section-title">
                                <h3>原始过程 SQL</h3>
                                <button @click="copySql(selectedProcedure.rawSql)">复制</button>
                            </div>
                            <pre class="source-code"><code><span v-for="line in selectedProcedureLines" :key="line.number" :id="'procedure-line-' + line.number" class="source-code-line" :class="{highlight: procedureFocusLine === line.number}"><span class="source-line-number">{{ line.number }}</span><span class="source-line-text">{{ line.text || " " }}</span></span></code></pre>
                        </section>
                    </template>
                </div>
                </template>
            </article>
        </section>
        <div v-if="generalCodeModal" class="modal-backdrop" @click.self="generalCodeModal = false">
            <section class="modal">
                <div class="section-title"><h3>通用码值维护</h3><button @click="generalCodeModal = false">关闭</button></div>
                <div class="general-code-layout">
                    <div class="general-code-list"><button @click="newGeneralCodeSet">新建码表</button><button v-for="item in generalCodeSets" :key="item.codeSet" @click="selectGeneralCodeSet(item)">{{ item.codeSet }} · {{ item.name }}</button></div>
                    <div v-if="generalCodeDraft" class="general-code-editor"><label>码表标识<input v-model="generalCodeDraft.codeSet"></label><label>码表名称<input v-model="generalCodeDraft.name"></label><div class="code-value-row" v-for="(item, index) in generalCodeDraft.values" :key="index"><input v-model="item.value" placeholder="码值"><input v-model="item.label" placeholder="码值说明"><button class="icon-button" @click="generalCodeDraft.values.splice(index, 1)">×</button></div><div class="code-value-actions"><button @click="addGeneralCodeValue">添加码值</button><button class="primary" @click="saveGeneralCodeSet">保存码表</button></div></div>
                </div>
            </section>
        </div>
        <div v-if="customGroupModal" class="modal-backdrop" @click.self="customGroupModal = false">
            <section class="modal custom-group-modal">
                <div class="section-title">
                    <div>
                        <h3>自定义分组</h3>
                        <div class="modal-subtitle">每个分组主题独立维护多级分组和对象归属；创建后，在表或存储过程详情中选择归属。</div>
                    </div>
                    <button type="button" @click="customGroupModal = false">关闭</button>
                </div>
                <div class="custom-theme-toolbar">
                    <label>分组主题
                        <select :value="customThemeId" @change="setCustomTheme($event.target.value)">
                            <option v-for="theme in customGroupThemes" :key="theme.id" :value="theme.id">{{ theme.themeName }}</option>
                        </select>
                    </label>
                    <input v-model.trim="customThemeDraft" placeholder="例如：按业务主题分组" maxlength="120">
                    <button type="button" @click="createCustomTheme" :disabled="customThemeSaving">{{ customThemeSaving ? "保存中" : "新增主题" }}</button>
                    <button v-if="customThemeId !== 1 && customGroupThemes.length > 1" type="button" class="text-button danger-text" @click="deleteCustomTheme(currentCustomTheme)" :disabled="customThemeDeleting">
                        {{ customThemeDeleting ? "删除中" : "删除当前主题" }}
                    </button>
                </div>
                <form class="custom-group-form" @submit.prevent="createCustomGroup">
                    <label>对象类型
                        <select v-model="customGroupDraft.objectType">
                            <option value="TABLE">表</option>
                            <option value="PROCEDURE">存储过程</option>
                        </select>
                    </label>
                    <label>分组名称
                        <input v-model.trim="customGroupDraft.groupName" placeholder="例如：待核对对象" maxlength="120">
                    </label>
                    <label>上级分组
                        <select v-model="customGroupDraft.parentId">
                            <option :value="null">顶级分组</option>
                            <option v-for="group in customGroupOptions(customGroupDraft.objectType)" :key="group.id" :value="String(group.id)">
                                {{ customGroupPath(group.objectType, group.id).join(" / ") }}
                            </option>
                        </select>
                    </label>
                    <button class="primary" type="submit" :disabled="customGroupSaving">{{ customGroupSaving ? "保存中" : "新增分组" }}</button>
                </form>
                <div class="custom-group-list">
                    <div class="custom-group-list-title">当前主题：{{ customThemeName }}</div>
                    <div v-if="customGroups.length === 0" class="empty">当前主题还没有自定义分组</div>
                    <div v-for="group in customGroups" :key="group.id" class="custom-group-item">
                        <div>
                            <strong>{{ customGroupPath(group.objectType, group.id).join(" / ") }}</strong>
                            <div class="muted">{{ group.objectType === "TABLE" ? "表" : "存储过程" }} · {{ group.memberCount }} 个对象</div>
                        </div>
                        <button type="button" class="text-button danger-text" @click="deleteCustomGroup(group)">删除</button>
                    </div>
                </div>
            </section>
        </div>
        <div v-if="adjustmentModal" class="modal-backdrop" @click.self="closeAdjustmentEditor">
            <section class="modal adjustment-modal">
                <div class="section-title">
                    <div>
                        <h3>标记需要调整</h3>
                        <div class="modal-subtitle mono">{{ adjustmentModal.objectName }}</div>
                    </div>
                    <button type="button" @click="closeAdjustmentEditor">关闭</button>
                </div>
                <label class="adjustment-editor-label">调整说明
                    <textarea v-model="adjustmentDraft" rows="5" maxlength="1000" placeholder="记录需要调整的字段、逻辑或待确认事项"></textarea>
                </label>
                <div class="modal-actions">
                    <button v-if="isAdjusted(adjustmentModal.objectType, adjustmentModal.objectKey)" type="button" class="danger-button" @click="saveAdjustment(false)" :disabled="adjustmentSaving">取消标记</button>
                    <span></span>
                    <button type="button" @click="closeAdjustmentEditor">取消</button>
                    <button class="primary" type="button" @click="saveAdjustment(true)" :disabled="adjustmentSaving">{{ adjustmentSaving ? "保存中" : "保存标记" }}</button>
                </div>
            </section>
        </div>
        <div v-if="adjustmentListModal" class="modal-backdrop" @click.self="adjustmentListModal = false">
            <section class="modal adjustment-list-modal">
                <div class="section-title">
                    <div>
                        <h3>调整清单</h3>
                        <div class="modal-subtitle">统一查看所有需要调整的表和存储过程</div>
                    </div>
                    <button type="button" @click="adjustmentListModal = false">关闭</button>
                </div>
                <div v-if="adjustments.length === 0" class="empty">暂无调整标记</div>
                <div v-else class="adjustment-list">
                    <button v-for="mark in adjustments" :key="annotationKey(mark.objectType, mark.objectKey)" class="adjustment-item" type="button" @click="jumpAdjustment(mark)">
                        <div class="adjustment-item-title">
                            <span class="status-badge adjustment">{{ mark.objectType === "TABLE" ? "表" : "存储过程" }}</span>
                            <strong class="mono">{{ mark.objectName || mark.objectKey }}</strong>
                        </div>
                        <div class="adjustment-item-note">{{ mark.note || "未填写调整说明" }}</div>
                        <div class="adjustment-item-meta">{{ mark.markedBy || "未知用户" }} · {{ formatLoginDateTime(mark.markedAt) }}</div>
                    </button>
                </div>
            </section>
        </div>
        <div v-if="dictionaryModal" class="modal-backdrop" @click.self="dictionaryModal = null">
            <section class="modal dictionary-modal"><div class="section-title"><h3>{{ dictionaryModal.columnName }} 数据字典</h3><button @click="dictionaryModal = null">关闭</button></div><div class="muted" v-if="dictionaryModal.sourceType === 'GENERAL'">引用通用码表：{{ dictionaryModal.codeSet }}</div><div class="table-wrap"><table><thead><tr><th>码值</th><th>说明</th></tr></thead><tbody><tr v-for="item in dictionaryModal.values" :key="item.value"><td class="mono">{{ item.value }}</td><td>{{ item.label }}</td></tr></tbody></table></div></section>
        </div>
        <div v-if="lineageModal" class="modal-backdrop" @click.self="closeLineageDrilldown">
            <section class="modal lineage-modal">
                <div class="section-title">
                    <div>
                        <h3>字段血缘下钻</h3>
                        <div class="modal-subtitle mono">{{ lineageModal.table }}.{{ lineageModal.column }}</div>
                    </div>
                    <button type="button" @click="closeLineageDrilldown">关闭</button>
                </div>
                <div v-if="lineageLoading" class="lineage-loading">正在分析字段上游关系...</div>
                <div v-else-if="lineageError" class="error">{{ lineageError }}</div>
                <div v-else-if="lineageModal.tree" class="lineage-tree">
                    <div class="lineage-tree-toolbar">
                        <span class="muted">逐层展开来源字段，直到没有可继续识别的上游关系</span>
                        <button type="button" class="text-button" @click="lineageExpanded = {root: true}">收起下游层级</button>
                    </div>
                    <div v-for="row in lineageTreeRows" :key="row.key" class="lineage-tree-row" :class="'depth-' + Math.min(row.depth, 8) + ' status-' + lineageStatusClass(row.node.status)">
                        <button v-if="row.node.children && row.node.children.length" class="lineage-toggle" type="button" :aria-label="lineageExpanded[row.key] === true ? '收起来源' : '展开来源'" @click="toggleLineageNode(row)">
                            {{ lineageExpanded[row.key] === true ? "−" : "+" }}
                        </button>
                        <span v-else class="lineage-toggle-placeholder"></span>
                        <div class="lineage-tree-main">
                            <div class="lineage-tree-title">
                                <span class="lineage-depth">{{ lineageDepthLabel(row.depth) }}</span>
                                <span class="mono">{{ row.node.table }}.{{ row.node.column }}</span>
                                <span class="lineage-status" :class="'status-' + lineageStatusClass(row.node.status)">{{ lineageStatusLabel(row.node.status) }}</span>
                            </div>
                            <div class="lineage-tree-meta" v-if="row.node.qualifiedProcedureName">
                                加工过程：<span class="mono">{{ row.node.qualifiedProcedureName }}</span>
                                <span v-if="row.node.sourceFile"> · {{ row.node.sourceFile }}:{{ row.node.line }}</span>
                            </div>
                        </div>
                    </div>
                </div>
            </section>
        </div>
        <div v-if="loginLogsModal" class="modal-backdrop" @click.self="loginLogsModal = false">
            <section class="modal login-log-modal">
                <div class="section-title">
                    <div><h3>登录日志</h3><div class="modal-subtitle">最近 200 条登录尝试</div></div>
                    <button type="button" @click="loginLogsModal = false">关闭</button>
                </div>
                <div v-if="loginLogsLoading" class="lineage-loading">正在读取登录日志...</div>
                <div v-else class="table-wrap">
                    <table>
                        <thead><tr><th>时间</th><th>账号</th><th>结果</th><th>地址</th><th>客户端</th></tr></thead>
                        <tbody>
                        <tr v-for="item in loginLogs" :key="item.id">
                            <td class="mono">{{ formatLoginDateTime(item.loggedInAt) }}</td>
                            <td><strong>{{ item.displayName || item.username }}</strong><div class="muted">{{ item.username }}</div></td>
                            <td><span class="login-status" :class="{success: item.success, failed: !item.success}">{{ item.success ? "成功" : "失败" }}</span><div v-if="item.failureReason" class="muted">{{ item.failureReason }}</div></td>
                            <td class="mono">{{ item.ipAddress || "-" }}</td>
                            <td class="login-agent">{{ item.userAgent || "-" }}</td>
                        </tr>
                        <tr v-if="loginLogs.length === 0"><td colspan="5" class="empty-table-cell">暂无登录记录</td></tr>
                        </tbody>
                    </table>
                </div>
            </section>
        </div>
    </main>
    `
}).mount("#app");

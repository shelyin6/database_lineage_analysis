# SQL 元数据查看器

面向本地 SQL 文件的 Spring Boot + Vue 元数据查看器。

本应用仅解析 SQL 文件，不连接数据库，也不读取表数据。
Vue 3 运行时已打包在 `src/main/resources/static/vendor` 下，因此浏览器界面运行时不依赖公共 CDN。

手工维护的表备注和字段码值存储在本地 `metadata.sqlite` 中。数据库会在首次启动时自动创建；如需使用其他位置，请设置 `metadata.annotation-database`。

## GitHub 开源说明

本项目采用 MIT 许可证发布，详见 [LICENSE](LICENSE)。

项目文档统一放在 `docs/` 目录，包括 [项目简介](docs/数据溯源项目简介-zh.md)、[参与贡献](docs/CONTRIBUTING-zh.md)、[行为准则](docs/CODE_OF_CONDUCT-zh.md) 和 [安全策略](docs/SECURITY-zh.md)。发布公开仓库前请确认没有真实业务 SQL 文件、SQLite 运行时数据库、生成的 jar 包、内部分析报告、凭据、Cookie 或环境相关配置文件。

打包的 Vue 运行时在 `src/main/resources/static/vendor/vue.LICENSE.txt` 中保留了自己的许可证声明。

## 内置登录账户

当前应用在
`src/main/java/com/xcloud/metadata/security/AuthenticationService.java`
中配置了固定的本地演示账户。
应用没有注册、账户创建或账户管理 API。登录尝试会写入本地
`metadata.sqlite` 数据库。只有 `admin` 可以在界面中查看近期登录日志。
在将应用暴露到受信任的本地或内网环境之外之前，请检查并替换此身份认证模型。

## 运行

```bash
mvn spring-boot:run
```

打开：

```text
http://localhost:8080
```

## 打包用于内网部署

Spring Boot Maven 插件会创建一个包含应用及其全部依赖的可执行 fat jar：

```bash
mvn clean package -DskipTests
```

输出文件为：

```text
target/sql-metadata-viewer-0.0.1-SNAPSHOT.jar
```

将 jar 包和所有 SQL 文件放在同一目录下，然后运行：

```bash
java -jar sql-metadata-viewer-0.0.1-SNAPSHOT.jar
```

默认情况下，应用会扫描 jar 包旁边的所有 `.sql` 文件，并从每个文件中解析表和存储过程。`metadata.sqlite` 也会写入 jar 包所在目录。

当需要配置端口、SQL 目录、数据库路径或明确的文件列表时，将 `application-example.yml` 复制为 jar 包旁边的 `application.yml`。`config/application.yml` 下的文件也会被自动加载。

`metadata.sql-directory` 和 `metadata.annotation-database` 的相对路径会解析到 jar 包所在目录；`metadata.table-files`、`metadata.procedure-files` 中的相对条目会按 `sql-directory` 解析，绝对路径原样使用。

例如：

```bash
cp application-example.yml application.yml
java -jar sql-metadata-viewer-0.0.1-SNAPSHOT.jar
```

## 展示内容

- 按模式/源文件查看表列表
- 表注释、引擎、分区字段、源文件和行号范围
- 每个字段的名称、类型、类型参数、可空标记、默认值、注释和原始定义
- 按模式/源文件查看存储过程列表
- 存储过程参数、头部文档、目标表/来源表/被引用表、调用的存储过程和原始 SQL
- 从表反向查找引用该表的存储过程
- 按孤立/已关联的数据流状态筛选表
- 编辑并持久化表业务备注和字段码值
- 分组且可折叠的表/存储过程导航
- 多个相互独立的自定义分组主题，支持多级分组，例如业务主题和业务线主题
- 收藏、字段筛选、深色主题和详情聚焦模式
- “数据库”页签：只读接入 Inceptor 元数据表，按存储过程名（模糊/精确）检索并直接给出目标表、来源表、参数、头部注释与原始 SQL

## 从元数据库读取存储过程（可选，默认关闭）

除本地 SQL 文件外，还可以只读接入 Inceptor 的存储过程元数据表 `system.procedures_v`：在
“数据库”页签里输入存储过程名（支持模糊查询），选中后即可看到与本地文件模式一致的解析结果。

1. 把 Inceptor 驱动 jar（例如 `inceptor-sdk-4.7.0.jar`）放到 jar 同级目录的 `lib/` 下。
   驱动由隔离类加载器加载，因此**不需要** `-Dloader.path`，也不会和应用的 Logback/SLF4J 冲突，
   并且不打进发布包。
2. 把 `application-example.yml` 复制为 jar 同级目录的 `application.yml`，打开
   `metadata.inceptor.enabled` 并填好 `url`、`username`、`password`（口令建议写成
   `${环境变量}` 占位符）。
3. 在 jar 所在目录启动应用，然后依次访问：

- `GET /api/catalog/status`：免登录自检，显示 enabled、驱动来源、连接池与源码缓存统计
- `GET /api/catalog/status/verify`：连通性自检（只发一条 SELECT）
- `GET /api/catalog/procedures?keyword=p_loan&limit=20`：模糊查询过程名（不读 `full_text`）
- `GET /api/catalog/procedures/{database}/{name}/profile`：读取源码并解析（第二次命中缓存）
- `POST /api/catalog/analyze?keyword=p_loan`：批量分析匹配过程，返回去重后的目标表/来源表
- `POST /api/catalog/cache/clear`：清空源码缓存

针对远程元数据库的取数优化（内网实测依据）：

- **连接复用**：建立会话约 0.1~0.6 秒，连接池复用后列表查询约 0.3 秒；
- **源码缓存**：`full_text` 单次读取约 4~5 秒（底表是 dblink 虚拟视图），缓存命中后接近 0，
  以 `create_time` 作为版本标记，并用 TTL 兜住“原过程被原地修改”的情况；
- **请求超时**：`request-timeout-seconds` 到点立即放弃等待并返回 504，页面不会假死，
  同时用信号量限制并发，避免超时请求在数据库侧堆积；
- **列表查询**：默认不取 `full_text`、不在库端排序，避免约 0.75 秒/行的 LOB 读取代价。

## API

- `POST /api/auth/login`
- `GET /api/auth/me`
- `POST /api/auth/logout`
- `GET /api/auth/login-logs`
- `GET /api/summary`
- `POST /api/reload`
- `GET /api/schemas`
- `GET /api/tables?q=&schema=&relation=&tableType=&tableStatus=`
- `GET /api/tables/detail?id=...`
- `GET /api/columns/lineage?table=...&column=...&maxDepth=...`
- `GET /api/procedures?q=&schema=`
- `GET /api/procedures/detail?id=...`
- `GET /api/catalog/status`（免登录）
- `GET /api/catalog/status/verify`
- `GET /api/catalog/procedures?keyword=&database=&owner=&exact=&limit=`
- `GET /api/catalog/procedures/{database}/{name}/profile`
- `POST /api/catalog/analyze?keyword=&database=&owner=&exact=&limit=`
- `POST /api/catalog/cache/clear`
- `GET /api/annotations/overview?themeId=...`
- `POST /api/custom-group-themes`
- `DELETE /api/custom-group-themes/{id}`
- `POST /api/custom-groups`
- `DELETE /api/custom-groups/{id}?themeId=...`
- `POST /api/custom-groups/assignment`

默认情况下，后端从项目根目录读取 SQL。可以通过以下命令覆盖：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--metadata.sql-directory=/path/to/sql"
```

## 许可证

本项目基于 [MIT 许可证](LICENSE) 发布。

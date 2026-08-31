# SQL Metadata Viewer

Spring Boot + Vue metadata viewer for local SQL files.

The app parses SQL files only. It does not connect to a database and does not read table data.
Vue 3 runtime is bundled under `src/main/resources/static/vendor`, so the browser UI does not depend on a public CDN.

Manual table remarks and field code values are stored locally in `metadata.sqlite`. The database is created automatically on first start; set `metadata.annotation-database` to use a different location.

## GitHub Open Source Notes

This project is licensed under the MIT License. See [LICENSE](LICENSE).

Project documentation lives under `docs/`. See [GitHub Open Source Notes](docs/数据溯源项目简介.md) and the Chinese project introduction at [数据溯源项目简介-zh.md](docs/数据溯源项目简介-zh.md). Do not publish real business SQL files, SQLite runtime databases, generated jars, internal analysis reports, credentials, cookies, or environment-specific configuration files.

The bundled Vue runtime keeps its own license notice under `src/main/resources/static/vendor/vue.LICENSE.txt`.

## Built-in Login Accounts

The application currently has fixed local demo accounts in
`src/main/java/com/xcloud/metadata/security/AuthenticationService.java`.
It has no registration, account creation, or account management API. Login
attempts are written to the local `metadata.sqlite` database. Only `admin` can
view recent login logs in the UI. Review and replace this authentication model
before exposing the application outside a trusted local or intranet
environment.

## Run

```bash
mvn spring-boot:run
```

Open:

```text
http://localhost:8080
```

## Package For Intranet

The Spring Boot Maven plugin creates an executable fat jar containing the application and all dependencies:

```bash
mvn clean package -DskipTests
```

The output is:

```text
target/sql-metadata-viewer-0.0.1-SNAPSHOT.jar
```

Put the jar and all SQL files in the same directory, then run:

```bash
java -jar sql-metadata-viewer-0.0.1-SNAPSHOT.jar
```

By default, the application scans every `.sql` file directly beside the jar. It parses tables and procedures from each file. `metadata.sqlite` is also written beside the jar.

Copy `application-example.yml` to `application.yml` beside the jar when a port, SQL directory, database path, or explicit file list needs to be configured. A file under `config/application.yml` is also loaded automatically.

Relative paths in `metadata.sql-directory` and `metadata.annotation-database` are resolved beside the executable jar. Entries in `metadata.table-files` and `metadata.procedure-files` are resolved under `sql-directory` when they are relative; absolute entries are used as-is.

For example:

```bash
cp application-example.yml application.yml
java -jar sql-metadata-viewer-0.0.1-SNAPSHOT.jar
```

## What It Shows

- Table list by schema/source file
- Table comments, engine, partition columns, source file and line range
- Every column name, type, type arguments, nullable flag, default value, comment and raw definition
- Procedure list by schema/source file
- Procedure parameters, header documentation, target/source/referenced tables, called procedures and raw SQL
- Reverse lookup from a table to procedures that reference it
- Filter tables by orphan/linked data-flow status
- Edit and persist table business remarks and field code values
- Grouped and collapsible table/procedure navigation
- Multiple independent custom grouping themes with multi-level groups, such as business themes and line-of-business themes
- Favorites, field filtering, dark theme and detail focus mode

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
- `GET /api/annotations/overview?themeId=...`
- `POST /api/custom-group-themes`
- `DELETE /api/custom-group-themes/{id}`
- `POST /api/custom-groups`
- `DELETE /api/custom-groups/{id}?themeId=...`
- `POST /api/custom-groups/assignment`

By default the backend reads SQL from the project root. Override it with:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--metadata.sql-directory=/path/to/sql"
```

## License

This project is released under the [MIT License](LICENSE).

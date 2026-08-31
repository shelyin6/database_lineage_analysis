# Contributing

Thanks for your interest in improving SQL Metadata Viewer.

## Development Setup

Requirements:

- JDK 17
- Maven 3.6+

Run locally:

```bash
mvn spring-boot:run
```

Package:

```bash
mvn -DskipTests package
```

The frontend is bundled as static files under `src/main/resources/static`. No public CDN is required at runtime.

## Before Opening a Pull Request

- Run `node --check src/main/resources/static/app.js` after frontend changes.
- Run `mvn -DskipTests package` after Java or build changes.
- Do not commit real business SQL files, SQLite databases, generated jars, internal reports, passwords, keys, or environment-specific configuration.
- Keep changes focused. Separate unrelated refactors from feature or bug-fix pull requests.

## Coding Guidelines

- Prefer existing project patterns over new framework-level abstractions.
- Keep SQL parsing conservative: if a source or lineage relationship is ambiguous, leave it empty rather than returning a misleading result.
- Keep user-facing text clear and concise.

## Reporting Issues

When reporting a parser bug, include a minimal SQL snippet that reproduces the issue. Remove business identifiers or sensitive logic before sharing.

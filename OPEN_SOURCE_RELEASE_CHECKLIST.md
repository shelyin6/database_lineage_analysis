# Open Source Release Checklist

Use this checklist before pushing the repository to GitHub.

- [ ] Confirm the copyright holder in `LICENSE`.
- [ ] Remove or keep ignored all real `.sql` files from internal systems.
- [ ] Remove local SQLite databases such as `metadata.sqlite`.
- [ ] Remove generated jars and build outputs from `dist/` and `target/`.
- [ ] Remove internal analysis documents that include business table names or process logic.
- [ ] Review `README.md` for default passwords and deployment assumptions.
- [ ] Confirm the bundled Vue license remains under `src/main/resources/static/vendor/vue.LICENSE.txt`.
- [ ] Confirm no time-limited or environment-specific release logic remains.
- [ ] Run `git status --short` and verify no confidential files are staged.
- [ ] Run `node --check src/main/resources/static/app.js`.
- [ ] Run `mvn -DskipTests package`.

Suggested first commit:

```bash
git add .
git status --short
git commit -m "Initial open source release"
```

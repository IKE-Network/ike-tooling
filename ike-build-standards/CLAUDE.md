# IKE Build Standards — Claude Standards

## Initial Setup — ALWAYS DO THIS FIRST

This module IS the standards source. The standards files live in
`src/main/standards/`. Read them directly — no unpacking needed.

Read these files in `src/main/standards/`:

- MAVEN.md — Maven 4 build standards (always read)
- IKE-MAVEN.md — IKE-specific Maven conventions (always read)

## Module Overview

This module produces seven classified ZIP artifacts:
- `classifier=claude`           — Claude instruction files (Markdown)
- `classifier=docs`             — human-readable convention documents (AsciiDoc)
- `classifier=config`           — shared static config (checkstyle, editorconfig, …)
- `classifier=asciidoctorconfig` — shared `.asciidoctorconfig` fragment
- `classifier=scaffold`         — scaffold manifest + template files consumed by
                                   `ike:scaffold-draft/publish/revert` (#221, #222)
- `classifier=site-theme`       — canonical Forest theme `site.css` + `ike-logo.svg`,
                                   unpacked at `pre-site` by `ike-parent`'s
                                   `site-resources` profile so every consuming project
                                   inherits the same Maven-Site theme without carrying
                                   per-repo copies (#318). Hosted here rather than in
                                   `ike-doc-resources` so `ike-tooling` itself can
                                   consume it (#308) without inverting the cascade.
- `classifier=built-with`       — platform-wide Built-With supplement (`supplement.yaml`)
                                   unpacked at `initialize` by `ike-parent` to
                                   `target/built-with-supplement.yaml`. The `ike:built-with`
                                   mojo reads it as a third-priority fallback after the
                                   per-project and walk-up locations, so external
                                   consumers (`ike-lab-documents`, `doc-example`, etc.)
                                   get the Curated narrative section on their
                                   `built-with.html` without authoring their own
                                   supplement (#340).

Consumer modules unpack the `claude` artifact at `validate` phase into
`.claude/standards/` via `maven-dependency-plugin`.

The `scaffold` artifact unpacks to a flat tree rooted at
`scaffold-manifest.yaml`; the manifest's `standards-version` is filtered
to `${project.version}` at assembly time so the consumed zip carries
the concrete artifact version into the lockfile.

- **Packaging**: POM (no compiled code)
- **Versioning**: Unified pipeline version (matches all reactor modules)

## Key Conventions

- Uses the ike-tooling reactor's single-segment integer version (e.g., `144-SNAPSHOT`)
- Assembly descriptors live in `src/assembly/`: `claude-standards.xml`,
  `docs.xml`, `config.xml`, `asciidoctorconfig.xml`, `scaffold.xml`,
  `site-theme.xml`, `built-with.xml`. Each maps to one classified ZIP
  execution in `pom.xml`'s `maven-assembly-plugin` config.
- Source content for each classifier lives in `src/main/<classifier>/`
  (e.g. `src/main/site-theme/css/site.css`).
- Version is managed in `ike-parent`, which provides inline dependency management

## Build

```bash
mvn install
```

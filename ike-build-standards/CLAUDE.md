# IKE Build Standards — Claude Standards

## Initial Setup — ALWAYS DO THIS FIRST

This module IS the standards source. The standards files live in
`src/main/standards/`. Read them directly — no unpacking needed.

Read these files in `src/main/standards/`:

- MAVEN.md — Maven 4 build standards (always read)
- IKE-MAVEN.md — IKE-specific Maven conventions (always read)

## Module Overview

This module produces six classified ZIP artifacts:
- `classifier=claude`           — Claude instruction files (Markdown)
- `classifier=docs`             — human-readable convention documents (AsciiDoc)
- `classifier=config`           — shared static config (checkstyle, editorconfig, …)
- `classifier=asciidoctorconfig` — shared `.asciidoctorconfig` fragment
- `classifier=scaffold`         — scaffold manifest + template files consumed by
                                   `ike:scaffold-draft/publish/revert` (#221, #222)
- `classifier=site-theme`       — **DEPRECATED** as of ike-base-parent v5
                                   (IKE-Network/ike-issues#464). Canonical Forest theme
                                   moved to `network.ike:ike-base-parent:site-theme:zip`
                                   so direct ike-base-parent inheritors get the theme
                                   natively and Tier 0 no longer depends on Tier 1.
                                   This artifact stays one cycle for back-compat
                                   (consumers that pinned `ike-build-standards:site-theme`
                                   directly) and will be removed in a future
                                   ike-tooling release. New consumers should use
                                   `network.ike:ike-base-parent:site-theme:zip`
                                   instead, automatically resolved by ike-parent's
                                   `site-resources` profile.
- `classifier=built-with`       — platform-wide Built-With supplement (`supplement.yaml`)
                                   unpacked at `initialize` by `ike-parent` to
                                   `target/built-with-supplement.yaml`. The `ike:built-with`
                                   mojo reads it as a third-priority fallback after the
                                   per-project and walk-up locations, so external
                                   consumers (`ike-lab-documents`, `doc-example`, etc.)
                                   get the Curated narrative section on their
                                   `built-with.html` without authoring their own
                                   supplement (#340).

The cross-repo release ordering is no longer a classified artifact:
each foundation repo version-controls its own
`src/main/cascade/release-cascade.yaml` declaring only its own
upstream/downstream edges, and the full graph is assembled by
traversal (IKE-Network/ike-issues#420).

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

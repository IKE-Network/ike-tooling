# IKE Build Standards — Claude Standards

## Initial Setup — ALWAYS DO THIS FIRST

This module IS the standards source. The standards files live in
`src/main/standards/`. Read them directly — no unpacking needed.

Read these files in `src/main/standards/`:

- MAVEN.md — Maven 4 build standards (always read)
- IKE-MAVEN.md — IKE-specific Maven conventions (always read)

## Module Overview

This module produces several classified ZIP artifacts:
- `classifier=claude`           — Claude instruction files (Markdown)
- `classifier=docs`             — human-readable convention documents (AsciiDoc)
- `classifier=config`           — shared static config (checkstyle, editorconfig, …)
- `classifier=asciidoctorconfig` — shared `.asciidoctorconfig` fragment
- `classifier=scaffold`         — scaffold manifest + template files consumed by
                                   `ike:scaffold-draft/publish/revert` (#221, #222)

Consumer modules unpack the `claude` artifact at `validate` phase into
`.claude/standards/` via `maven-dependency-plugin`.

The `scaffold` artifact unpacks to a flat tree rooted at
`scaffold-manifest.yaml`; the manifest's `standards-version` is filtered
to `${project.version}` at assembly time so the consumed zip carries
the concrete artifact version into the lockfile.

- **Packaging**: POM (no compiled code)
- **Versioning**: Unified pipeline version (matches all reactor modules)

## Key Conventions

- Uses the unified pipeline version (e.g., `1.1.0-SNAPSHOT`)
- Assembly descriptors: `src/assembly/claude-standards.xml`, `src/assembly/docs.xml`
- Version is managed in `ike-parent`, which provides inline dependency management

## Build

```bash
mvn install
```

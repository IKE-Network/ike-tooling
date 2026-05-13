# ike-build-standards

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Documentation](https://img.shields.io/badge/docs-ike.network%2Fike--build--standards-blue)](https://ike.network/ike-tooling/ike-build-standards/)
[![IKE Network](https://img.shields.io/badge/IKE-Network-green)](https://ike.network/)

Multi-classifier Maven artifact carrying versioned reference material
for IKE Network projects: build conventions, documentation standards,
AI-assistant instruction files, shared build configuration, AsciiDoc
IDE-preview config, the workspace scaffold manifest, the platform-wide
Built-With supplement, and the canonical Maven-Site theme.

## Doc as Code + LLM-Friendly

The IKE Network treats documentation, build conventions, and
AI-assistant guidance as source-controlled artifacts that ship
through the same release cascade as code. Three consequences:

1. **Doc as code.** Every standard, template, and convention lives
   in this repository as a Markdown or AsciiDoc file. Changes flow
   through commits and the release cascade; consumers pin to a
   specific standards version exactly the way they pin a library
   version (via `${ike-tooling.version}`).

2. **LLM-friendly by construction.** The Markdown standards in
   this artifact's `claude` classifier are unpacked into every
   consuming project's `.claude/standards/` directory at `validate`
   phase. When a developer — or Claude itself — opens an IKE
   project, the agent reads the relevant standards from
   `.claude/standards/` and applies them automatically. The author
   doesn't have to remember each project's conventions; the
   project carries them.

3. **Same source for humans and machines.** The Markdown files are
   the canonical source. Humans read them on GitHub or in a local
   checkout; tools render them into the published Maven Site;
   LLMs read them via the standard `.claude/standards/` path. One
   file, three audiences.

## Build Standards (Markdown source)

The `claude` classifier ships every file from
[`src/main/standards/`](src/main/standards/) into each consumer's
`.claude/standards/`:

| Standard | What it covers |
|---|---|
| [`MAVEN.md`](src/main/standards/MAVEN.md) | Maven 4 build conventions (universal — applies to every Maven project) |
| [`JAVA.md`](src/main/standards/JAVA.md) | Java 25 language and library conventions |
| [`TESTING.md`](src/main/standards/TESTING.md) | JUnit 5, AssertJ, Mockito conventions; integration tests via `maven-invoker-plugin` |
| [`IKE-MAVEN.md`](src/main/standards/IKE-MAVEN.md) | IKE-specific Maven conventions (cascade, `ws:*` goals, classifier shape, BOM usage) |
| [`IKE-JAVA.md`](src/main/standards/IKE-JAVA.md) | IKE-specific Java conventions (record-builder usage, ASM 9.9, JPMS) |
| [`IKE-DOC.md`](src/main/standards/IKE-DOC.md) | Doc-only / hybrid / code-only module shapes; classifier-canonical attachment |
| [`IKE-DIAGRAMS.md`](src/main/standards/IKE-DIAGRAMS.md) | When to add a diagram, PlantUML vs GraphViz selection, authoring conventions |
| [`IKE-ASCIIDOC-FRAGMENT.md`](src/main/standards/IKE-ASCIIDOC-FRAGMENT.md) | Fragment-style AsciiDoc topic authoring (titles, sections, IDs, includes) |
| [`IKE-ASSEMBLY.md`](src/main/standards/IKE-ASSEMBLY.md) | How an assembly composes topic fragments; index blocks; cross-references |
| [`IKE-TOPIC-DECOMPOSITION.md`](src/main/standards/IKE-TOPIC-DECOMPOSITION.md) | Splitting prose into fragments; topic granularity guidance |
| [`IKE-TOPIC-REGISTRY.md`](src/main/standards/IKE-TOPIC-REGISTRY.md) | The topic registry YAML format; topic-IDs and cross-references |
| [`IKE-INDEX.md`](src/main/standards/IKE-INDEX.md) | AsciiDoc index-term conventions; term-to-topic reverse index for content discovery |
| [`IKE-CLASSIFIERS.md`](src/main/standards/IKE-CLASSIFIERS.md) | Maven artifact classifier conventions (`adoc`, `prince`, `fop`, `claude`, etc.) |
| [`IKE-INGEST.md`](src/main/standards/IKE-INGEST.md) | Ingest pipeline conventions (FHIR → ANF → Delta Lake) |
| [`IKE-KNOWLEDGE.md`](src/main/standards/IKE-KNOWLEDGE.md) | Knowledge-layer conventions (terminology, concept models) |
| [`IKE-RELEASE.md`](src/main/standards/IKE-RELEASE.md) | Release procedure conventions (foundation cascade, workspace cascade) |
| [`IKE-RELEASE-RECOVERY.md`](src/main/standards/IKE-RELEASE-RECOVERY.md) | Recovery procedures when a release is interrupted mid-cascade |
| [`IKE-SITE-XML.md`](src/main/standards/IKE-SITE-XML.md) | `site.xml` conventions (breadcrumbs, modules sidebar, href-identity dedupe) |
| [`IKE-WORKSPACE.md`](src/main/standards/IKE-WORKSPACE.md) | Workspace conventions (`workspace.yaml`, subproject layout, `ws:*` goals) |

Read any of these directly on GitHub (they are plain Markdown). For
the rendered published index, see
[`https://ike.network/ike-tooling/ike-build-standards/`](https://ike.network/ike-tooling/ike-build-standards/).

## Classifiers (Maven artifact shape)

| Classifier | Contents | Unpacked by |
|---|---|---|
| `claude` | Markdown standards (the files above) | Every consumer; lands at `.claude/standards/` at `validate` |
| `docs` | Human-readable convention documents in AsciiDoc | The published Maven Site of this module |
| `config` | Shared static build config (`.editorconfig`, checkstyle, stignore template) | Consumers at `validate` |
| `asciidoctorconfig` | Shared `.asciidoctorconfig` fragment | Consumers with `src/docs/asciidoc/`, for IDE preview |
| `scaffold` | Workspace scaffold manifest + template files | `ike:scaffold-draft` / `ike:scaffold-publish` |
| `site-theme` | Canonical Forest theme `site.css` + `ike-logo.svg` | `ike-parent`'s `site-resources` profile at `pre-site` |
| `built-with` | Platform-wide `supplement.yaml` for the Built-With narrative | `ike-parent` at `initialize`; consumed by `ike:built-with` |

## Consumer integration

In `ike-parent` (transitively reaches every IKE project):

```xml
<dependency>
    <groupId>network.ike.tooling</groupId>
    <artifactId>ike-build-standards</artifactId>
    <version>${ike-tooling.version}</version>
    <classifier>claude</classifier>
    <type>zip</type>
    <scope>provided</scope>
</dependency>
```

The `validate`-phase `maven-dependency-plugin:unpack-dependencies`
execution writes the contents to `.claude/standards/`. The
`.gitignore` shipped via the same artifact excludes that directory,
so the standards never check in — they're always live from Nexus
at the pinned version.

## Build

```bash
mvn install
```

Produces all seven classified ZIPs from `src/assembly/<name>.xml`
descriptors. No compiled code; this is a content-only artifact.

## Links

- **Documentation:** [`https://ike.network/ike-tooling/ike-build-standards/`](https://ike.network/ike-tooling/ike-build-standards/)
- **Parent project:** [`ike-tooling`](https://ike.network/ike-tooling/)
- **Issues:** [`IKE-Network/ike-issues`](https://github.com/IKE-Network/ike-issues) (cross-project tracker)
- **Source:** [`IKE-Network/ike-tooling`](https://github.com/IKE-Network/ike-tooling) (the `ike-build-standards` submodule)

## License

Apache License 2.0. See [LICENSE](../LICENSE) or
[apache.org/licenses/LICENSE-2.0](https://www.apache.org/licenses/LICENSE-2.0).

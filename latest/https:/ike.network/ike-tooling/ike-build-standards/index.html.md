---
date_published: 2026-05-10
date_modified: 2026-05-10
canonical_url: https://ike.network/ike-tooling/ike-build-standards/index.html
---

# IKE Build Standards

`ike-build-standards` is a multi-classifier Maven artifact that ships versioned reference material for IKE Network projects: AI-assistant instruction files, human-readable convention documents, shared build configuration, AsciiDoc IDE-preview config, the workspace scaffold manifest, and the canonical Maven-Site theme.

| Coordinate | Value |
| --- | --- |
| Group ID | `network.ike.tooling` |
| Artifact ID | `ike-build-standards` |
| Packaging | `pom` |

The artifact has no compiled code. It carries content only — six classified ZIPs, each unpacked by a different consumer.

## [#the-six-classifiers](#the-six-classifiers)The six classifiers

| Classifier | What’s in it | Who unpacks it |
| --- | --- | --- |
| `claude` | Markdown standards files for AI-assistant context: `MAVEN.md`, `IKE-MAVEN.md`, `JAVA.md`, `IKE-JAVA.md`, `IKE-DOC.md`, `IKE-RELEASE.md`, `TESTING.md`, etc. | Each consumer project unpacks at the `validate` phase via `maven-dependency-plugin` into `.claude/standards/`. The local `CLAUDE.md` reads them. |
| `docs` | Human-readable convention documents in AsciiDoc format (`ike-workspace-conventions.adoc`, etc.) — same conventions, but rendered as proper documentation rather than instruction prose. | The IKE doc pipeline (where applicable) — readable in any AsciiDoc viewer. |
| `config` | Shared static build configuration: `.editorconfig`, `checkstyle.xml`, `.stignore.template`. Files that should be byte-identical across every IKE workspace. | `ws:scaffold-upgrade-publish` writes them to the workspace root. |
| `asciidoctorconfig` | The shared `.asciidoctorconfig` fragment that gives IDEs (IntelliJ, VS Code) a working AsciiDoc preview matching the build’s renderer. | Each consuming module unpacks it so live preview matches the Maven-rendered output. |
| `scaffold` | The workspace scaffold manifest (`scaffold-manifest.yaml`) and its template files: gitignore blocks, git hooks, IDE settings, `.mvn/maven.config`. The manifest’s `standards-version` is filtered to `154` at assembly time so the consumed zip carries the concrete artifact version into the lockfile. | `ws:scaffold-upgrade-{draft,publish}` consult the manifest to detect drift and apply upgrades. |
| `site-theme` | Canonical Forest-theme `site.css` and `ike-logo.svg` for the Sentry Maven Site skin. Single source of truth for the ike.network theme — bumping a color here propagates to every consumer’s site on the next ike-tooling release. | `ike-parent’s `site-resources` profile (activated when `src/site/` exists) unpacks at `pre-site` into `target/generated-site/resources/`, which `maven-site-plugin` auto-merges into `target/site/`. See ike-issues#318. Hosted here rather than in `ike-doc-resources` so `ike-tooling’s own modules can consume it (#308) without inverting the release cascade. |

## [#versioning](#versioning)Versioning

Standards follow the unified `ike-tooling` reactor version. When standards evolve, consuming projects pick up the changes on their next `mvn validate` (the unpack step is wired to that phase). The `standards-version` field in the scaffold manifest’s lockfile records which version a workspace last upgraded to, so `ws:scaffold-upgrade` can detect "this workspace is on N, current is N+3".

## [#why-a-separate-module](#why-a-separate-module)Why a separate module

Pulling reference material out of the consuming projects' source trees avoids three problems:

1. **Drift** — a hand-edited `MAVEN.md` in one repo silently diverges from another’s. Centralizing means there’s one canonical version.
2. **Version stamping** — knowing which set of standards a workspace is using requires a single artifact-version pin, not a guess based on file contents.
3. **AI assistant context** — the `claude` classifier is the contract: Claude reads `.claude/standards/*.md` after every `mvn validate`, so the instructions match the build version.

## [#key-files-in-claude-classifier](#key-files-in-claude-classifier)Key files in `claude` classifier

| File | Scope |
| --- | --- |
| `MAVEN.md` | Maven 4 build standards: POM model 4.1.0 conventions, property naming, profile structure, lifecycle phases. |
| `IKE-MAVEN.md` | IKE-specific Maven conventions: parent architecture, doc-pipeline profile, artifact distribution, property-driven builds. |
| `JAVA.md` | Java 25 standards: preview features, pattern matching, records, sealed classes, virtual threads. |
| `IKE-JAVA.md` | IKE-specific Java patterns: RocksDB usage, gRPC conventions, Koncept extension development. |
| `IKE-DOC.md` | AsciiDoc documentation project standards. |
| `IKE-RELEASE.md` | Release process — single-segment monotonic versioning, branch cadence, tag conventions. |
| `IKE-RELEASE-RECOVERY.md` | Recovery playbook for interrupted releases. |
| `IKE-WORKSPACE.md` | Workspace plugin conventions; complements `ws:*` runtime help. |
| `IKE-TOPIC-DECOMPOSITION.md`, `IKE-TOPIC-REGISTRY.md`, `IKE-ASCIIDOC-FRAGMENT.md`, `IKE-ASSEMBLY.md`, `IKE-INGEST.md`, `IKE-DIAGRAMS.md`, `IKE-CLASSIFIERS.md`, `IKE-INDEX.md`, `IKE-KNOWLEDGE.md` | Documentation-system standards used by `ike-lab-documents` and other doc-only projects. |
| `TESTING.md` | Test conventions across all IKE modules. |

## [#source](#source)Source

- GitHub: [ike-tooling/ike-build-standards](https://github.com/IKE-Network/ike-tooling/tree/main/ike-build-standards)[1]
- Issues: [IKE-Network/ike-issues](https://github.com/IKE-Network/ike-issues)[2]

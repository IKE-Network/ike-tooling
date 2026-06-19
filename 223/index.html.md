---
date_published: 2026-06-18
date_modified: 2026-06-18
canonical_url: https://ike.network/ike-tooling/index.html
---

# IKE Tooling

[https://central.sonatype.com/artifact/network.ike.tooling/ike-tooling](https://central.sonatype.com/artifact/network.ike.tooling/ike-tooling)[1]

Build tooling for the IKE Network: workspace management, release orchestration, gitflow workflows, and build-time utilities.

| Coordinate | Value |
| --- | --- |
| Group ID | `network.ike.tooling` |
| Packaging | POM (reactor) |

## [#modules](#modules)Modules

| Module | Description |
| --- | --- |
| [IKE Build Standards](ike-build-standards/index.html)[2] | Versioned reference material distributed as classified ZIP artifacts: AI-assistant standards, convention documents, shared build configuration, AsciiDoc IDE-preview config, and the workspace scaffold manifest. |
| [IKE Workspace Model](ike-workspace-model/index.html)[3] | Data model and conventions for an IKE workspace. Consumed by both `ike-workspace-maven-plugin` and `ike-maven-plugin` so the two plugins share a single source of truth for what a workspace is. Includes the four-state alignment model (snapshot / tag-aligned-release / tag-aligned-checkpoint / external-consumer / unrelated) and graph algorithms for topological ordering. |
| [IKE Maven Plugin Support](ike-maven-plugin-support/index.html)[4] | Shared library used by `ike-maven-plugin` and `ike-workspace-maven-plugin` (which lives in `ike-platform`). Goal-registry helper, base mojo with markdown-report writing, parameter parsing, and released-version resolution. |
| [IKE Maven Plugin](ike-maven-plugin/index.html)[5] | The `ike:*` plugin — release orchestration, scaffolding, site deploy, AsciiDoc rendering, and build-time utilities. ~30 goals. |

## [#quick-start](#quick-start)Quick Start

The most common single-repo operations:

```
# Diagnose any in-flight or partial release (read-only)
mvn ike:release-status

# Preview a release (writes a markdown report; no on-disk changes)
mvn ike:release-draft

# Execute a release: tag, deploy to Nexus + komet.sh + gh-pages,
# update latest symlink, push, create GitHub Release
mvn ike:release-publish

# Re-deploy the site for the current release without re-releasing
mvn ike:site-publish

# Apply scaffold convention upgrades (gitignore, hooks, IDE settings)
mvn ike:scaffold-publish
```

For workspace-spanning operations (`ws:*` prefix), see the [ws plugin docs](https://ike.network/ike-platform/ike-workspace-maven-plugin/)[6] in `ike-platform`.

## [#goal-naming-convention](#goal-naming-convention)Goal naming convention

Most state-mutating goals come in two forms:

- `*-draft` — preview only. Writes a markdown report, makes no on-disk changes. Safe to run any time.
- `*-publish` — executes the action.

The bare goal name (e.g. `ike:release`) is wired to the draft variant. When you see `ike:release-publish` written without a matching `-draft` form, treat the missing suffix as "actually do it."

## [#release-cascade](#release-cascade)Release Cascade

```
[ike-tooling] -> ike-docs -> ike-platform -> { downstream consumers }
```

`ike-tooling` releases first because `ike-docs` and `ike-platform` declare `ike-maven-plugin` in their `<pluginManagement>` and consume `ike-maven-plugin-support` as a regular dependency. Both must already be resolvable from Nexus when downstream reactors load. The `ws:*` plugin in `ike-platform` co-releases with the rest of the platform reactor and references `ike-maven-plugin-support` from this repo.

The cascade is structurally upstream-first; it is not driven by extension-realm timing. See [Design rationale](#design-rationale) for why no plugin in this repo uses `<extensions>true</extensions>`.

## [#design-rationale](#design-rationale)Design rationale

The plugins in this repo are **regular Maven plugins** — none of them declares `<extensions>true</extensions>`, none of them registers a custom packaging type via `META-INF/plexus/components.xml`, and none of them participates in the Maven build extension realm.

This is a deliberate architectural choice, not an oversight.

### [#why-no-extensions](#why-no-extensions)Why no extensions

A plugin with `<extensions>true</extensions>` is loaded into the build extension realm at **project-load time** — during Maven’s “Scanning for projects” phase, before the Model Builder runs property interpolation. This means the plugin’s `<version>` in the POM cannot be a `${property}`; it must be a literal string.

For a single-repo reactor with one plugin, that constraint is manageable. For our cross-repo cascade (`ike-tooling` → `ike-docs` → `ike-platform` → consumers), it became toxic: every consumer POM had to carry the literal version of every extension plugin from upstream, the literal had to be kept in sync manually across repos, and routine version-property tooling (like `ws:align-publish` and `versions:set-property`) was structurally unable to maintain it.

The pain surfaced as `IKE-Network/ike-issues#236` — pre-flight release checks repeatedly catching stale literals after upstream releases that the alignment tooling could not see.

### [#why-no-custom-packaging](#why-no-custom-packaging)Why no custom packaging

The historical reason for `<extensions>true</extensions>` here was to provide a custom `<packaging>ike-doc</packaging>` type for documentation modules — a packaging that produces a `.zip` of AsciiDoc sources as the primary artifact, with a lean lifecycle that skips compile/test phases.

Inspection revealed two problems with that frame:

1. **The custom packaging produced a primary artifact nobody used.** Across 43 `<packaging>ike-doc</packaging>` modules in `ike-lab-documents`, **zero** dependencies referenced `<type>ike-doc</type>`. Consumers exclusively used a parallel `<classifier>asciidoc</classifier><type>zip</type>` attachment (built by `maven-assembly-plugin` from the same source directory). Same content, two coordinates, redundant.
2. **Custom packaging cannot serve hybrid modules.** `<packaging>` is single-valued: a Java module that ships reference docs cannot be both `jar` and `ike-doc`. Every comparable docs-as-code project in the JVM ecosystem (Hibernate, Eclipse Collections, Vert.x, Quarkus, OptaPlanner, Apache projects) handles this via classifier attachments for exactly this reason. No project in the surveyed set invents a custom packaging type for documentation.

The architectural decision was therefore to retire `<packaging>ike-doc</packaging>` in favor of a classifier-canonical shape: `<classifier>adoc</classifier><type>zip</type>` attached to either a `<packaging>pom</packaging>` (doc-only) or `<packaging>jar</packaging>` (hybrid) primary. Activation is path-conditional on `src/docs/asciidoc/` existence in `ike-parent’s `doc-pipeline` profile — **not** packaging type — so hybrid modules become first-class without per-module ceremony.

### [#why-this-is-not-springs-path](#why-this-is-not-springs-path)Why this is not Spring’s path

A natural objection: most large JVM projects (Spring, Apache projects, Quarkus) publish docs as static sites to gh-pages or CDN-hosted destinations rather than as Maven artifacts. Why not follow that pattern?

Examined point by point, those rationales do not transfer to our context:

- **Continuous publication.** Maven snapshots provide this — `mvn deploy` to a snapshot repo on every commit, snapshot semantics handle “latest” natively.
- **Search-driven discovery.** Static URLs are indexable; rsyncing HTML to a public web server downstream of a Maven artifact gives the same indexable URLs. `knowledge.design` already operates this way.
- **Contributor friction.** CI eliminates this — PR merge fires `mvn deploy` plus a post-deploy hook to rsync rendered output.
- **CDN economics.** Same as search — rsync to CDN works regardless of canonical-storage format.

What honestly remains as a non-Maven argument:

- **Antora as a turnkey product.** It does cross-version aggregation, theming, navigation, and CDN-friendly output as one coherent thing. Adopt Antora and you do not build any of that. **Does not apply here** — we have already built the equivalent pipeline (`ike-parent’s render profiles, the Prince/FOP/XEP chains, `knowledge.design` rsync deploy, the asciidoctor toolchain).
- **ASF organizational policy.** Apache projects cannot publish docs as Maven artifacts and call it done. A foundation rule, not an engineering reason. Does not apply outside ASF.
- **Path dependency.** Spring 1.0 shipped in 2003-04, before Maven Central was a viable documentation distribution channel. Spring’s current pipeline descends from choices that were rational then and are too costly to rip out now. Explains why-they-still-do-it but does not justify what a new project would choose today.

For our constraints — small team, cross-repo doc dependencies via topic libraries, an already-built rendering pipeline, and a standing commitment to artifact uniformity — staying inside the Maven artifact ecosystem is the rigorous choice. Every shipped artifact, code or documentation, goes through one pipeline: signed (Bouncy Castle GPG), versioned, deployed to Nexus, mirrorable, and reproducibly buildable years later. We hold every output to the same engineering standard — consistent signing, consistent distribution, consistent build lifecycle, consistent version control. The single-pipeline discipline is the standard we are protecting; uniformity across artifact classes is the point.

The classifier-canonical shape **is** fully Maven-canonical — it deploys to Nexus, signed, versioned. The choice between custom packaging and classifier is not a Maven-vs-non-Maven choice; both stay inside the Maven ecosystem. Custom packaging was buying an aesthetic type-system marker; classifier-canonical buys a single mechanism that handles doc-only and hybrid uniformly.

### [#tracking](#tracking)Tracking

- `IKE-Network/ike-issues#321` — primary tracking issue (umbrella); subsumes #220, #236, #320.
- `IKE-Network/ike-issues#216` — repo split that established the cross-repo boundary the extension realm was working around.
- Design note: `dev-classifier-canonical-doc-shape` in `ike-lab-documents/topics/` (full Socratic discovery captured for posterity).

## [#the-ike-foundation](#the-ike-foundation)The IKE foundation

`ike-tooling` is one of four foundation projects published to Maven Central. Together they form the parent-inheritance forest that every IKE project builds on:

- [ike-base-parent](https://ike.network/ike-base-parent/)[7] — Tier 0 foundation parent POM; shared publishing metadata and signing config.
- [ike-tooling](https://ike.network/ike-tooling/)[8] — Maven plugins for release orchestration and workspace management.
- [ike-docs](https://ike.network/ike-docs/)[9] — the AsciiDoc documentation pipeline and `idoc:*` plugin.
- [ike-platform](https://ike.network/ike-platform/)[10] — the consumer-facing `ike-parent`, the BOM, and the `ws:*` plugin.

## [#resources](#resources)Resources

| Resource | URL |
| --- | --- |
| GitHub | [https://github.com/IKE-Network/ike-tooling](https://github.com/IKE-Network/ike-tooling)[11] |
| Issues | [https://github.com/IKE-Network/ike-issues](https://github.com/IKE-Network/ike-issues)[12] |
| Nexus Artifacts | [https://nexus.tinkar.org](https://nexus.tinkar.org)[13] |
| IKE Network | [https://ike.network](https://ike.network)[14] |

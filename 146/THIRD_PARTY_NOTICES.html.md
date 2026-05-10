---
date_published: 2026-05-09
date_modified: 2026-05-09
canonical_url: https://ike.network/ike-tooling/THIRD_PARTY_NOTICES.html
---

# IKE Tooling — Third-Party Notices

This page is the curated companion to two mechanical inventories that ship alongside it:

- [Software Bill of Materials (CycloneDX, JSON)](bom.json)[1] — full transitive dependency graph with SPDX-normalized licenses and artifact hashes. Ingestible by Dependency-Track, Trivy, Snyk, GitHub’s dependency graph, etc. Also reachable as a Maven artifact with `<classifier>cyclonedx</classifier>`.
- [Maven dependencies report](dependencies.html)[2] — auto-generated HTML browse of declared dependencies, with verbatim per-license text from each dependency’s POM. License names appear here as declared in the upstream POM (often noisy: "Apache 2.0" vs. "The Apache Software License, Version 2.0"); the SBOM normalizes these to canonical SPDX identifiers.

The curated content below covers what neither sees — Maven core machinery, plugins, the Maven Site skin, build-time signing — that the mechanical reports either don’t reach or under-report. License identifiers below are SPDX form (`Apache-2.0`, `MIT`, `EPL-2.0`, expressions with `OR` / `AND` / `WITH`) so they’re unambiguous and grep-friendly.

For the corresponding notices in the rest of the IKE platform see:

- `[ike-docs](../ike-docs/THIRD_PARTY_NOTICES.html)[3]` — AsciiDoc rendering chain, fonts, DocBook, frontend assets.
- `[ike-platform](../ike-platform/THIRD_PARTY_NOTICES.html)[4]` — Java toolchain, BOM-managed dependencies, test framework.

## [#maven-build-infrastructure](#maven-build-infrastructure)Maven build infrastructure

| Component | License | Role |
| --- | --- | --- |
| [Apache Maven](https://maven.apache.org/)[5] (core, build extensions, plugin API) | `Apache-2.0` | Build orchestration. `ike-tooling` consumes `maven-api-core`, `maven-api-plugin`, `maven-api-impl`, `maven-plugin-tools-annotations`, and the standard build lifecycle plugins. |
| [Maven Site Plugin](https://maven.apache.org/plugins/maven-site-plugin/)[6] | `Apache-2.0` | Generates the project Maven Site (this site). |
| [Maven Project Info Reports Plugin](https://maven.apache.org/plugins/maven-project-info-reports-plugin/)[7] | `Apache-2.0` | Produces `licenses.html`, `dependencies.html`, `plugin-management.html`, `dependency-management.html`, and the rest of the standard reports visible on the left navigation. |
| [Maven Dependency Plugin](https://maven.apache.org/plugins/maven-dependency-plugin/)[8] | `Apache-2.0` | Unpacks classified ZIP artifacts (`claude`, `site-theme`, `asciidoctorconfig`, `scaffold`) into consumer build directories. |
| [Maven Assembly Plugin](https://maven.apache.org/plugins/maven-assembly-plugin/)[9] | `Apache-2.0` | Builds the classified ZIP artifacts produced by `ike-build-standards`. |
| [Maven Compiler Plugin](https://maven.apache.org/plugins/maven-compiler-plugin/)[10], [Surefire](https://maven.apache.org/surefire/maven-surefire-plugin/)[11], [Jar Plugin](https://maven.apache.org/plugins/maven-jar-plugin/)[12], [Resources](https://maven.apache.org/plugins/maven-resources-plugin/)[13], [Install](https://maven.apache.org/plugins/maven-install-plugin/)[14], [Deploy](https://maven.apache.org/plugins/maven-deploy-plugin/)[15], [Clean](https://maven.apache.org/plugins/maven-clean-plugin/)[16] | `Apache-2.0` | Standard Maven lifecycle plugins. |
| [Maven Source Plugin](https://maven.apache.org/plugins/maven-source-plugin/)[17], [Javadoc Plugin](https://maven.apache.org/plugins/maven-javadoc-plugin/)[18] | `Apache-2.0` | Source jar and javadoc generation during release. |
| [Maven GPG Plugin](https://maven.apache.org/plugins/maven-gpg-plugin/)[19] | `Apache-2.0` | Signs release artifacts. Configured to use the `bc` (Bouncy Castle) signer for parallel-safe signing without `gpg-agent` contention. |
| [CycloneDX Maven Plugin](https://github.com/CycloneDX/cyclonedx-maven-plugin)[20] | `Apache-2.0` | Generates the SBOM linked at the top of this page (`bom.json`, `bom.xml`). See ike-issues#333. |

## [#maven-site-skin](#maven-site-skin)Maven Site skin

| Component | License | Role |
| --- | --- | --- |
| [Sentry Maven Skin](https://github.com/sentrysoftware/maven-skins)[21] | `Apache-2.0` | The Maven Site skin applied to every published page. Provides the base navigation chrome (header, sidebar, breadcrumbs). The IKE Forest theme overlays Sentry’s defaults via the `site-theme` classifier produced by `ike-build-standards` (see #318). |
| [Maven Skin Tools](https://github.com/sentrysoftware/maven-skins/tree/main/maven-skin-tools)[22] | `Apache-2.0` | Velocity helper context (`$headElement`, `$bodyElement`) used by the Sentry skin. Pulled in as a `maven-site-plugin` dependency. |
| [GraalVM Polyglot, GraalVM JS](https://www.graalvm.org/)[23] | `GPL-2.0-only WITH Classpath-exception-2.0` (Community Edition) | Replaces the deprecated Nashorn engine for skin script evaluation. Pulled in as a `maven-site-plugin` dependency. |
| [Prism](https://prismjs.com/)[24] | `MIT` | Syntax highlighter shipped in rendered HTML. Used by the AsciiDoc pipeline; per-language CSS is bundled in the rendered output. |

## [#cryptographic-signing](#cryptographic-signing)Cryptographic signing

| Component | License | Role |
| --- | --- | --- |
| [Bouncy Castle (bcprov-jdk18on, bcpg-jdk18on)](https://www.bouncycastle.org/)[25] | `MIT` (Bouncy Castle distributes under an MIT-X11 adaptation; SPDX classifies as `MIT`) | OpenPGP signing engine used by `maven-gpg-plugin’s `bc` signer. IKE releases consistently use `bc` because the native `gpg` signer serializes through `gpg-agent` and breaks parallel reactor builds. |

## [#test-frameworks](#test-frameworks)Test frameworks

| Component | License | Role |
| --- | --- | --- |
| [JUnit 5 (Jupiter, Platform)](https://junit.org/junit5/)[26] | `EPL-2.0` | Unit test framework. |
| [AssertJ](https://assertj.github.io/)[27] | `Apache-2.0` | Fluent assertion library used in tests. |
| [Mockito](https://site.mockito.org/)[28] | `MIT` | Mocking framework used in `ike-workspace-model` tests. |

## [#documentation-tooling-consumed-during-plugin-devel](#documentation-tooling-consumed-during-plugin-devel)Documentation tooling consumed during plugin development

The ike-tooling reactor itself does not render AsciiDoc to PDF or HTML — that work belongs to `ike-docs`. The Maven Site of this reactor consumes:

| Component | License | Role |
| --- | --- | --- |
| [Asciidoctor Parser Doxia Module](https://github.com/asciidoctor/asciidoctor-doxia)[29] | `MIT` | Lets `maven-site-plugin` render the `src/site/asciidoc/*.adoc` files (this page among them) as part of `site:site`. |
| [JRuby](https://www.jruby.org/)[30] | `EPL-2.0 OR GPL-2.0-only OR LGPL-2.1-only` | Embedded Ruby runtime that the AsciiDoc parser uses. Pulled in transitively by the Doxia module. The `OR` is consumer’s choice — for downstream users including IKE, EPL-2.0 is the most redistribution-friendly of the three. |

## [#verification](#verification)Verification

- `mvn package` produces `target/bom.json` and `target/bom.xml` (CycloneDX 1.6 format) with SPDX-normalized licenses. Validate with `cyclonedx-cli validate --input-file target/bom.json` or ingest into Dependency-Track.
- `mvn site` populates `target/site/licenses.html`, `target/site/dependencies.html`, `target/site/plugins.html`, and `target/site/plugin-management.html` from declared `<dependencies>`, `<build><plugins>`, and `<build><pluginManagement>`.
- The same `mvn site` run copies `bom.json` and `bom.xml` into `target/site/` so they’re reachable from the published Maven Site.
- This curated document complements those mechanical inventories. Issues or omissions: file an issue at [https://github.com/IKE-Network/ike-issues](https://github.com/IKE-Network/ike-issues)[31].

## [#related](#related)Related

- [ike-tooling site index](index.html)[32]
- [ike-issues#331](https://github.com/IKE-Network/ike-issues/issues/331)[33] — comprehensive third-party attribution (closed; this page’s curated content).
- [ike-issues#333](https://github.com/IKE-Network/ike-issues/issues/333)[34] — SBOM via CycloneDX + SPDX normalization (the mechanical inventory linked at the top of this page).

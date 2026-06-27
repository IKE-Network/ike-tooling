---
date_published: 2026-06-26
date_modified: 2026-06-26
canonical_url: https://ike.network/ike-tooling/ike-maven-plugin/built-with.html
---

# Built With

Open-source software that `ike-maven-plugin` 228 depends on, links against, ships within, or invokes at runtime.

Three layers of attribution ship with each release:

- [Software Bill of Materials (CycloneDX, JSON)](bom.json)[1] — full transitive dependency graph with SPDX-normalized licenses and artifact hashes. Ingestible by Dependency-Track, Trivy, Snyk, GitHub’s dependency graph.
- [Licenses (SPDX)](licenses.html)[2] — human-readable SPDX-grouped view of declared dependencies, generated from `bom.json` (#335).
- This page — curated companion covering what mechanical reports can’t see (Maven Site skin, external services, fonts inside artifacts, frontend assets in rendered HTML).

## [#curated-narrative](#curated-narrative)Curated narrative

Components covered by the project-wide supplement at `src/main/built-with/supplement.yaml`. These are the components that don’t appear in `bom.json` because they aren’t Maven artifacts (external services, fonts inside classifier ZIPs, runtime binaries, frontend assets).

### [#maven-build-infrastructure](#maven-build-infrastructure)Maven build infrastructure

| Component | License | Role |
| --- | --- | --- |
| [Apache Maven core, build extensions, plugin API](https://maven.apache.org/)[3] | `Apache-2.0` | Build orchestration. ike-tooling consumes maven-api-core, maven-api-plugin, maven-api-impl, maven-plugin-tools- annotations, and the standard build lifecycle plugins. |
| [Maven Site Plugin](https://maven.apache.org/plugins/maven-site-plugin/)[4] | `Apache-2.0` | Generates the project Maven Site (this site). |
| [Maven Project Info Reports Plugin](https://maven.apache.org/plugins/maven-project-info-reports-plugin/)[5] | `Apache-2.0` | Produces dependencies.html, dependency-info.html, plugin-management.html, dependency-management.html, and the rest of the standard reports under Project Reports. |
| [Maven Dependency Plugin](https://maven.apache.org/plugins/maven-dependency-plugin/)[6] | `Apache-2.0` | Unpacks classified ZIP artifacts (claude, site-theme, asciidoctorconfig, scaffold) into consumer build directories. |
| [Maven Assembly Plugin](https://maven.apache.org/plugins/maven-assembly-plugin/)[7] | `Apache-2.0` | Builds the classified ZIP artifacts produced by ike-build-standards. |
| [Maven Compiler / Surefire / Jar / Resources / Install / Deploy / Clean Plugins](https://maven.apache.org/plugins/)[8] | `Apache-2.0` | Standard Maven lifecycle plugins. |
| [Maven Source / Javadoc Plugins](https://maven.apache.org/plugins/)[8] | `Apache-2.0` | Source jar and javadoc generation during release. |
| [Maven GPG Plugin](https://maven.apache.org/plugins/maven-gpg-plugin/)[9] | `Apache-2.0` | Signs release artifacts. Configured to use the bc (Bouncy Castle) signer for parallel-safe signing without gpg-agent contention. |
| [CycloneDX Maven Plugin](https://github.com/CycloneDX/cyclonedx-maven-plugin)[10] | `Apache-2.0` | Generates the SBOM (bom.json + bom.xml) at package phase per ike-issues#333. |

### [#maven-site-skin](#maven-site-skin)Maven Site skin

| Component | License | Role |
| --- | --- | --- |
| [Sentry Maven Skin](https://github.com/sentrysoftware/maven-skins)[11] | `Apache-2.0` | The Maven Site skin applied to every published page. Provides the base navigation chrome (header, sidebar, breadcrumbs). The IKE Forest theme overlays Sentry’s defaults via the site-theme classifier produced by ike-build-standards (#318). |
| [Maven Skin Tools](https://github.com/sentrysoftware/maven-skins/tree/main/maven-skin-tools)[12] | `Apache-2.0` | Velocity helper context ($headElement, $bodyElement) used by the Sentry skin. Pulled in as a maven-site-plugin dependency. |
| [GraalVM Polyglot, GraalVM JS](https://www.graalvm.org/)[13] | `GPL-2.0-only WITH Classpath-exception-2.0` | Replaces the deprecated Nashorn engine for skin script evaluation. Pulled in as a maven-site-plugin dependency. Community Edition. |
| [Prism](https://prismjs.com/)[14] | `MIT` | Syntax highlighter shipped in rendered HTML. Used by the AsciiDoc pipeline; per-language CSS is bundled in the rendered output. |

### [#cryptographic-signing](#cryptographic-signing)Cryptographic signing

| Component | License | Role |
| --- | --- | --- |
| [Bouncy Castle (bcprov-jdk18on, bcpg-jdk18on)](https://www.bouncycastle.org/)[15] | `MIT` | OpenPGP signing engine used by maven-gpg-plugin’s bc signer. IKE releases consistently use bc because the native gpg signer serializes through gpg-agent and breaks parallel reactor builds. SPDX classifies the MIT-X11-style BC license as MIT. |

### [#test-frameworks](#test-frameworks)Test frameworks

| Component | License | Role |
| --- | --- | --- |
| [JUnit 5 (Jupiter, Platform)](https://junit.org/junit5/)[16] | `EPL-2.0` | Unit test framework. |
| [AssertJ](https://assertj.github.io/)[17] | `Apache-2.0` | Fluent assertion library used in tests. |
| [Mockito](https://site.mockito.org/)[18] | `MIT` | Mocking framework used in ike-workspace-model tests. |

### [#documentation-tooling-consumed-during-plugin-devel](#documentation-tooling-consumed-during-plugin-devel)Documentation tooling consumed during plugin development

| Component | License | Role |
| --- | --- | --- |
| [Asciidoctor Parser Doxia Module](https://github.com/asciidoctor/asciidoctor-doxia)[19] | `MIT` | Lets maven-site-plugin render the src/site/asciidoc/*.adoc files (this page among them) as part of site:site. |
| [JRuby](https://www.jruby.org/)[20] | `EPL-2.0 OR GPL-2.0-only OR LGPL-2.1-only` | Embedded Ruby runtime that the AsciiDoc parser uses. Pulled in transitively by the Doxia module. The OR is consumer’s choice. |

## [#mechanical-inventory](#mechanical-inventory)Mechanical inventory

Direct dependencies of this module, grouped by SPDX expression. Generated from `bom.json` at build time.

| SPDX Expression | Components |
| --- | --- |
| `Apache-2.0` | 54 |
| `BSD-2-Clause` | 2 |
| `EPL-2.0 OR GPL-2.0 OR LGPL-2.1` | 3 |
| `BSD-3-Clause` | 6 |
| `EPL-2.0 OR GNU General Public License Version 2 OR GNU Lesser General Public License Version 2.1` | 1 |
| `MIT` | 5 |
| `Apache-2.0 OR GNU Lesser General Public License version 3` | 2 |
| `EPL-1.0` | 1 |
| `BSD-4-Clause` | 1 |
| `Apache-2.0 OR LGPL-2.1-or-later` | 2 |
| `BSD-2-Clause OR CC0-1.0` | 1 |
| `CC0-1.0` | 1 |
| **Total** | **79** |

For full per-component detail (group, artifact, version, hashes, transitive deps), see [bom.json](bom.json)[1] or [licenses.html](licenses.html)[2].

## [#related](#related)Related

- [site index](index.html)[21]
- [ike-issues#336](https://github.com/IKE-Network/ike-issues/issues/336)[22] — the issue that introduced this page (rename of the legacy "Third-Party Notices" to friendlier "Built With").

# IKE Documentation Project Standards

## Parent Selection

All IKE projects inherit from `ike-parent` (the standard parent POM
in `network.ike.platform`). It provides Java 25 build conventions,
GPG signing via Bouncy Castle, JaCoCo, the AsciiDoc documentation
pipeline, and dependency version management for the IKE ecosystem.
There is no separate `java-parent`.

## Module shapes

`ike-parent`'s `doc-pipeline` profile auto-activates on
`<file><exists>src/docs/asciidoc</exists></file>`. The activation
is path-conditional, *not* packaging-conditional, so the same
mechanism handles three coherent module shapes:

| Module type | Packaging | Primary artifact | `adoc` classifier | Render classifiers |
|---|---|---|---|---|
| Doc-only (assembly, brief, topics) | `pom` | none | yes | yes (when renderers active) |
| Hybrid (Java + reference docs)     | `jar` | jar  | yes | yes (when renderers active) |
| Pure code (no `src/docs/asciidoc/`) | `jar` | jar  | no  | no |

The `adoc` classifier is the canonical *source* payload — a zip of
`src/docs/asciidoc/` attached via `maven-assembly-plugin`. The
renderer classifiers (`prince`, `fop`, `xep`, `pdf-default`,
`html-single`, etc.) are the canonical *rendered* payloads. Consumers
depend on whichever shape they need.

## Packaging

For **doc-only** modules use `<packaging>pom</packaging>`. The
`adoc` classifier is the deliverable; there is no primary artifact
to ship.

```xml
<packaging>pom</packaging>
```

For **hybrid** modules (Java module that also ships reference docs)
keep `<packaging>jar</packaging>`. The jar is the primary; the
`adoc` classifier attaches on top.

```xml
<packaging>jar</packaging>
```

Do **not** use `<packaging>jar</packaging>` for doc-only modules —
it produces an empty primary jar alongside the classifier, which is
the kind of inconsistent shape we deliberately avoid.

Do **not** use `<packaging>ike-doc</packaging>` — this custom
packaging type was retired in `IKE-Network/ike-issues#321`. It
forced `ike-doc-maven-plugin` and `ike-maven-plugin` to be loaded as
build extensions (`<extensions>true</extensions>`), which in turn
forced their `<version>` to be a literal everywhere they were
declared, which broke property-based version maintenance across the
release cascade. It also produced a primary `.zip` that no consumer
actually referenced — every dependency in the workspace was on the
`adoc` (formerly `asciidoc`) classifier attachment.

## Required Directory Structure

Minimum for a documentation project:

```
my-project/
├── pom.xml
└── src/
    └── docs/
        └── asciidoc/
            └── index.adoc
```

Optional additions:

```
src/docs/asciidoc/
├── index.adoc              # Master document
├── chapters/               # Modular chapter includes
│   ├── intro.adoc
│   └── architecture.adoc
└── .mermaid-config.json    # Mermaid diagram config (legacy; prefer PlantUML/GraphViz)
```

## Document Attributes

Standard attributes for the master document (`index.adoc`):

```asciidoc
= Document Title
:author: IKE Network
:revnumber: {project-version}
:revdate: {docdate}
:doctype: book
:toc: left
:toclevels: 3
:sectnums:
:icons: font
:source-highlighter: coderay
:experimental:
```

## Diagram Conventions

All diagrams are rendered server-side via Kroki. No local CLI tools needed.

- **PlantUML** (preferred): Standard `@startuml`/`@enduml` syntax. Clean SVG across all renderers.
- **GraphViz** (preferred): Standard `digraph`/`graph` syntax. Clean SVG across all renderers.
- **Mermaid** (discouraged): SVG uses `<foreignObject>`, which breaks in Prawn, WeasyPrint,
  and partially in FOP. Use only for HTML-only output or diagram types without PlantUML
  equivalents (Gantt, pie). If used, set `htmlLabels: false` in `.mermaid-config.json`.
- **Kroki server**: Default `https://kroki.komet.sh`, override with `-Dkroki.server.url=...`

For editorial guidance on **when** to include a diagram, **which engine** to
choose, style conventions, and renderer compatibility details, see `IKE-DIAGRAMS.md`.

## Koncept Macro Usage

Reference formally identified terminology with `k:Name[]`:

```asciidoc
The Koncept k:DiabetesMellitus[] is a metabolic disorder.
```

Koncept definitions are provided via YAML files in the
`koncept-asciidoc-extension` module.

## Theme Customization

The default IKE theme is provided by `ike-doc-resources` and unpacked
automatically. To override:

1. Create `src/theme/ike-default-theme.yml` in your project
2. Add to `<properties>` in your POM:

```xml
<asciidoc.theme.directory>${project.basedir}/src/theme</asciidoc.theme.directory>
```

## Build Commands

```bash
# HTML only (default):
mvn clean verify

# HTML + specific PDF renderer:
mvn clean verify -Dike.pdf.prawn
mvn clean verify -Dike.pdf.fop
mvn clean verify -Dike.pdf.prince
mvn clean verify -Dike.pdf.ah
mvn clean verify -Dike.pdf.weasyprint
mvn clean verify -Dike.pdf.xep

# Multiple renderers:
mvn clean verify -Dike.pdf.prawn -Dike.pdf.fop

# Self-contained HTML:
mvn clean verify -Dike.html.single
```

## Output Locations

| Format | Directory |
|--------|-----------|
| HTML | `target/generated-docs/html/` |
| Self-contained HTML | `target/generated-docs/html-single/` |
| Prawn PDF | `target/generated-docs/pdf-prawn/` |
| FOP PDF | `target/generated-docs/pdf-fop/` |
| Prince PDF | `target/generated-docs/pdf-prince/` |
| AH PDF | `target/generated-docs/pdf-ah/` |
| WeasyPrint PDF | `target/generated-docs/pdf-weasyprint/` |
| XEP PDF | `target/generated-docs/pdf-xep/` |
| Default PDF copy | `target/generated-docs/pdf/` |

## Renderer Capabilities

The pipeline supports 6 PDF renderers and 2 HTML outputs. Each uses a
different Asciidoctor backend and rendering pipeline, which determines
what features are available.

### Renderer Overview

| Renderer | Backend | Pipeline | License |
|----------|---------|----------|---------|
| HTML | html5 | AsciiDoc → HTML | Free |
| HTML-Single | html5 | AsciiDoc → HTML (data-URI) | Free |
| Prawn | pdf | AsciiDoc → PDF (direct) | Free |
| FOP | docbook5 | AsciiDoc → DocBook → XSL-FO → PDF | Free |
| Prince | html5 | AsciiDoc → HTML → PDF | Commercial |
| AH | html5 | AsciiDoc → HTML → PDF | Commercial |
| WeasyPrint | html5 | AsciiDoc → HTML → PDF | Free |
| XEP | docbook5 | AsciiDoc → DocBook → XSL-FO → PDF | Commercial |

### Feature Support by Backend

Not all AsciiDoc features work identically across backends. The
Asciidoctor backend determines what is available.

| Feature | html5 | pdf (Prawn) | docbook5 |
|---------|:-----:|:-----------:|:--------:|
| `[index]` catalog | No | Yes | Yes |
| `indexterm:[]` captured | Anchors only | Full index | Full index |
| Koncept badges | SVG | Text-only | DocBook phrase |
| Glossary (Postprocessor) | Yes | No (crashes) | Yes |
| SVG diagrams (Kroki) | Full | PNG fallback | Full (with fixes) |
| Table of contents | Yes | Yes | Yes |
| Cross-references | Yes | Yes | Yes |
| Source highlighting | Rouge | Rouge | N/A (XSL-FO) |

**Renderers grouped by backend:**

- **html5**: HTML, HTML-Single, Prince, AH, WeasyPrint
- **pdf**: Prawn
- **docbook5**: FOP, XEP

### Known Limitations

**Index generation** — The `[index]` macro only produces a back-of-book
index with the `pdf` (Prawn) and `docbook5` (FOP/XEP) backends. The
`html5` backend captures `indexterm:[]` macros as hidden anchors but
does not generate the index catalog. This affects all HTML-based
renderers (HTML, Prince, AH, WeasyPrint). Use conditional inclusion
in assembly files:

```asciidoc
ifdef::backend-pdf,backend-docbook5[]
[index]
== Index
endif::[]
```

**Koncept badges** — The `k:Name[]` inline macro renders differently
per backend:
- html5: clickable SVG pill badges linking to glossary
- pdf (Prawn): text-only `K Label` (Prawn's HTML parser cannot render SVG)
- docbook5: `<phrase role="koncept">` styled by ike-fo.xsl

**Glossary Postprocessor** — Cannot be registered via SPI because it
crashes the Prawn backend (JRuby `PostprocessorProxy` TypeError). It is
registered per-execution in the asciidoctor-maven-plugin config for
html5 and docbook5 backends only.

**FOP SVG rendering** — Apache FOP uses Batik for SVG, which has
several limitations. The pipeline includes automated fixes (svgo +
antrun) for: missing `<rect>` dimensions, `orient="auto-start-reverse"`
on markers, `alignment-baseline="central"` (SVG2), and `fill:rgba()`
values. FOP also requires the `-r` (relaxed validation) flag.

**WeasyPrint SVG** — Cannot render `<foreignObject>` in SVGs, so
Mermaid diagrams use PNG format instead of SVG.

**Prawn SVG** — The `prawn-svg` library drops `<foreignObject>` from
Mermaid SVGs. Diagrams use PNG format instead of SVG.

## Creating a Standalone Doc Project

For a doc project in its own repository (outside the IKE reactor):

1. Ensure these artifacts are deployed to a shared repository:
   - `network.ike:ike-parent`
   - `network.ike:ike-doc-resources`
   - `network.ike:ike-build-standards`
   - `network.ike:minimal-fonts`
   - `network.ike:koncept-asciidoc-extension`

2. Use the POM template from `doc-example/README.md`

3. The `ike-doc-resources` JAR is unpacked automatically by `ike-parent`'s
   `maven-dependency-plugin` configuration — no `../` paths needed.

## Multi-Assembly Projects

When a project produces multiple documents (e.g., a compendium, an
architecture guide, and a developer guide), use a **topic library +
assembly module** pattern. Each assembly produces an independent PDF
artifact in one reactor build.

### Topic Library (`topics/`)

Every doc multi-module project has a module named `topics/` with
artifact ID `topics`. The group ID carries project uniqueness. See
`IKE-INGEST.md` for the full standard project structure.

- **Directory**: always `topics/`
- **ArtifactId**: always `topics`
- **Packaging**: `pom` (no primary artifact; the `adoc` classifier
  is the deliverable)
- **Source**: `src/docs/asciidoc/topics/` with topic files, plus
  `index.adoc` for a browsable all-topics preview
- **Artifact**: publishes the `adoc` classifier ZIP of all sources
  (auto-attached by the inherited `doc-pipeline` profile)
- Does NOT render PDF — that is the assembly module's job

**POM template** (topic library):

```xml
<project xmlns="http://maven.apache.org/POM/4.1.0" ...>
    <modelVersion>4.1.0</modelVersion>
    <parent>
        <groupId>network.ike.platform</groupId>
        <artifactId>ike-parent</artifactId>
        <version>26</version>
        <relativePath/>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>topics</artifactId>
    <packaging>pom</packaging>
    <version>1.0.0-SNAPSHOT</version>

    <name>Topics</name>

    <dependencies>
        <dependency>
            <groupId>network.ike.docs</groupId>
            <artifactId>minimal-fonts</artifactId>
        </dependency>
    </dependencies>
</project>
```

The `<build>` block is empty by design — every plugin needed for
the doc pipeline is in `ike-parent`'s `<pluginManagement>` and
auto-activates via the path-conditional `doc-pipeline` profile.
Re-declaring `maven-dependency-plugin` or `asciidoctor-maven-plugin`
in the module POM is redundant.

### Assembly Module

An assembly module composes topics from one or more topic libraries
into a single document:

- **Packaging**: `pom`
- **Source**: `src/docs/asciidoc/` with the assembly `.adoc` file
- **Dependencies**: one or more topic library `adoc`-classified ZIPs
- **Unpack**: `ike-parent` automatically unpacks `adoc`-classified ZIPs to
  `target/generated-sources/asciidoc/{artifactId}-adoc/`
- **Includes**: use AsciiDoc attributes to reference unpacked topics

**POM template** (assembly module):

```xml
<project xmlns="http://maven.apache.org/POM/4.1.0" ...>
    <modelVersion>4.1.0</modelVersion>
    <parent>
        <groupId>network.ike.platform</groupId>
        <artifactId>ike-parent</artifactId>
        <version>26</version>
        <relativePath/>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>my-compendium</artifactId>
    <packaging>pom</packaging>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <pdf.source.document>compendium</pdf.source.document>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>topics</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <classifier>adoc</classifier>
            <type>zip</type>
        </dependency>
        <dependency>
            <groupId>network.ike.docs</groupId>
            <artifactId>minimal-fonts</artifactId>
        </dependency>
    </dependencies>
</project>
```

Hybrid modules (Java code that also ships reference docs) follow
the same dependency pattern but keep `<packaging>jar</packaging>`
and add Java sources under `src/main/java/`. The `adoc` classifier
auto-attaches whenever `src/docs/asciidoc/` exists; the jar primary
is unchanged.

### Include Path Resolution

In assembly `.adoc` files, use the `{generated}` attribute (provided by
`ike-parent`'s asciidoctor-maven-plugin config) to reference unpacked
topic libraries:

```asciidoc
:topics: {generated}/topics-adoc

== Developer Guide
include::{topics}/topics/dev/overview.adoc[leveloffset=+2]
```

The `leveloffset` value depends on the containing heading level — see
`IKE-ASCIIDOC-FRAGMENT.md` for the full table. Since all IKE assemblies
use `:doctype: book` with `==` chapter headings, topic includes under a
chapter need `leveloffset=+2` (not `+1`).

The attribute name matches the topic library's artifact ID. Since every
doc project uses `topics` as the standard artifact ID, this attribute
is always `:topics:`.

### IDE Preview with `.asciidoctorconfig`

Create `.asciidoctorconfig` in each assembly module root so IntelliJ
and VS Code resolve includes without a Maven build:

```
:generated: {asciidoctorconfigdir}/target/generated-sources/asciidoc
```

After one `mvn generate-sources`, the IDE resolves all includes, shows
previews, and provides file completion.

### Aggregator POM

The top-level POM uses `pom` packaging and lists subprojects:

```xml
<project xmlns="http://maven.apache.org/POM/4.1.0" ...>
    <modelVersion>4.1.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>my-documents</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <subprojects>
        <subproject>topics</subproject>
        <subproject>my-compendium</subproject>
        <subproject>my-guide</subproject>
    </subprojects>
</project>
```

### Build Commands

```bash
# IDE setup (unpack dependencies for preview):
mvn generate-sources

# All assemblies, HTML only:
mvn clean verify

# All assemblies with PDF:
mvn clean verify -Dike.pdf.prawn

# Single assembly with PDF:
mvn clean verify -pl my-compendium -am -Dike.pdf.prawn
```

### Cross-Project Composition

A future assembly can pull from multiple topic libraries across repos:

```asciidoc
:arch-topics: {generated}/arch-topics-adoc
:clinical-topics: {generated}/clinical-topics-adoc

include::{arch-topics}/topics/arch/design-lineage.adoc[leveloffset=+1]
include::{clinical-topics}/topics/clinical/workflow.adoc[leveloffset=+1]
```

Each `.asciidoctorconfig` defines the attributes for its own dependencies.
Attribute names are stable; values are project-local.

## Design rationale — why classifier, not custom packaging

The shape above (path-conditional activation, `pom` packaging for
doc-only modules, `adoc` classifier as canonical source coordinate)
is the result of a deliberate architectural decision documented in
`IKE-Network/ike-issues#321` and the `dev-classifier-canonical-doc-shape`
topic in `ike-lab-documents/topics/`. Brief summary, since the issue
will eventually close and this standard is what surviving teams
will land on:

**Why not `<packaging>ike-doc</packaging>`?** The custom packaging
type required `ike-doc-maven-plugin` to be loaded as a build
extension (`<extensions>true</extensions>`). Maven resolves
extensions at project-load time, before property interpolation,
which forces the plugin's `<version>` to be a literal string
everywhere it's declared. Across our cross-repo cascade
(`ike-tooling` -> `ike-docs` -> `ike-platform` -> consumers) this
defeated `ws:align-publish` and routine version-property
maintenance, surfaced as repeated stale-literal failures
(`#236`), and required a duplicate primary artifact that no
consumer actually referenced (every dependency was on the
classifier attachment, not the primary `.zip`).

**Why not Spring's gh-pages model?** Examined point-by-point, the
Spring rationale (continuous publication, search-driven discovery,
contributor friction, CDN economics) all have Maven-snapshot-plus-
post-deploy-rsync solutions. The genuinely Spring-specific
arguments — Antora as a turnkey product, ASF organizational policy,
path dependency from a 2003-04 codebase — do not apply to our
context. Our standing commitment to artifact uniformity (every
output goes through one pipeline, with consistent signing,
distribution, build lifecycle, and version control), combined with
a cross-repo doc-dependency graph and an existing rendering
pipeline, makes the all-Maven model the rigorous choice. Classifier-
canonical *is* all-Maven — the artifacts deploy to Nexus, signed
and versioned, just like every other output of the project. We do
not want one class of artifact (documentation) operating under
different rules than another (code).

**Why not custom packaging for doc-only and classifier for
hybrids?** That introduces two coordinate formats for the same
conceptual artifact (`<type>ike-doc</type>` vs.
`<classifier>adoc</classifier>`), forcing consumers to know which
form to use depending on what kind of module produced it. One
mechanism that handles both is structurally simpler.

**Why classifier name `adoc`?** Aligns with the `.adoc` file
extension (the AsciiDoc community's chosen short form), avoids the
overloaded `doc` (could mean rendered HTML, PDF, asciidoc source,
DocBook, Markdown, plain text), and reads cleanly alongside the
renderer classifiers (`prince`, `fop`, `pdf-default`, etc.).

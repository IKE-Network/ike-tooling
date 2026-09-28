# IKE Document Ingestion Standards

## Purpose

Ingestion is the process of importing an existing document into an IKE
documentation project by decomposing it into reusable topics, indexing
them, and wiring up an assembly that reconstructs the original document.

The target project already exists. Ingestion populates it with content.

## Prerequisites

- A target documentation multi-module project (the "into" project)
- A source document to ingest
- Familiarity with:
  - `IKE-TOPIC-DECOMPOSITION.md` — decomposition rules and granularity
  - `IKE-TOPIC-REGISTRY.md` — registry schema and maintenance
  - `IKE-ASCIIDOC-FRAGMENT.md` — fragment authoring conventions
  - `IKE-ASSEMBLY.md` — assembly document conventions
  - `IKE-INDEX.md` — index term authoring
  - `IKE-DEX-INGEST.md` — FDA 510(k) decision summaries (DeX records)

## Standard Project Structure

Every documentation multi-module project follows this layout:

```
{project}/                          # reactor POM (packaging: pom)
├── topics/                         # STANDARD name — always "topics"
│   ├── pom.xml                     #   artifactId: topics
│   └── src/docs/asciidoc/
│       ├── index.adoc              #   all-topics HTML preview
│       └── topics/                 #   topic fragments by domain
│           ├── {domain}/
│           │   └── {topic}.adoc
│           └── {domain}/
│               └── {topic}.adoc
├── {assembly-name}/                # descriptive name (e.g., arch-guide)
│   ├── pom.xml
│   └── src/docs/asciidoc/
│       └── {assembly-name}.adoc    #   assembly master document
├── {another-assembly}/             # additional assemblies as needed
└── pom.xml
```

### The `topics` module convention

- **Directory**: always `topics/`
- **ArtifactId**: always `topics`
- **GroupId**: carries project uniqueness (e.g., `network.ike`)
- **Unpack location**: `target/generated-sources/asciidoc/topics-asciidoc/`
- **AsciiDoc attribute**: `:topics: {generated}/topics-asciidoc`
- **Include pattern**: `include::{topics}/topics/{domain}/{topic}.adoc[leveloffset=+1]`

This convention means:
- Every doc project has topics in the same predictable location
- Assembly modules always depend on `${project.groupId}:topics:asciidoc:zip`
- Cross-project topic consumption follows a uniform pattern where
  groupId identifies the source

### Assembly modules

Assembly modules get descriptive names reflecting their content:
`arch-guide`, `dev-guide`, `compendium`, `safety-report`, etc.
The compendium assembly is the validation target — every published
topic must appear in it.

## Ingestion Workflow

### Step 1: Import

Receive the source document.

**510(k) check.** Before anything else, test the first page for an
FDA 510(k) Substantial Equivalence Determination Decision Summary:
the headings `510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION` and
`DECISION SUMMARY`, plus a field `A. 510(k) Number:` matching
`K\d{6}`. If all three are present, stop here and ingest per
`IKE-DEX-INGEST.md` — the document is a DeX record and is not
decomposed. Otherwise continue.

Identify its structure:
- Heading hierarchy and section boundaries
- Content types (narrative, procedures, reference tables, diagrams)
- Cross-references and dependencies between sections
- Existing index terms or glossary entries

### Step 2: Normalize line structure

Apply semantic line breaks to the source before any structural editing.
Run the `semantic-linebreak` tool on every AsciiDoc file being ingested.

If the source is not yet AsciiDoc (e.g., DocBook, Markdown, HTML),
convert it to AsciiDoc first, then run the tool.

#### Invocation

The tool accepts individual files, multiple files, or entire
directories. When given a directory it walks recursively for `*.adoc`
files, skipping `target/` directories. AsciidoctorJ is initialized
once and reused across all files, so batch mode is significantly
faster than invoking per file.

**Batch — entire directory (recommended):**

```bash
# From the ike-docs reactor root:
mvn exec:java -pl semantic-linebreak \
  -Dexec.args="path/to/src/docs/asciidoc"
```

**Batch — multiple files:**

```bash
mvn exec:java -pl semantic-linebreak \
  -Dexec.args="chapter1.adoc chapter2.adoc chapter3.adoc"
```

**Single file:**

```bash
mvn exec:java -pl semantic-linebreak \
  -Dexec.args="path/to/source.adoc"
```

**Dry run — preview to stdout without modifying:**

```bash
mvn exec:java -pl semantic-linebreak \
  -Dexec.args="-n path/to/source.adoc"
```

**Direct Java invocation (outside reactor):**

```bash
java -jar semantic-linebreak/target/semantic-linebreak-*.jar \
  path/to/src/docs/asciidoc
```

All invocations modify files in-place by default. Use `-n` (dry run)
to preview changes to stdout, or `-o <file>` to write to a different
file (single-file mode only).

#### Why normalize before decomposition

Semantic line breaks place newlines at logical boundaries — sentences,
em-dashes, semicolons, colons, and comma+conjunction joints. This
normalization must happen **before** decomposition because:

- **Decomposition splits** become line-range operations instead of
  mid-line edits, eliminating copy-paste truncation errors.
- **Index term insertion** targets an exact line without disturbing
  adjacent sentences.
- **Subsequent edits** (xrefs, koncept macros, wording changes)
  produce minimal, reviewable diffs that touch only the affected line.
- **Granularity estimation** against the 500–5000 character bounds is
  easier when each line is a semantic unit.

The tool only modifies paragraph blocks identified by the AsciidoctorJ
AST. Source listings, diagrams, tables, and all other block types are
preserved unchanged.

#### Re-normalize after authoring

Run the tool again after Step 5 (Place) on any files authored or
substantially edited during ingestion — fragment files, assembly
documents, and updated `index.adoc` previews. This ensures all
committed AsciiDoc follows uniform line structure.

### Step 3: Decompose

Split the source into topic fragments per `IKE-TOPIC-DECOMPOSITION.md`:

1. Map sections to candidate topics with proposed IDs, types, and sizes.
2. Apply granularity rules (500-5000 chars, no partial sections).
3. Resolve cross-references to `xref:` macros using topic IDs.
4. Author each fragment per `IKE-ASCIIDOC-FRAGMENT.md` with index
   terms per `IKE-INDEX.md`.

### Step 4: Index

Record every topic's metadata in its own header per
`IKE-TOPIC-REGISTRY.md` § "Topic Header Attributes". The topic
registry is generated from these headers; never write registry YAML.

- Assign domain prefix, `:topic-id:`, `:topic-type:`,
  `:topic-keywords:`, and `:topic-summary:`.
- Run `mvn idoc:topic-registry -pl topics` and read
  `topics/target/topic-registry.yaml` to check for redundancy
  against existing topics.
- Resolve any overlaps before proceeding.

### Step 5: Place

Put topic files into the target project's `topics/` module:

1. Create domain directories under `topics/src/docs/asciidoc/topics/`
   if they don't exist.
2. Place each `.adoc` fragment in the appropriate domain directory.
3. Update `topics/src/docs/asciidoc/index.adoc` to include the new
   topics for the HTML preview.
4. Add the placed files to the topic registry:

   ```bash
   mvn -B idoc:topic-registry -pl topics \
     -Dike.topic-registry.add=src/docs/asciidoc/topics/{domain}/{topic}.adoc   # comma-separated for several
   ```

### Step 6: Assemble

Create or update an assembly in the target project:

1. If this is a new document, create an assembly module with a
   descriptive name and a POM that depends on `topics`.
2. Author the assembly `.adoc` file per `IKE-ASSEMBLY.md` with
   `include::` directives referencing the placed topics.
3. Add the new module to the reactor POM's `<subprojects>`.

### Step 7: Validate

Build and verify:

```bash
mvn clean verify
```

- All `include::` paths resolve.
- All `xref:` targets resolve.
- Heading levels render correctly with `leveloffset`.
- No content from the source document was lost.
- A full `mvn -B idoc:topic-registry -pl topics` run reports
  `0 findings`, and every new topic appears in
  `topics/target/topic-registry.yaml` at the path where it was placed.
- Every new topic appears in the compendium assembly.
- Every new topic is included in `topics/src/docs/asciidoc/index.adoc`
  (the all-topics preview). This ensures cross-topic `xref:` links
  resolve in the topics module build and do not produce "possible
  invalid reference" warnings.

## Dialog Ingestion

Dialogs (Socratic or dramatic dialogues) follow a simplified ingestion
workflow. They are **not decomposed** — the entire document becomes a
single topic. See `IKE-TOPIC-DECOMPOSITION.md` § "Dialog Topics."

### Dialog Ingestion Workflow

1. **Import**: Receive the source dialog document.
2. **Convert to fragment**: Strip document-level AsciiDoc attributes
   (`:doctype:`, `:toc:`, `:icons:`, etc.). Add topic metadata
   attributes (`:topic-id:`, `:topic-type: dialog`, etc.), the
   anchor, and a level-1 heading per `IKE-ASCIIDOC-FRAGMENT.md`.
   Preserve all dialog content — speakers, stage directions, and
   structure — intact.
3. **Add index terms**: Insert 5–15 index terms at points of first
   substantive discussion per `IKE-INDEX.md`.
4. **Place**: Put the single `.adoc` file in
   `topics/src/docs/asciidoc/topics/dialog/`.
5. **Register**: Use the `dialog` domain prefix for the topic id and
   set `:topic-notes:` in the header documenting that this is a dialog
   topic exempt from size bounds. Add the file to the topic registry
   per Step 5 of the standard workflow.
6. **Assemble**: Add the topic to the `dialogs` assembly and to the
   compendium. If a `dialogs` assembly module does not yet exist,
   create one following the assembly module template in `IKE-DOC.md`.
7. **Update reactor**: Add the `dialogs` assembly module to the
   reactor POM's `<subprojects>` if it is new.
8. **Validate**: Build and verify per Step 7 of the standard workflow.

## External Source Ingestion

External source ingestion imports publications, memoranda, regulatory
guidance, journal articles, and other third-party documents into the
topic library as curated reference material. External topics are
available for search and cross-referencing but are **never included in
assembly documents**.

### Content handling by source type

Different source types have fundamentally different intellectual
property constraints. The content handling strategy must match the
source type — there is no single "ingest" operation.

| Source type  | Subdirectory    | Content handling | Quotation rules |
|--------------|-----------------|------------------|-----------------|
| **Internal** | `ext/internal/` | Near-verbatim with semantic line breaks. The project has implicit permission to reproduce collaborator-authored content. Apply editorial cleanup (structure, headings, definition lists) but preserve the author's substance and wording. | Attribution by `:topic-citation:`. No quotation marks needed for inline use — the entire topic is attributed to the author. |
| **Regulatory** | `ext/regulatory/` | Verbatim for US federal government works (public domain). For non-federal regulatory documents (NSHE policy, state regulations, EU directives), check the specific publication's reuse terms. Apply semantic line breaks and structural markup. | Federal works: no restriction — cite for traceability, not copyright. Non-federal: quote short passages with attribution; summarize the rest. |
| **Standards** | `ext/standards/` | **Fair-use summary only.** Standards documents (HL7, ISO, IEC, DICOM) are copyrighted. Summarize structure, requirements, and key definitions in the project's voice. | Direct quotes must be short (1–2 sentences), in AsciiDoc quote blocks with attribution. Never reproduce tables, figures, or extended normative text. |
| **Literature** | `ext/literature/` | **Fair-use summary only.** Journal articles and conference papers are copyrighted. Summarize findings, methods, and conclusions in the project's voice. | Direct quotes must be short (1–2 sentences), in AsciiDoc quote blocks with attribution. Never reproduce figures, tables, or extended passages. |

#### Quotation markup for fair-use sources

When quoting copyrighted material (standards, literature), use
AsciiDoc quote blocks with explicit attribution:

```asciidoc
[quote, "Author Name, Document Title (Year)"]
____
The quoted sentence goes here, exactly as published.
____
```

For inline quotations shorter than one sentence, use standard
quotation marks with a parenthetical citation:

```asciidoc
The specification defines this as "a formal representation
of clinical meaning" (Author, Title, Year).
```

### Mandatory confirmation step

**Before processing any external source, Claude must pause and
confirm with the user:**

1. **Source type classification** — which of the four types
   (internal, regulatory, standards, literature) applies, and why.
2. **Content handling strategy** — verbatim with line breaks,
   verbatim with structural cleanup, or fair-use summary.
3. **Quotation approach** — whether direct quotation is planned
   and how it will be attributed.
4. **License note** — the proposed `:topic-license:` value.

Example confirmation:

> This appears to be a **literature** source (journal article with
> DOI, published in JAMIA). I will create a **fair-use summary** in
> the project's voice — findings, methods, and conclusions only.
> Direct quotes will be limited to key definitions, in quote blocks
> with full attribution. License: `Fair use summary of copyrighted
> work — not for redistribution.`
>
> Proceed?

Do not begin content processing until the user confirms. The user
may reclassify the source or adjust the handling strategy.

### External source ingestion workflow

#### Step 1: Import and classify

Receive the source document. Apply the 510(k) check from the
standard workflow's Step 1; a decision summary is ingested per
`IKE-DEX-INGEST.md`, not this workflow. Otherwise identify the
source type per the content handling matrix above. Present the mandatory confirmation
to the user before proceeding.

#### Step 2: Convert and normalize

Convert to AsciiDoc if necessary (from `.docx`, `.pdf`, Markdown,
HTML, etc.). Apply semantic line breaks using the `semantic-linebreak`
tool, exactly as in standard ingestion. External topics follow the
same line structure conventions as authored topics.

#### Step 3: Process content per source type

Apply the content handling strategy confirmed in Step 1:

- **Internal**: Convert to AsciiDoc fragment structure. Apply
  semantic line breaks, add headings and definition lists for
  scannability, but preserve the author's substance and wording.
- **Regulatory (public domain)**: Convert to AsciiDoc fragment
  structure. Preserve the regulatory text verbatim with semantic
  line breaks and structural markup. Add editorial headings for
  navigation if the original lacks them.
- **Regulatory (non-federal)**: Check reuse terms. Summarize
  where reproduction is not permitted; quote short passages with
  attribution where it is.
- **Standards / Literature**: Write a curated summary in the
  project's voice. Capture findings, structure, requirements, and
  key definitions. Use quote blocks for any direct quotation.
  Never reproduce tables, figures, or extended normative text.

#### Step 4: Add provenance attributes

Every external topic must include three attributes in its header
block, in addition to the standard topic attributes:

```asciidoc
:topic-provenance: external
:topic-citation: {full bibliographic citation}
:topic-license: {rights/permissions note}
```

| Attribute             | Required | Description |
|-----------------------|----------|-------------|
| `:topic-provenance:`  | Yes      | Always `external` for this workflow. |
| `:topic-citation:`    | Yes      | Full citation: author, title, date, publisher, DOI/URL if applicable. |
| `:topic-license:`     | Yes      | One of: `Internal use — project collaborator content.` / `Public domain — US federal government work.` / `Fair use summary of copyrighted work — not for redistribution.` / or a specific license if known. |

#### Step 5: Add index terms and diagrams

External topics receive the same editorial treatment as authored
topics:

- **Index terms**: 3–10 per topic, per `IKE-INDEX.md`.
- **Diagrams**: Apply the Diagram Test from `IKE-DIAGRAMS.md`. If
  the source's structure, relationships, or processes benefit from
  visual representation, add inline PlantUML or GraphViz diagrams.
  These are original illustrations of the source's content — not
  reproductions of copyrighted figures.

#### Step 6: Place

Place external topic files under the appropriate subdirectory of
`topics/src/docs/asciidoc/topics/ext/`:

```
topics/ext/
├── internal/       # unpublished memoranda, partner correspondence
├── regulatory/     # FDA guidance, EU MDR/AI Act, NSHE policy
├── literature/     # journal articles, conference papers
└── standards/      # HL7, ISO, DICOM, IHE publications
```

#### Step 7: Register

Give the topic an `ext-` id so it lands in the `ext` domain, and set
its registry metadata in the header:

- Use `:topic-status: review` as the ceiling — external topics are
  never `published` because they are never included in assemblies.
- Set `:topic-notes:` documenting the content handling strategy
  that was applied (e.g., `Fair use summary — no verbatim
  reproduction.` or `Near-verbatim — internal collaborator
  content with implicit permission.`).
- Set bidirectional `:topic-related:` links: list the authored topics
  that reference or were informed by this source, and add this
  topic's id to each of their `:topic-related:` attributes.

Then add the placed file, and any authored topic whose header you
changed, to the topic registry:

```bash
mvn -B idoc:topic-registry -pl topics \
  -Dike.topic-registry.add=src/docs/asciidoc/topics/ext/{type}/{topic}.adoc
```

#### Step 8: Update index.adoc and validate

Add the new topic to `topics/src/docs/asciidoc/index.adoc` so it
renders in the topic library HTML preview and `xref:` links resolve.

Build and verify:

```bash
mvn clean verify -pl topics -am
```

#### Step 9: Re-normalize

Run the `semantic-linebreak` tool on the placed file to ensure
uniform line structure after any manual editing during Steps 3–5.

### Assembly exclusion rule

External topics (`ext/` domain) must not appear in any assembly's
`include::` directives. Authored topics may cross-reference external topics using
`xref:`:

```asciidoc
For details on the three-entity recommendation,
see xref:ext-plh-open-questions-answers[PLH's answers].
```

This renders as a link in the topic library HTML preview. In
assemblies that do not include the external topic, the `xref:` will
produce a warning — use a conditional include guard if needed:

```asciidoc
ifdef::ext-plh-open-questions-answers[]
See xref:ext-plh-open-questions-answers[PLH's answers] for details.
endif::[]
ifndef::ext-plh-open-questions-answers[]
See PLH's 2026-03-11 memorandum for details.
endif::[]
```

## Ingestion into an Existing Corpus

When the target project already has topics, follow the integration
workflow from `IKE-TOPIC-DECOMPOSITION.md` § "Topic Integration."
The additional constraint: search the generated topic registry
(`topics/target/topic-registry.yaml`) and term index
for overlap before placing any new topics. Resolve redundancy before
committing.

## Instructing Claude for Ingestion

Provide:

1. The source document (pasted, uploaded, or as a file path).
2. The target project path.
3. A directive such as:

> Ingest this document into {target-project} per IKE-INGEST standards.
> Use domain prefix `{prefix}`. Create assembly module `{name}`.

Claude should:

1. Run the 510(k) check. If the source is a decision summary,
   switch to `IKE-DEX-INGEST.md` and say so.
2. Run `mvn -B idoc:topic-registry -pl topics` in the target project
   and read `topics/target/topic-registry.yaml`.
3. Decompose the source document into topics.
4. Check for redundancy against existing topics.
5. Place topic files in `topics/src/docs/asciidoc/topics/{domain}/`.
6. Add the placed files to the topic registry with
   `-Dike.topic-registry.add`.
7. Create or update the assembly module.
8. Build and verify, finishing with a full topic registry scan that
   reports `0 findings`.

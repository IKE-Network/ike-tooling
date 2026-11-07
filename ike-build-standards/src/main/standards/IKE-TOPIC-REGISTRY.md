# IKE Topic Registry Standards

## Purpose

The topic registry is the catalog of every AsciiDoc file in a documentation module:
each topic with its metadata, each assembly, and every other file. It is **generated** by the
`idoc:topic-registry` goal from the files themselves. Nobody writes or edits it by hand.

The metadata lives in each topic's `:topic-*:` header attributes (see
`IKE-ASCIIDOC-FRAGMENT.md`). The topic file is the single source of truth; the registry is a
derived view of all headers at once. It serves three functions:

1. **Build validation**: every build checks each topic header and reports findings (missing
   required attribute, unknown type or status, anchor mismatch, duplicate id).
2. **Assembly planning**: authors and tooling use the registry to see what content exists,
   its status, and its dependencies when constructing assembly documents.
3. **Claude navigation**: the registry gives Claude (chat or Claude Code) a searchable index
   of the corpus, so content can be located by keyword, topic-id, or domain without reading
   every topic file.

## File Location

```
{module}/target/.../META-INF/ike/{group}/{artifact}/topic-registry.yaml
```

The registry is written under `target/`, in the library's reserved `META-INF` area, keyed by
its coordinates and beside the manifest and the term index. `IKE-INGEST.md` § "The manifest
and the `META-INF` area" describes the area. The `adoc` ZIP ships the area at that same relative path,
so a consumer of the library reads the registry from the union without scanning a file, and
the union's catalog is the shipped registries concatenated. The registry is never committed
and is brought up to date on every run.

## Keyed by content, not by date

A checkout, a rebase, a sync between machines or a restored file all change dates without
changing content, so dates are the wrong key for staleness. Git already addresses content:
one call, `git ls-files -s`, lists every path with the blob id of its content without opening
a file. Each registry entry records the blob it was parsed from, and the rule is exact:

- same path and same blob: the entry is kept;
- same blob at a new path, a rename: the entry is re-keyed without parsing;
- a new or changed blob: that file is parsed;
- a path gone: the entry is dropped.

Files that are not topics get an entry too, with `topic: false` and their blob, so they are
not opened again either. An unstaged edit shows in the status and is hashed on the spot,
which costs the same as the parse it causes. A tree with no git falls back to the full scan.

**Legacy hand-kept registries.** Projects created before this standard may still have
`src/docs/asciidoc/topic-registry.yaml` (and a `topic-registry/` directory of per-domain
files). It is no longer maintained, and no goal reads it: `idoc:topic-registry` and
`idoc:diff` both generate the registry from the headers. Move any `summary`, `related`,
`dependencies`, `supersedes`, and `notes` values it holds into the matching topic headers,
run the goal, confirm zero findings, then delete the hand-kept files.

## Generating the Registry

```bash
mvn idoc:topic-registry -pl topics                         # full scan of src/docs/asciidoc
mvn idoc:topic-registry -pl topics \
  -Dike.topic-registry.add=src/docs/asciidoc/topics/{domain}/{topic}.adoc   # add files, no rescan
```

- The goal defaults to the `validate` phase, reads sources only, and writes only under
  `target/`. It never edits a source file.
- `-Dike.topic-registry.add` takes comma-separated paths relative to the module directory.
  Each file is parsed alone and merged into the existing registry; an entry for the same file
  is replaced, so re-adding is safe. When no registry exists yet, the full scan runs instead.
- `add` does not notice deleted or moved files. After any delete or move, run the full scan;
  the full scan is the one to trust.
- Findings are warnings: they are printed to the log and listed in the registry's `findings`,
  and they do not fail the build. Fix each one in the source file and run again. Work is not
  done until the run reports `0 findings`.
- Other options: `-Dike.topic-registry.roots=a,b` (scan other directories),
  `-Dike.topic-registry.output=<file>` (write elsewhere), `-Dike.skip.topic-registry=true`.

## Schema

The generated file has this shape (values abbreviated):

```yaml
registry-version: "1.2"
generated: 2026-09-28T19:39:06Z     # timestamp of this run
scanned-from: /path/to/module
roots: [src/docs/asciidoc]
topic-count: 247
file-count: 251

domains:                             # grouped by the id prefix before the first hyphen
  - id: arch
    topics:
      - id: arch-dl-classifier
        file: network.ike.lab/arch/dl-classifier.adoc
        blob: 9f2c1e0a...                         # the git blob id the entry was parsed from
        title: "Classifier Architecture"          # from the level-1 heading
        type: concept
        keywords: [classifier, reasoning, EL++, inference]
        status: published
        char-count: 2890                          # counted from the anchor line on
        dependencies: [arch-overview]
        related: [term-dl-axioms]
        summary: >
          Describes the classifier subsystem architecture including integration
          points, performance characteristics, and the EL++ profile constraints
          that enable polynomial-time reasoning.
        anchor: present
        header: complete                          # or "missing: status, keywords"

assemblies:                          # files with include:: directives and no :topic-id:
  - file: compendium.adoc
    title: "IKE Compendium"
    includes: 247

other-files:                         # every other file under the root, with its blob
  - file: network.ike.lab/lab/index.adoc
    blob: 4b7d...
    topic: false
findings: []                         # one line per problem, prefixed with the file path
```

A topic's `file` is its package path, `{group}/{domain}/{topic}.adoc`, the same path an
include names after `{adoc}`. A topic entry also carries `supersedes`, `notes`, `provenance`, `scope-note`, `citation`, and
`license` when the header sets them. Any other `:topic-*:` attribute appears under `extra`,
and non-topic document attributes under `attributes`.

## Topic Header Attributes

Every registry field of a topic comes from its header. Write the header, never the registry.

### Required

A missing required attribute is a finding.

| Attribute         | Description                                                  |
|-------------------|--------------------------------------------------------------|
| `:topic-id:`      | Unique topic identifier. Format: `{domain-prefix}-{slug}`, lowercase kebab-case. Immutable once assigned. Must match the `[[anchor]]` before the heading. |
| `:topic-type:`    | One of: `concept`, `task`, `procedure`, `reference`, `dialog`. |
| `:topic-status:`  | One of: `draft`, `proposed`, `review`, `published`, `deprecated`. |
| `:topic-keywords:`| Comma-separated, 3–8 searchable terms. See Keyword Guidelines. |

The title is the level-1 heading (`= Title`); a missing heading is a finding. The file path and
`char-count` are measured by the goal.

### Expected

| Attribute             | Description                                                  |
|-----------------------|--------------------------------------------------------------|
| `:topic-summary:`     | 1–3 sentences describing the content. See Summary Guidelines. Not checked by the goal, but every topic should have one: it is the main search and redundancy signal. |

### Optional

| Attribute               | Description                                                  |
|-------------------------|--------------------------------------------------------------|
| `:topic-dependencies:`  | Comma-separated `topic-id` values this topic cross-references via `xref:`. |
| `:topic-related:`       | Comma-separated `topic-id` values covering similar subject matter from a different angle. Keep it bidirectional: if A lists B, B lists A. Distinct from `dependencies`, which are structural cross-references. |
| `:topic-supersedes:`    | `topic-id` of a deprecated topic this topic replaces.        |
| `:topic-notes:`         | Free-text notes for authors and Claude. Use for documenting exceptions (e.g., "Exceeds 5000 chars — indivisible reference table"). |
| `:topic-scope-note:`    | What the topic covers and where related material lives.     |
| `:topic-provenance:`, `:topic-citation:`, `:topic-license:` | Required for external sources; see `IKE-INGEST.md`. |

A long value continues onto the next line with a trailing ` \`:

```asciidoc
:topic-summary: Describes the classifier subsystem architecture including integration \
  points, performance characteristics, and the EL++ profile constraints.
```

## Assemblies

An assembly is recorded from its file: path, title, document attributes, and include count.
Its structure is the assembly file itself — the headings and `include::` directives in
document order. There is no hand-kept `sections` / `topic-refs` tree; read the assembly file
when you need its structure.

## Domains

A topic's domain is the part of its id before the first hyphen. Domains exist because topics
use the prefix; there is no domain declaration. Describe a domain's scope in the project's
`CLAUDE.md` or topic-library `index.adoc` if it needs explanation.

## Topic ID Construction Rules

1. Format: `{domain-prefix}-{descriptive-slug}`
2. Domain prefix: 2–5 lowercase characters. The prefix alone determines the domain, so use
   the same prefix for every topic in a domain.
3. Slug: lowercase kebab-case, 2–5 words, descriptive of content.
4. Total length: aim for under 40 characters.
5. **Immutability**: Once a `topic-id` is assigned and committed, it must not be changed. Other
   topics, assemblies, and external documents may reference it. If a topic's scope changes
   substantially, create a new topic, set `:topic-status: deprecated` on the old one with a
   `:topic-notes:` pointing to the replacement, and set `:topic-supersedes:` on the new one.

Examples:
- `arch-coord-versioning` — architecture domain, describes coordinate-based versioning
- `term-snomed-concept-model` — terminology domain, SNOMED CT concept model
- `safe-usc-hazard-analysis` — safety domain, unsafe control action hazard analysis
- `ops-maven-release-process` — operations domain, Maven release procedure

## Status Lifecycle

```
draft → review → published
                     ↓
                deprecated

draft → proposed → review        (proposal adopted)
        proposed → deprecated    (proposal declined or superseded)
```

- **draft**: Content is being authored or decomposed. May contain TODOs and placeholders.
- **proposed**: Content-complete design proposal awaiting an adoption decision. Distinct from
  `draft` (content still being authored): a proposed topic is ready to read, but the approach
  it argues for has not been decided. On adoption, move to `review`; if declined or
  superseded, move to `deprecated`.
- **review**: Content is complete and awaiting technical review.
- **published**: Content is reviewed and approved for inclusion in assemblies.
- **deprecated**: Content is superseded or no longer applicable. Retained in the registry for
  reference stability but excluded from new assemblies. Set `supersedes` on the replacement
  topic if one exists (`:topic-supersedes:`).

## Keyword Guidelines

Keywords are the primary mechanism for Claude to locate topics by subject matter. Follow these
rules:

1. **3–8 keywords per topic.** Fewer is too sparse for search; more dilutes relevance.
2. **Include synonyms and abbreviations**: If the topic discusses "description logic," also
   include `DL` and `classifier`. If it covers SNOMED CT, include `SCT`.
3. **Do not repeat title words**: The title is already searchable. Keywords should expand
   coverage.
4. **Prefer specific terms over generic**: `stamp-coordinate` over `coordinate`;
   `el-profile` over `profile`.
5. **Include the names of key standards, systems, or specifications** referenced in the topic.

## Summary Guidelines

Summaries serve triple duty: human-readable abstracts, Claude search targets, and redundancy
detection signals. They are the primary mechanism by which Claude identifies content overlap
across sessions. Invest effort in making them specific and term-rich.

1. Be 1–3 sentences, 150–400 characters. This is longer than a typical abstract — the extra
   space is needed for the technical terms that drive redundancy detection.
2. Use indicative mood: "Describes the coordinate-based versioning pattern..." not "This topic
   describes..."
3. Include 3–5 key technical terms not already in `keywords` or `title`. Prioritize terms
   that would help identify overlap with other topics — the specific standards, formalisms,
   patterns, and domain concepts discussed in the body.
4. Mention the *angle* or *perspective* of the topic when relevant: "from the terminology
   authoring perspective" or "focusing on build-time validation." This helps distinguish
   intentionally overlapping topics.
5. Be specific enough that a reader (or Claude) can determine relevance and potential overlap
   without opening the file.

Bad: "Covers versioning." (too vague, no technical terms, useless for redundancy detection)

Bad: "Describes coordinate-based versioning." (marginally better but still lacks the specific
terms that would trigger overlap detection)

Good: "Describes the coordinate-based versioning pattern where each component version is
identified by module, path, and temporal coordinates within the STAMP model. Covers the
relationship between coordinates and the version graph used for dependency resolution."

## Maintenance Rules

### When to Run

Run `idoc:topic-registry` whenever:

- A topic is created, modified, split, merged, moved, or deprecated (full scan after any
  move, delete, split, or merge; `add` is enough for new or edited files).
- A topic's status or header metadata changes.
- An assembly's include list changes.
- A decomposition or ingestion session produces new topics.

When the goal is bound in the build, `mvn validate` and every later phase refresh the
registry automatically.

### Who Updates

- **Claude (chat or Claude Code)**: writes and updates topic headers as part of decomposition,
  ingestion, or topic creation, then runs the goal and fixes every finding. Claude never
  produces registry YAML fragments and never edits a registry file.
- **Authors**: review header changes in the topic files and commit them. The registry itself
  is never committed.

### Validation

The goal checks, and reports as findings:

1. Every topic has `:topic-id:`, `:topic-type:`, `:topic-status:`, and `:topic-keywords:`.
2. `type` and `status` are known values.
3. A literal `[[id]]` anchor matching `:topic-id:` precedes a level-1 heading.
4. No `topic-id` appears in two files.
5. Symbolic links are skipped and reported.

With the package layout, which the pom turns on with `ike.adoc.layout=packages`, the
validate-phase lint also checks
that every package begins with the library's own group unless the pom declares another prefix,
that a topic's directory equals its id's domain prefix, and that the root holds nothing but
group directories and `META-INF`.

`topic-count` and `char-count` are measured, so they cannot drift.

Not yet checked by the goal — check them during review, and Claude checks them before
reporting work complete:

1. Every topic has a `:topic-summary:`.
2. All `dependencies` and `related` values name existing topic ids, and `related` is
   bidirectional.
3. Every `include::` path in an assembly resolves (the build reports unresolved includes).
4. Every published topic appears in at least one assembly.

## Generated Artifact: term-index.yaml

The build produces `term-index.yaml` by collecting all `indexterm` and `((...))` entries from
topic `.adoc` files. This file is a generated artifact — it must not be hand-edited. See
`IKE-INDEX.md` for the full schema and authoring conventions.

### Purpose

The term index provides a reverse mapping from technical terms to topics. While the registry's
`keywords` and `summary` fields capture what a topic is *about*, the term index captures what
a topic *discusses*. This distinction matters for redundancy detection: two topics may have
different keywords but discuss the same underlying concepts.

### Location

```
{module}/target/.../META-INF/ike/{group}/{artifact}/term-index.yaml
```

The term index is generated during the build beside the registry and the manifest, in the
library's `META-INF` area. It is not committed to source control. The `adoc` ZIP ships it at
that same relative path, so dependent modules and Claude read it from the union after
unpacking.

### Build Integration

The doc plugin walks every `.adoc` file under the library's packages, extracts `indexterm`
macros and `((...))` inline index terms, and writes the YAML file with the registry, before
packaging.

## Working with Claude

### Providing Context

At the start of a session involving topic work, run the goal and give Claude the generated
`topic-registry.yaml` under `target/` (Claude Code reads it directly), plus the
`term-index.yaml` file beside it if the session involves integration or redundancy checking.
For a library a project depends on, both are in the union under
`target/generated-sources/adoc/META-INF/ike/{group}/{artifact}/`.

For a 600-page compendium decomposed into ~300 topics, the registry will be roughly 20–30 KB
of YAML and the term index roughly 10–15 KB — both well within context window limits.

Together, these give Claude a complete map of what exists (registry), where it sits
structurally (the assembly files), and what specific terms each topic discusses (term index).

### Requesting Topic Lookup

To find existing content without opening topic files:

> Which topics cover STAMP coordinates? (Check the topic registry.)

Claude will search the registry's `title`, `keywords`, and `summary` fields to identify
matching topics and report their `topic-id`, `title`, and `summary`.

### Requesting Registry Updates

After any topic creation or modification:

> Update the topic headers and rebuild the topic registry.

Claude edits the `:topic-*:` headers, runs `idoc:topic-registry` (with `add` for new or edited
files, a full scan after moves or deletes), and fixes findings until the run reports
`0 findings`.

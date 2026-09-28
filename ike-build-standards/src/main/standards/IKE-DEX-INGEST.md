# IKE DeX Record Ingestion Standards

## Purpose

Augments `IKE-INGEST.md` for one source type: the FDA **510(k)
Substantial Equivalence Determination Decision Summary**
(reference format: https://www.accessdata.fda.gov/cdrh_docs/reviews/K031739.pdf).
Such a document is ingested as a single, whole-document record (a
*DeX record*) named after its 510(k) number.

Everything not stated here is governed by `IKE-INGEST.md` § "External
Source Ingestion": prerequisites, project structure, the
`semantic-linebreak` tool, provenance attributes, the mandatory
confirmation step, validation, and the assembly exclusion rule. This
document lists only the differences.

## Detection (new step, before Step 1)

Apply this standard only when all three signals are on the first page
of the source:

1. The heading `510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION`
   (case-insensitive, whitespace-tolerant).
2. The heading `DECISION SUMMARY`, usually followed by a template name
   (`DEVICE AND INSTRUMENT TEMPLATE`, `ASSAY AND INSTRUMENT COMBINATION
   TEMPLATE`, `ASSAY ONLY TEMPLATE`).
3. A field `A. 510(k) Number:` whose value matches `K\d{6}`. This value
   is the *510(k) number*.

Any signal missing: not a DeX record. Use `IKE-INGEST.md` unchanged.
Do not apply the DeX shape to other FDA documents (510(k) summaries,
clearance letters, De Novo or PMA decisions).

## Differences from IKE-INGEST

| Concern | IKE-INGEST (external source) | IKE-DEX-INGEST |
|---------|------------------------------|----------------|
| Decomposition | Split into topics, 500–5000 chars | **None.** One record, whole document, size bounds exempt (note in registry, as for dialogs) |
| Source type | Classified per confirmation step | Fixed: regulatory, US federal, public domain, verbatim |
| Domain | `ext` | `dex` |
| Directory | `topics/ext/regulatory/` | `topics/dex/` |
| File name | `{slug}.adoc` | `DeXRecord_{510k-number}.adoc`, e.g. `DeXRecord_K031739.adoc` |
| Title | Descriptive | `DeXRecord_{510k-number}` |
| Topic ID | `ext-{slug}` | `dex-{510k-number lowercase}`, e.g. `dex-k031739` (registry requires lowercase kebab-case; derived from the file name) |
| Layout | Fragment conventions | **Mirrors the PDF.** Title block, lettered headings with their punctuation, numbered and lettered sub-fields as lists, tables in the source's shape |
| Editorial context paragraph | Added for navigation | **Not added.** The registry `summary` is the abstract |
| Index terms | 3–10 | 5–15, at first substantive mention |
| Uniqueness | Redundancy check against registry | **One record per 510(k) number.** Existing `dex-{number}`: stop and ask before replacing |
| `index.adoc` heading | `== External Sources: Regulatory` | `== DeX Records` |
| Citation | Bibliographic | Same, plus the `accessdata.fda.gov` PDF URL |

### Layout: mirror the PDF

The record should read like the PDF. Keep the source's wording,
punctuation, capitalisation, emphasis, and ordering; use AsciiDoc only
to reproduce its shape.

- **Title block**: the centred three-line heading from page 1, as one
  centred paragraph of bold lines joined by hard breaks:

  ```asciidoc
  [.text-center]
  *510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION* +
  *DECISION SUMMARY* +
  *DEVICE AND INSTRUMENT TEMPLATE*
  ```

- **Lettered sections (A–P)**: level-2 headings, in source order, letter
  and title verbatim including the trailing colon or period the source
  uses (`== A. 510(k) Number:`, `== H. Device Description`,
  `== O. ... Decision Summary.`). Never merge, reorder, rename, or add.
  The Device and Instrument Template carries:

  ```
  A. 510(k) Number                         I. Substantial Equivalence Information
  B. Analyte                               J. Standard/Guidance Document Referenced
  C. Type of Test                          K. Test Principle
  D. Applicant                             L. Performance Characteristics
  E. Proprietary and Established Names     M. Instrument Name
  F. Regulatory Information                N. System Descriptions
  G. Intended Use                          O. Other Supportive Instrument Performance
  H. Device Description                    P. Conclusion
  ```

  Other templates carry a subset or variant; keep whatever the source has.

- **Numbered sub-fields (1., 2., ...)**: an ordered list. The label is
  underlined as in the source, followed by a hard break, then the value:

  ```asciidoc
  . [.underline]#Regulation section:# +
  21 CFR §862.1215
  ```

  A blank field keeps its label and nothing else. Further paragraphs
  and tables attach with `+` list continuation.

- **Lettered sub-sub-fields (a., b., ...)**: a nested `[loweralpha]`
  list with the label in italics:

  ```asciidoc
  . [.underline]#Analytical performance:#
  [loweralpha]
  .. _Precision/Reproducibility:_ +
  Three levels of plasma controls ...
  ```

- **Tables**: one AsciiDoc table per source table, same columns and
  headings, numeric columns centred (`^`). A source table with band
  rows (Similarities / Differences) is one table with spanning header
  cells (`3+^h| Similarities`), not two tables. A caption above a
  source table is a centred bold paragraph, not a `.Title`, so no
  `Table N.` prefix is added. Row-spanning cells use `.3+|`.

- **Forms**: a checkbox line is reproduced with a passthrough,
  `Yes +____X____+ or No +________+`, with a comment noting which box
  the source marks.

- **Emphasis**: keep the source's italics (guidance titles, _in vitro_).

- **Cleanup**: fix PDF extraction artifacts only (broken words, glyph
  substitutions, `Page 2 of 8` headers). Never correct FDA wording,
  spelling, or grammar.

- **Index terms**: inside the paragraph or list item they describe,
  never on the line before a list item (`IKE-INDEX.md`).

### Header block

```asciidoc
// dex-k031739
// Topic: DeXRecord_K031739
// Type: reference
// Status: review
:topic-id: dex-k031739
:topic-type: reference
:topic-status: review
:topic-keywords: 510(k), K031739, {analyte}, {device}, substantial equivalence, {product code}
:topic-scope-note: Whole-document DeX record for K031739. Not decomposed.
:topic-provenance: external
:topic-citation: U.S. Food and Drug Administration, Center for Devices and Radiological Health. 510(k) Substantial Equivalence Determination Decision Summary, {Template Name}: K031739, {Device Name}. Applicant: {Applicant}. https://www.accessdata.fda.gov/cdrh_docs/reviews/K031739.pdf
:topic-license: Public domain — US federal government work.

[[dex-k031739]]
= DeXRecord_K031739

// Editorial: all content below is verbatim from the FDA decision summary; layout mirrors the PDF.

[.text-center]
*510(k) SUBSTANTIAL EQUIVALENCE DETERMINATION* +
*DECISION SUMMARY* +
*DEVICE AND INSTRUMENT TEMPLATE*

== A. 510(k) Number:

K031739
```

### Registry domain

Create once, then append one topic per record:

```yaml
  - id: dex
    title: "DeX Records"
    description: >
      Whole-document FDA 510(k) Substantial Equivalence Determination
      Decision Summaries, one record per 510(k) number. Never decomposed;
      never included in assemblies.
    topics:
      - id: dex-k031739
        file: topics/dex/DeXRecord_K031739.adoc
        title: "DeXRecord_K031739"
        type: reference
        status: review
        related: [ext-fda-k031739-device-overview, ext-fda-k031739-performance, ext-fda-k031739-instrument-system]
        notes: >
          DeX record — whole document, verbatim, exempt from size bounds
          per IKE-DEX-INGEST. Public domain US federal work.
```

Decomposed topics for the same 510(k) number may coexist. Link them
both ways through `related:`.

### Confirmation text

Pre-fill the `IKE-INGEST.md` mandatory confirmation:

> Detected a **510(k) Decision Summary** ({Template Name}) for
> **K031739**, {Device Name}, applicant {Applicant}.
> Handling: verbatim, whole document, one record —
> `topics/dex/DeXRecord_K031739.adoc`, topic ID `dex-k031739`.
> License: `Public domain — US federal government work.`
>
> Proceed?

### Added validation checks

- Every lettered section of the source appears once, in order.
- Rendered output reads in the same order and shape as the PDF:
  title block, sub-field numbering, table bands and captions.
- No page headers, footers, or extraction artifacts remain.
- File name, title, and topic ID agree with the 510(k) number.

## Instructing Claude

> Ingest this document into {target-project} per IKE-DEX-INGEST.

Claude runs Detection first. On failure it says so and proceeds under
`IKE-INGEST.md`. On success it follows `IKE-INGEST.md` § "External
source ingestion workflow" with the substitutions in this document.

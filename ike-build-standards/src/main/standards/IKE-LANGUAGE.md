# IKE Plain Language

How to write a concept definition, a design topic, a guide chapter, or a
proposal to the architect. The reader is a clinician who has not read the
code and will not. Every rule here was set by the architect in review; the
examples are the sentences that were rejected and what replaced them.

## Who this applies to

- Concept definitions in a starter-set ledger (`KnowledgeSet` sections).
- Design topics in `ike-lab-documents` and the starter-set guide.
- Proposals put to the architect for a yes or a no.
- Commit messages that explain a settlement.

## The shape of a definition

1. **Genus and differentia.** A definition names the parent and then the
   one thing that sets this concept apart. A parent is defined on its
   own, never by listing its children. A child restates the parent's
   essence and adds its own characteristic.
2. **Everyday form first.** Start from what a person already knows, then
   add the model's one twist, then the consequence a person reasons with.
   "An if-then-else needs a yes or a no. In this model a comparison can
   also come out Indeterminate, so the author has to say what happens
   then." Not: "A conditional chooses among branches of one kind by
   conditions."
3. **A real example early.** Name a descendant or a clinical case in the
   first two sentences: a hospital stay, an onset recorded as March 2024,
   an HbA1c of 8.5 to 9.5. An example is not decoration; it is where the
   reader checks the rule.
4. **One idea per sentence.** About twenty words, with a verb. A sentence
   beats a label with a colon.
5. **The model's words, in words.** Present, Absent, Indeterminate are
   written out. No interval notation, no `[x, y]`, no symbols to unpack.
   Indeterminate means a determination was performed and could not arrive
   at a value; unknown is not a value at all, it is the absence of any
   result.

## Nothing in the model speaks or acts

A semantic, a concept, a rule, a binding, a query, a set, or a pass is not
a person. It does not say, tell, decide, serve, rest on, carry, shape,
hand back, or live anywhere.

| Rejected | Why | Written instead |
|---|---|---|
| "What a range means is the semantic's to say" | a semantic is not vocal | "A measure whose semantic is a period on the calendar has bounds where it began and ended" |
| "because a concept says so" | same | "because a concept in the knowledge layer defines that combination" |
| "The question it serves is …" | a pass does not serve | "This pass is for one question: …" |
| "Every precision-based comparison rests on it" | metaphor | "Every precision-based comparison uses it" |
| "the value lies between" | values do not lie down | "the value is between" |
| "the binding says so" | same | "the binding records it as data" |

The one accepted use: a keyword names a construct, because a keyword is a
name. "The construct ECL's `<` names."

## Constructions to refuse

- **Undefined ordinals.** "A criterion that names a second set of
  statements" arrives before any first set. Say "this statement" and "a
  set of other statements".
- **Agentless jargon.** "The statement under test", "the operand",
  "predicate", "cell", "tuple", "monotone", "semi-join", "presupposes".
  Say what the thing is: "this statement", "none of them can go down when
  a member goes up".
- **Compression.** "Chooses among branches of one kind by conditions"
  packs four ideas into one clause. Unpack them.
- **Essay openers and hedges.** No "The question it serves", no "it is
  worth noting", no "importantly".
- **Em-dashes and parentheticals.** Use commas or a new sentence.
- **Metaphors of position or motion for values.** Nothing falls, lands,
  lies, sits, or is carried.

## Words that are not used

- **mapping, alignment, crosswalk** for relations between constructs. Say
  identity, logical equivalence, definitional extension, or conservative
  extension, each of which is gate-checked.
- **ontology, ontological.** Say knowledge layer, concept model, or the
  specific thing.
- **dirty** for a working tree. Say uncommitted or modified.
- **unknown** as a value. It is not one.
- **cycle, facet** and other reserved words listed in the terminology
  discipline for the project.

## Keywords and logics

- Quote a keyword and name its logic: CQL's `where`, ECL's `<`.
- Say which construct the keyword names and what the logic fixes without
  writing it down: "CQL's `if` is this with Present fixed as the outcome
  that takes the branch and never written down, and the binding records
  that as data."
- Say what stays unbound and why, in one clause each.
- HL7's ELM, the Expression Logical Model of the CQL specification, shares its
  name with Elm, a programming language for browser user interfaces. Expand
  the acronym once per document and add a footnote at that first mention
  saying it is not the other one:
  `footnote:[HL7's ELM, the Expression Logical Model of the Clinical Quality
  Language specification, and not Elm, the programming language for browser
  user interfaces.]`

## Proposals to the architect

- Numbered items, each one decision, each ending "Yes or no?".
- The recommendation first, then the alternatives in one sentence each.
- Counts on their own line, not inside prose.
- Wording for review before anything is applied to a ledger; the
  architect settles design and wording, never the writer.

## Self-review before sending

Read every sentence and ask:

1. Who is doing the verb, and can that thing do it?
2. Is there a "second" or "other" with no first in sight?
3. Does the paragraph start from the everyday form?
4. Is there an example a clinician would recognise in the first two
   sentences?
5. Is any word on the refused lists above?
6. Would the sentence survive being read aloud to a colleague?

A definition that fails one of these is rewritten before it is shown.

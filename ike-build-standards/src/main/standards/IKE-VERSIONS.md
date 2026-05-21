# IKE Version-Property Convention

This is the canonical convention for the property names that pin
Maven artifact versions in IKE Network POMs. Established in
IKE-Network/ike-issues#470, #471.

## The rule

For any version-pinning property in an IKE POM, the property name
MUST be the GA-encoded form:

> **`<groupId·artifactId>` &nbsp;==&nbsp; the property name**
> **`<version>` &nbsp;==&nbsp; the property value**

The structural separator between groupId and artifactId is U+00B7
MIDDLE DOT (`·`). Not a period (`.`), not a colon (`:`), not a
slash (`/`).

```xml
<properties>
  <network.ike.tooling·ike-tooling>194</network.ike.tooling·ike-tooling>
  <network.ike.docs·ike-docs>50</network.ike.docs·ike-docs>
  <org.junit.jupiter·junit-jupiter>6.0.0</org.junit.jupiter·junit-jupiter>
</properties>
```

The property name **is** the GA. The property value **is** its
version. No suffix, no decoration. The `·` itself is the signal
that the property pins a version.

A POM that consumes `network.ike.tooling:ike-tooling` references
the pin by its canonical name:

```xml
<dependency>
    <groupId>network.ike.tooling</groupId>
    <artifactId>ike-maven-plugin</artifactId>
    <version>${network.ike.tooling·ike-tooling}</version>
</dependency>
```

One contract, lint-checkable, mechanically derivable from the GA.

## Why `·` and not `.` or `:`

XML 1.0 element names allow letters, digits, hyphens, underscores,
periods, and Extender characters. `.` is already heavily used in
groupId segments (`network.ike.tooling`), so a property named
`network.ike.tooling.ike-tooling` is ambiguous: human readers can't
tell where the groupId ends and the artifactId begins. The
GA-derivation rule would have to encode an arbitrary split point.

`:` is the natural Maven separator (`groupId:artifactId:version`)
but is **reserved** in XML for namespace prefixes
(`xmlns:xsi="..."`). Using `:` in an element name produces invalid
XML.

`·` (U+00B7 MIDDLE DOT) is explicitly enumerated in the XML 1.0
Extender list, making it valid in element names without quoting.
It is visually distinct from `.`, unambiguous, and easy to spot in
diffs. It is already in IKE's typographic vocabulary — `꞉` (U+A789
MODIFIER LETTER COLON) is used as the path-safe colon substitute
in goal-output filenames (`ike꞉release-publish.md`). The `·`
serves the same role for property names that `꞉` does for paths:
visually echoes the conventional separator, side-steps the syntax
collision.

## Why no `.version` suffix

The presence of `·` already signals "this property pins a version."
Adding `.version` is redundant. The bare form `${G·A}` is the
common case.

Future qualifiers (classifier, scope, range) get **position-3**
markers explicitly:

```xml
<network.ike·ike-base-parent·classifier·site-theme>6</network.ike·ike-base-parent·classifier·site-theme>
```

That is, every `·` after the second is a typed qualifier. There is
no need for a `.version` suffix to disambiguate a version pin from
a classifier pin — the position of the `·` tells you.

## Opt-out is implicit

A property whose name does **not** contain `·` is invisible to the
GA-convention tooling. The cascade walker, alignment goals, and
lint all walk past it. There is no exclusion list to maintain.

This is the cleanest possible opt-out: a property like
`<my-legacy-version>1.2.3</my-legacy-version>` is preserved
verbatim. It is also lint-flagged as non-conformant in workspaces
that have opted in to lint mode (IKE-Network/ike-issues#476), but
the flag is informational — drift, not error.

## Aliases bridge to existing short names

The wider Maven ecosystem has established short-name idioms:
`${junit-jupiter.version}`, `${assertj.version}`,
`${maven-compiler-plugin.version}`. Consumers that follow these
idioms already work. Forcing every consumer to rewrite their POMs
to use `${G·A}` form on a flag day would be hostile.

Instead, `ike-base-parent` ships a default **alias manifest**
covering common externals: each alias is a one-line indirection
from the legacy short name to the canonical `${G·A}` form.

```xml
<properties>
  <!-- Canonical -->
  <org.junit.jupiter·junit-jupiter>6.0.0</org.junit.jupiter·junit-jupiter>

  <!-- Alias (legacy short name; one-line indirection) -->
  <junit-jupiter.version>${org.junit.jupiter·junit-jupiter}</junit-jupiter.version>
</properties>
```

A consumer using `${junit-jupiter.version}` keeps working
unchanged. A consumer using `${org.junit.jupiter·junit-jupiter}`
gets the same value via the canonical name. Both resolve through
Maven's standard property mechanism — no extension required for
the alias side.

The `ike-version-management-extension` (Tier 0, opt-in via
`.mvn/extensions.xml`) handles two things the static alias chain
cannot: alias **injection** for projects that don't inherit
`ike-base-parent` (the manifest is read from the extension's
classpath), and **typo detection** (see below).

## Migration

There is no flag-day rename. Existing POMs that use legacy short
names continue to build. Each repo migrates at its own pace:

1. Add the canonical `${G·A}` property if not already in scope via
   inheritance.
2. Update each `<dependency>` / `<plugin>` `<version>` reference
   from `${X.version}` to `${G·A}`.
3. When all references in a POM use the canonical form, the legacy
   alias property can be removed locally (but the inherited alias
   from `ike-base-parent` stays for any other consumer).

The migration is reversible — re-add the alias property to roll
back any single reference.

When a repo renames an artifact:

1. Add the new `${G·A}` property at the new GA.
2. Cross-reference sweep every POM, README, doc, and topic for
   stale `${oldG·oldA}` references and update them.
3. Drop the alias once external consumers have migrated.

`ws:scaffold-draft` lint (#476) reports non-conformant pins as
suggestions, not errors. The rate of suggestions resolved is the
migration KPI.

## Typo detection

The `·` separator is not on any standard keyboard, so the common
typo is `${G.A}` instead of `${G·A}`. With regular dots the
property name is structurally valid XML but **silently fails to
resolve**:

```xml
<!-- Looks right, silently broken: -->
<dependency>
    <version>${network.ike.tooling.ike-tooling}</version>
</dependency>
```

Maven's property resolver returns the literal string
`${network.ike.tooling.ike-tooling}` (because no such property is
declared), and the build then fails at dependency resolution with
a confusing "version not a valid Maven coordinate" error.

The `ike-version-management-extension` detects this case at model
transformation time and fails fast with an actionable hint:

```
[ERROR] Property ${network.ike.tooling.ike-tooling} not found.
        Did you mean ${network.ike.tooling·ike-tooling}? (middle dot, U+00B7)
        Typed dots ARE valid in property names; only · signals the
        GA convention.
```

The heuristic: replace every `.` in the unresolved property name
with `·` and check whether the resulting name **is** a declared
property. A match is reported as a suggested fix.

## Keyboard input

The U+00B7 MIDDLE DOT is not on standard keyboards. Use one of:

| Platform | Input |
|---|---|
| macOS | `⌥⇧9` (Option-Shift-9) |
| Linux (Compose key) | `Compose . .` (or `Compose . -`) |
| Windows | Alt+0183 (numeric keypad) |
| Any IDE | A Live Template / snippet keyed to `gav` or similar (IKE-Network/ike-issues#479 ships these for IntelliJ + VS Code) |

You can also copy from any existing IKE POM, or from this
document.

In source files (Java strings, YAML), the character is just a
normal multibyte character. The IKE foundation file encoding is
UTF-8 across the board — no escaping needed.

## Examples

### Foundation Tier 0 / Tier 1 pins (in `ike-base-parent`)

```xml
<properties>
  <network.ike·ike-base-parent>6</network.ike·ike-base-parent>
  <network.ike.tooling·ike-tooling>194</network.ike.tooling·ike-tooling>
  <network.ike.tooling·ike-workspace-extension>4</network.ike.tooling·ike-workspace-extension>
  <network.ike.tooling·ike-version-management-extension>1</network.ike.tooling·ike-version-management-extension>
  <network.ike.docs·ike-docs>50</network.ike.docs·ike-docs>
  <network.ike.platform·ike-platform>80</network.ike.platform·ike-platform>
</properties>
```

### Common externals (in `ike-base-parent` default alias manifest)

```xml
<properties>
  <org.junit.jupiter·junit-jupiter>6.0.0</org.junit.jupiter·junit-jupiter>
  <org.assertj·assertj-core>3.27.3</org.assertj·assertj-core>
  <org.mockito·mockito-core>5.14.2</org.mockito·mockito-core>
  <org.eclipse.collections·eclipse-collections>12.0.0</org.eclipse.collections·eclipse-collections>
  <org.apache.maven.plugins·maven-compiler-plugin>3.14.0</org.apache.maven.plugins·maven-compiler-plugin>
  <org.apache.maven.plugins·maven-surefire-plugin>3.5.3</org.apache.maven.plugins·maven-surefire-plugin>
</properties>
```

### Anchor + sibling alignment (Eclipse Collections idiom)

When several GAs share a single version (the Eclipse Collections
suite ships `eclipse-collections-api`, `eclipse-collections`, and
`eclipse-collections-forkjoin` at the same version), pick one
anchor GA and reference it from siblings via the alias manifest:

```xml
<!-- Anchor pin -->
<org.eclipse.collections·eclipse-collections>12.0.0</org.eclipse.collections·eclipse-collections>

<!-- Sibling aliases (in the alias manifest, not declared per-POM) -->
<org.eclipse.collections·eclipse-collections-api>${org.eclipse.collections·eclipse-collections}</org.eclipse.collections·eclipse-collections-api>
<org.eclipse.collections·eclipse-collections-forkjoin>${org.eclipse.collections·eclipse-collections}</org.eclipse.collections·eclipse-collections-forkjoin>
```

A bump to the anchor flows to every sibling. This works at the
property-inheritance level — no extension code needed.

## Tooling that consumes this convention

| Tool | What it does with the convention |
|---|---|
| `ike-version-management-extension` (#472) | At model-transformation time: injects `${G·A}` properties from the alias manifest when the consumer's POM declares only the legacy short name; emits actionable failure for unresolved `${G·A}` and `${G.A}`-typo references. |
| `ike:release-cascade` (#474) | Derives `propertyName = upstream.groupId + '·' + upstream.artifactId` to find the property to rewrite. No `version-property:` field needed in `release-cascade.yaml`. |
| `ws:align-publish` (#475) | Scans for any property containing `·`, parses the GA, looks up the canonical version (from the cascade head), rewrites the value. No per-subproject configuration. |
| `ws:scaffold-draft` lint (#476) | Reports `<dependency>`/`<plugin>` `<version>` references that don't follow the convention (and aren't covered by a known alias). Warn-only by default. |
| `ws:scaffold-init` (#477) | New workspaces' aggregator POM gets a starter `<properties>` block in canonical form. |
| `ws:overview` (#478) | Per-subproject convention-compliance count surfaces in the markdown overview. |

## Out of scope

- **BOM-managed dependency versions** (where the consumer's POM
  has no `<version>` element at all). These flow via Maven's
  `<dependencyManagement>` import; this convention does not apply.
- **Parent POM version pins** (`<parent><version>4</version></parent>`).
  These are structural, not property-driven. The cascade walker
  handles parent bumps separately.
- **Maven enforcer rules** to forbid non-conformant pins. The lint
  is informational; an opinionated enforcer pass is a follow-up
  decision (not committed).

## See also

- IKE-Network/ike-issues#470 — the parent epic.
- IKE-Network/ike-issues#471 — this standard.
- IKE-Network/ike-issues#472 — `ike-version-management-extension`.
- IKE-Network/ike-issues#473 — `ike-base-parent` ships the alias manifest.
- `IKE-NAMING.md` — governs the artifact / repo / directory names
  whose values appear in the GA on each side of the `·`.
- `IKE-MAVEN.md` — IKE-specific Maven conventions (refers here for
  version-property naming).

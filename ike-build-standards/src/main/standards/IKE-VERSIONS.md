# IKE Version-Property Convention

This is the canonical convention for the property names that pin
Maven artifact versions in IKE Network POMs. Established in
IKE-Network/ike-issues#470 and #471; the typed-marker family that
replaced the original U+00B7 separator is IKE-Network/ike-issues#525.

## The rule

A version-pinning property is named from the coordinate it pins:

> **`<groupId>__GA__<artifactId>__VERSION`** &nbsp;==&nbsp; the property name
> **`<version>`** &nbsp;==&nbsp; the property value

`__` is a separator between adjacent name parts. `GA` is the typed
marker that separates the groupId from the artifactId. `VERSION` is
the typed marker that names the facet. A name never ends in `__`:
the XML structural terminator — `>` for a declaration, `}` for a
reference — closes the final segment.

```xml
<properties>
  <network.ike.tooling__GA__ike-tooling__VERSION>253</network.ike.tooling__GA__ike-tooling__VERSION>
  <network.ike.docs__GA__ike-docs__VERSION>108</network.ike.docs__GA__ike-docs__VERSION>
  <org.junit.jupiter__GA__junit-jupiter__VERSION>6.0.0</org.junit.jupiter__GA__junit-jupiter__VERSION>
</properties>
```

A POM that consumes `network.ike.tooling:ike-tooling` references the
pin by its canonical name:

```xml
<dependency>
    <groupId>network.ike.tooling</groupId>
    <artifactId>ike-maven-plugin</artifactId>
    <version>${network.ike.tooling__GA__ike-tooling__VERSION}</version>
</dependency>
```

One contract, lint-checkable, mechanically derivable from the
coordinate: `MavenCoordinate.versionProperty()` in
`ike-workspace-model` is the derivation every tool uses.

## The facets

Each coordinate gets up to three typed-marker properties:

| Property | Value |
|---|---|
| `<G>__GA__<A>__VERSION` | the version pin |
| `<G>__GA__<A>__POLICY` | the release policy: how this project responds when that upstream releases — `notify`, `verify`, `propose`, `integrate` or `release` (IKE-Network/ike-issues#498) |
| `<G>__GA__<A>__ALIAS` | comma-separated legacy short names the artifact also goes by (IKE-Network/ike-issues#526) |

Future facets slot in identically (`__BOM`, `__SCOPE`, …). A compound
marker keeps the separator rule: `__POLICY__OVERRIDE`, never
`__POLICY_OVERRIDE`.

## Why typed markers

XML 1.0 element names allow letters, digits, hyphens, underscores,
periods and a few extender characters. `.` is already the segment
separator inside a groupId (`network.ike.tooling`), so a name like
`network.ike.tooling.ike-tooling` cannot say where the groupId ends.
`:` is the natural Maven separator but is reserved for XML namespace
prefixes and produces invalid XML in an element name.

The convention first used U+00B7 MIDDLE DOT (`·`), which XML permits.
Maven interpolates it correctly, but IntelliJ's property resolver
matches identifiers with a narrower character class that excludes
it; the resolution chain died at the first `${G·A}` reference, the
plugin descriptor never loaded, and real plugin parameters were
flagged as unknown. The typed-marker family replaced it fleet-wide
on 2026-05-26 (IKE-Network/ike-issues#525):

- **Resolver-safe.** `_` is inside every identifier class Maven,
  IntelliJ or any other consumer uses.
- **Self-documenting.** `GA`, `VERSION`, `POLICY`, `ALIAS` each name
  their role in plain English.
- **One rule.** `__` separates; trailing only when something follows.
- **Greppable with the structural terminator.**
  `grep -E '__VERSION[>}]'` finds every version facet with no false
  positive on `__VERSIONED`; `grep -E '__GA__'` finds every
  coordinate boundary.
- **Typed on plain keyboards.** Underscores need no input method.

The legacy form is still read by the tooling during the transition
and is removed in a future major.

## Opt-out is implicit

A property whose name carries no `__GA__` is invisible to the
convention's tooling. The cascade walker, the aligner and the lint
walk past it. There is no exclusion list to maintain: a property like
`<my-legacy-version>1.2.3</my-legacy-version>` is preserved verbatim.
It is lint-reported as non-conforming in workspaces that opt in to
lint mode (IKE-Network/ike-issues#476), and the report is
informational — drift, not error.

## Aliases bridge to existing short names

The wider Maven ecosystem has established short-name idioms:
`${junit-jupiter.version}`, `${assertj.version}`,
`${maven-compiler-plugin.version}`. Consumers that follow them keep
working; forcing every POM onto the canonical form on a flag day would
be hostile.

Instead, the source POM declares the relationship as metadata and
the tooling materializes the indirection:

```xml
<properties>
  <!-- Canonical pin -->
  <org.junit.jupiter__GA__junit-jupiter__VERSION>6.0.0</org.junit.jupiter__GA__junit-jupiter__VERSION>

  <!-- Legacy short names this coordinate also goes by -->
  <org.junit.jupiter__GA__junit-jupiter__ALIAS>junit-jupiter.version,junit.version</org.junit.jupiter__GA__junit-jupiter__ALIAS>
</properties>
```

`ike-version-management-extension`, registered in the repository's
`.mvn/extensions.xml`, reads every `__ALIAS` at the file-model stage
and injects `<junit-jupiter.version>${org.junit.jupiter__GA__junit-jupiter__VERSION}</junit-jupiter.version>`
for each listed short name the POM does not already declare. Maven
builds the consumer POM from that transformed model, so the
indirections travel with every installed or deployed POM — snapshot
or release — and descendants resolve the short names through ordinary
inheritance. The release flow checks after its first install that the
consumer POM carries every declared indirection and refuses the
release otherwise (IKE-Network/ike-issues#1094).

A short name the source POM declares itself is the author's value;
neither the extension nor the release check touches it.

`ike-base-parent` carries the canonical pins and `__ALIAS` metadata
for the external dependency matrix every foundation tier uses (test
stack, Eclipse Collections, logging, Maven plugins) plus its own
self-pin. The IKE foundation pins (ike-tooling, ike-docs,
ike-platform, the extensions) live in `ike-parent`, the consumer
registry.

## Migration

There is no flag-day rename. Existing POMs that use legacy short
names continue to build. Each repository migrates at its own pace:

1. Confirm the canonical `__VERSION` property is in scope, locally or
   by inheritance.
2. Update each `<dependency>` / `<plugin>` `<version>` reference from
   `${X.version}` to `${G__GA__A__VERSION}`.
3. When every reference in a POM uses the canonical form, the short
   name can leave that POM's `__ALIAS` list — but an inherited
   `__ALIAS` from `ike-base-parent` stays for any other consumer.

The migration is reversible — re-add the short name to the `__ALIAS`
list to roll back a single reference. `ws:scaffold-rewrite`
consuming `__ALIAS` to migrate consumer POMs is
IKE-Network/ike-issues#528.

When a repository renames an artifact:

1. Add the pin at the new coordinate.
2. Sweep every POM, README, doc and topic for the old
   `${oldG__GA__oldA__VERSION}` and update it.
3. Drop the old pin once external consumers have migrated.

`ws:scaffold-draft` lint (IKE-Network/ike-issues#476) reports
non-conformant pins as suggestions, not errors. The rate of
suggestions resolved is the migration KPI.

## Typo detection

The likely mistake is `${G.A}` — dots where the typed marker was
meant. With regular dots the property name is valid XML but resolves
to nothing:

```xml
<!-- Looks right, silently broken: -->
<dependency>
    <version>${network.ike.tooling.ike-tooling}</version>
</dependency>
```

Maven's property resolver returns the literal string, and the build
then fails at dependency resolution with a "version is not a valid
Maven coordinate" error that points nowhere useful.

`ike-version-management-extension` catches this at model
transformation time. At every dot in the unresolved name, right to
left, it tries `__GA__` in place of the dot, the same with
`__VERSION` appended, and the legacy `·`. If one of those is a
declared property, the build fails with the intended name:

```
ike-version-management-extension: 1 convention violation in network.ike.docs:ike-docs:85-SNAPSHOT:

  [TYPO] dependency network.ike.tooling:ike-maven-plugin
    Property ${network.ike.tooling.ike-tooling} is not declared.
    Did you mean ${network.ike.tooling__GA__ike-tooling__VERSION}?
    The IKE typed-marker family uses __GA__ between groupId and
    artifactId, and __VERSION as the terminal facet marker on a
    version pin. Typed dots ARE valid in property names; only
    __GA__ (or the legacy U+00B7 ·) signals the IKE GA convention.
```

A dotted name with no declared candidate is left alone; dots are
legal in property names.

## Examples

### Foundation pins (in `ike-parent`, the consumer registry)

```xml
<properties>
  <network.ike.tooling__GA__ike-tooling__VERSION>253</network.ike.tooling__GA__ike-tooling__VERSION>
  <!-- __POLICY is optional; no foundation POM declares one today,
       and an absent policy takes the cascade default -->
  <network.ike.tooling__GA__ike-tooling__POLICY>integrate</network.ike.tooling__GA__ike-tooling__POLICY>
  <network.ike.tooling__GA__ike-tooling__ALIAS>ike-tooling.version</network.ike.tooling__GA__ike-tooling__ALIAS>

  <network.ike.docs__GA__ike-docs__VERSION>108</network.ike.docs__GA__ike-docs__VERSION>
  <network.ike.tooling__GA__ike-workspace-extension__VERSION>12</network.ike.tooling__GA__ike-workspace-extension__VERSION>
  <network.ike.tooling__GA__ike-version-management-extension__VERSION>11</network.ike.tooling__GA__ike-version-management-extension__VERSION>
</properties>
```

### External dependency matrix (in `ike-base-parent`)

```xml
<properties>
  <org.junit.jupiter__GA__junit-jupiter__VERSION>6.0.0</org.junit.jupiter__GA__junit-jupiter__VERSION>
  <org.junit.jupiter__GA__junit-jupiter__ALIAS>junit-jupiter.version,junit.version</org.junit.jupiter__GA__junit-jupiter__ALIAS>
  <org.assertj__GA__assertj-core__VERSION>3.27.3</org.assertj__GA__assertj-core__VERSION>
  <org.assertj__GA__assertj-core__ALIAS>assertj.version</org.assertj__GA__assertj-core__ALIAS>
  <org.apache.maven.plugins__GA__maven-compiler-plugin__VERSION>3.14.0</org.apache.maven.plugins__GA__maven-compiler-plugin__VERSION>
  <org.apache.maven.plugins__GA__maven-compiler-plugin__ALIAS>maven-compiler-plugin.version</org.apache.maven.plugins__GA__maven-compiler-plugin__ALIAS>
</properties>
```

### Anchor and siblings (Eclipse Collections idiom)

When several coordinates share one version — Eclipse Collections
ships `eclipse-collections-api`, `eclipse-collections` and
`eclipse-collections-forkjoin` together — pin one anchor and point
the siblings at it:

```xml
<!-- Anchor pin -->
<org.eclipse.collections__GA__eclipse-collections__VERSION>12.0.0</org.eclipse.collections__GA__eclipse-collections__VERSION>

<!-- Siblings reference the anchor -->
<org.eclipse.collections__GA__eclipse-collections-api__VERSION>${org.eclipse.collections__GA__eclipse-collections__VERSION}</org.eclipse.collections__GA__eclipse-collections-api__VERSION>
<org.eclipse.collections__GA__eclipse-collections-forkjoin__VERSION>${org.eclipse.collections__GA__eclipse-collections__VERSION}</org.eclipse.collections__GA__eclipse-collections-forkjoin__VERSION>
```

A bump to the anchor flows to every sibling through property
inheritance; no extension code is involved.

## Tooling that consumes this convention

| Tool | What it does with the convention |
|---|---|
| `ike-version-management-extension` (#472, #1094) | Registered in every working set and alias-declaring foundation repository. Injects `__ALIAS` indirections into the consumer POM; fails the build on an unresolved `${G__GA__A__VERSION}`, a `${G.A}` typo, an invalid `__POLICY` value, or a reactor root without a local `<scm>`. |
| `ike:release-cascade` (#474, #496) | Derives the property to rewrite from each upstream's coordinate — `MavenCoordinate.versionProperty()` — and dispatches on `__POLICY`. No `version-property:` field in `release-cascade.yaml`. |
| `ike:release-publish` (#1094) | After the first install, verifies that every `__ALIAS` short name reached the consumer POM and refuses the release otherwise. |
| `ws:align-publish` (#475, planned) | Scans for `__GA__` properties, parses the coordinate, looks up the canonical version, rewrites the value. |
| `ws:scaffold-draft` lint (#476, planned) | Reports `<dependency>` / `<plugin>` `<version>` references that do not follow the convention and are not covered by an `__ALIAS`. Warn-only by default. |
| `ws:scaffold-init` (#477, planned) | New workspaces' aggregator POM gets a starter `<properties>` block in canonical form. |
| `ws:overview` (#478, planned) | Per-subproject convention-compliance count in the markdown overview. |

## Out of scope

- **BOM-managed dependency versions**, where the consumer's POM has
  no `<version>` element at all. Those flow through
  `<dependencyManagement>` import; this convention does not apply.
- **Parent POM version pins** (`<parent><version>15</version></parent>`).
  Those are structural, not property-driven; the cascade walker
  handles parent bumps separately.
- **Plugin-level `<dependencies>`.** Maven applies no dependency
  management to a plugin's own dependencies, so their versions are
  written out — as `__VERSION` references where a pin exists.
  Working-set members named there are bound by
  `ike-workspace-extension` (IKE-Network/ike-issues#1019).
- **Maven enforcer rules** to forbid non-conformant pins. The lint is
  informational; an enforcer pass is a separate decision.

## See also

- IKE-Network/ike-issues#470 — the convention.
- IKE-Network/ike-issues#471 — this standard.
- IKE-Network/ike-issues#472 — `ike-version-management-extension`.
- IKE-Network/ike-issues#525 — the typed-marker family.
- IKE-Network/ike-issues#526 — `__ALIAS` metadata and the pin-table restructure.
- IKE-Network/ike-issues#1094 — fleet-wide registration; retirement of release-time alias baking.
- `IKE-NAMING.md` — governs the artifact, repository and directory
  names whose values appear on each side of `__GA__`.
- `IKE-MAVEN.md` — IKE-specific Maven conventions; refers here for
  version-property naming.

---
date_published: 2026-05-20
date_modified: 2026-05-20
canonical_url: https://ike.network/ike-tooling/ike-maven-plugin-support/index.html
---

# IKE Maven Plugin Support

`ike-maven-plugin-support` is the shared library of helper classes used by the IKE Maven plugins (`ike-maven-plugin` and `ike-workspace-maven-plugin`). It’s not a plugin itself — it has no mojos. It exists to keep the two plugins consistent without one plugin depending on the other.

| Coordinate | Value |
| --- | --- |
| Group ID | `network.ike.tooling` |
| Artifact ID | `ike-maven-plugin-support` |
| Packaging | `jar` |

## [#why-a-separate-module](#why-a-separate-module)Why a separate module

`ike-maven-plugin` and `ike-workspace-maven-plugin` need shared code for:

- Consistent goal-result reporting (the per-goal markdown reports).
- Shared parameter parsing and validation.
- The compile-time goal registry that catches mojo / enum drift.
- Released-version resolution — discovering the latest released version of an artifact (used by the scaffold foundation bake in both plugins).

Having one plugin depend on the other would create an awkward release-order constraint and a circular conceptual reference. Pulling the shared code into a third library keeps the dependency direction clean: both plugins depend on `ike-maven-plugin-support`, which depends on neither.

## [#key-classes](#key-classes)Key classes

| Class | Role |
| --- | --- |
| `AbstractGoalMojo` | Base class for any mojo that produces a per-goal markdown report. Centralizes the report-write path and the file-naming convention (`<goal-name>.md`). |
| `GoalReport` | Builder for the structured markdown reports goals emit. Sections, tables, callouts. |
| `GoalRef` | Type-safe reference to a goal (rather than a string literal). Lets the compiler catch typo’d goal names in `@see` and similar references. |
| `MojoParamSupport` | Shared parameter parsing — coercion of `-Dprop=val` strings into typed values, with consistent error messages on bad input. |
| `support.version.*` | Released-version resolution: `CandidateVersionResolver`, `SessionCandidateVersionResolver`, `MavenVersionComparator`, `VersionResolverFailureException`. Used by the scaffold foundation bake in both plugins. |

## [#the-goal-registry-pattern](#the-goal-registry-pattern)The goal registry pattern

Both `ike-maven-plugin` and `ike-workspace-maven-plugin` declare their goals twice: once as a `@Mojo`-annotated class (the implementation) and once as an entry in a corresponding `IkeGoal` or `WsGoal` enum (the catalog). A custom annotation processor checks at compile time that every mojo has a matching enum entry — so the help output, the markdown report filenames, and the goal implementations cannot drift apart.

`ike-maven-plugin-support` provides the supporting types (`GoalRef`, base mojo class, report writer) that make this pattern work uniformly across both plugins.

## [#source](#source)Source

- GitHub: [ike-tooling/ike-maven-plugin-support](https://github.com/IKE-Network/ike-tooling/tree/main/ike-maven-plugin-support)[1]
- Issues: [IKE-Network/ike-issues](https://github.com/IKE-Network/ike-issues)[2]

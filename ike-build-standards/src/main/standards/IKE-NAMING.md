# IKE Naming Policy

This is the canonical naming rule for IKE Network artifacts, git
repositories, on-disk directories, and workspace manifests.
Established in IKE-Network/ike-issues#467.

## The rule

For any IKE artifact, the following four identifiers MUST be equal:

> **`<artifactId>` &nbsp;==&nbsp; git repo name &nbsp;==&nbsp;
> on-disk directory &nbsp;==&nbsp; `workspace.yaml` subproject key**

No transformations. No prefixing on one side and dropping it on the
other. No aliases. If `git clone <url>` produces a directory named
`foo`, then `foo` is also the Maven `<artifactId>`, the on-disk
directory you keep it in, and (if applicable) the key under
`workspace.yaml`'s `subprojects:` map.

The rule applies to the **primary identifier** of a repository:

- For a **single-module repo**, that is the `<artifactId>` of its
  one POM.
- For a **multi-module reactor** (e.g., `ike-tooling`, `ike-docs`,
  `ike-platform`), that is the reactor POM's `<artifactId>`. Each
  submodule under it still satisfies the rule recursively:
  submodule directory name equals submodule `<artifactId>`.

The rule does NOT require that a single git repo has a single
artifactId — multi-module reactors publish many artifacts. It
requires that the repo's *top-level* name matches its *top-level*
POM's artifactId, and that internal directory names match their
submodule artifactIds.

## Why

Mismatched identifiers create a steady, invisible tax: every doc
page disambiguates, every `git clone` line has to translate
between "the thing on GitHub" and "the thing in workspace.yaml,"
every search-and-replace misses a corner, and new contributors
have to memorize a translation table that experienced contributors
no longer notice.

Pre-policy, `ike-example-ws` exhibited every failure mode:

- subproject key `its` &rarr; repo `ike-example-its` &rarr;
  artifact `network.ike.examples:ike-example-its` — three
  identifiers for one thing.
- subproject key `example-project` &rarr; repo `example-project`
  — consistent, but wrong word order vs. the sibling
  `doc-example`.
- Workspace `ike-example-ws` with `ike-` prefix; subproject
  `doc-example` without — inconsistent prefixing on artifacts that
  serve the same role (example templates).

Renamed under #467 to one name each, everywhere:

- `doc-example` (kept — the prototype)
- `project-example`
- `integration-tests-example`
- `workspace-example`

## Sub-rules

### Foundation artifacts keep the `ike-` prefix

Tier-0 and Tier-1 foundation artifacts — the ones IKE consumers
inherit, depend on, or extend — keep the `ike-` prefix:

| Artifact | Tier | Role |
|---|---|---|
| `ike-base-parent` | 0 | Root parent POM for foundation artifacts. |
| `ike-tooling` | 1 | Reactor: `ike-maven-plugin`, `ike-bom-tools`, `ike-build-standards`, … |
| `ike-docs` | 1 | Reactor: `ike-doc-maven-plugin`, `ike-doc-resources`, fonts, DocBook XSL. |
| `ike-platform` | 1 | Reactor: `ike-parent`, `ike-bom`, `ike-workspace-maven-plugin`. |
| `ike-workspace-extension` | 1 | Maven 4 build extension consumed via `.mvn/extensions.xml`. |

The prefix signals "core infrastructure published to Maven Central
as inheritable building blocks." It is load-bearing — without it,
the foundation cannot be distinguished from the application code
that inherits from it.

### Example templates drop the prefix and use `<role>-example`

Example templates are reference shapes, not infrastructure.
Consumers don't depend on them; they copy from them. The `ike-`
prefix on examples adds noise that competes with the foundation
signal. Use the pattern `<role>-example`:

| Artifact | Role |
|---|---|
| `doc-example` | The prototype — single-repo doc-only template. |
| `project-example` | Single-repo Java + docs template. |
| `integration-tests-example` | Integration-test harness against the foundation cascade. |
| `workspace-example` | Workspace-aggregator template. |

Reading top-to-bottom: "doc example", "project example",
"integration tests example", "workspace example." The role is the
adjective; "example" is the noun. Templates that diverge from this
pattern (`example-project`, `ike-example-ws`, …) violate the
policy.

### Workspace aggregators are example templates

Workspaces are reference shapes (a copy-and-modify template for
how to organize a multi-repo project), not infrastructure. They
follow the example-template rule: drop the `ike-` prefix, use
`<role>-example`. The single canonical workspace template is
`workspace-example`.

The older `<name>-ws` suffix convention (e.g., `ike-example-ws`,
`ike-komet-ws`) is **deprecated** in two directions:

* Template workspaces follow the `<role>-example` rule —
  `ike-example-ws` was renamed to `workspace-example` (and is
  itself renamed to `workspace-reactor-example` under the
  workspace-reactor naming policy).
* Project-specific aggregators that are themselves activated
  reactors take the `-wsr` suffix: `ike-komet-ws` was renamed to
  `ike-komet-wsr`. The `-wsr` name encodes both the Komet
  project (the name root) and the workspace-reactor concept —
  an aggregator activated as a Maven reactor. See the
  `arch-workspace-and-workspace-reactor` topic in
  `ike-lab-documents` for the vocabulary.

### Maven plugins follow `{purpose}-maven-plugin`

Maven plugins MUST be named `<purpose>-maven-plugin` so Maven's
prefix-resolution mechanism works (`mvn ike:release-publish` &rarr;
`ike-maven-plugin`, `mvn ws:overview` &rarr;
`ike-workspace-maven-plugin`). This is a Maven-side requirement,
not an IKE policy choice — but it interacts with the prefix rule:
foundation plugins keep the `ike-` prefix
(`ike-maven-plugin`, `ike-workspace-maven-plugin`,
`ike-doc-maven-plugin`), and the repo containing each plugin
either is named after the plugin (single-module case:
`ike-workspace-maven-plugin` &rarr; lives in its own repo of the
same name) or after the reactor that builds it (multi-module
case: `ike-maven-plugin` lives inside the `ike-tooling` reactor).

### Reactor names

Multi-module reactors are themselves IKE artifacts: the reactor
POM has its own `<artifactId>`, and the rule applies. Foundation
reactors use the descriptive `ike-<purpose>` form
(`ike-tooling`, `ike-docs`, `ike-platform`). No `-reactor` suffix.

## How this rule interacts with `workspace.yaml`

`workspace.yaml`'s `subprojects:` map keys MUST match each
subproject's repo name AND its on-disk directory inside the
workspace. The `ws:scaffold-init` goal clones each
`repo:` URL into a directory named after the subproject key; the
rule guarantees that directory's `pom.xml` declares the same
`<artifactId>` as the key.

```yaml
subprojects:
  project-example:                                           # <-- the key
    repo: https://github.com/IKE-Network/project-example.git # <-- repo name == key
    version: 32-SNAPSHOT
    groupId: network.ike.examples
    # On disk: workspace-example/project-example/
    # pom.xml: <artifactId>project-example</artifactId>
```

The workspace plugin's `ws:scaffold-draft` reports any drift
between these four identifiers as a policy violation.

## Migration policy

When an existing artifact's name diverges from this rule:

1. **File an issue** referencing #467 with a migration plan
   covering: GitHub repo rename, on-disk directory rename, POM
   `<artifactId>` change, downstream consumer updates, and site
   re-publish.
2. **Use `gh repo rename`** for the GitHub side. GitHub keeps
   redirects from the old URL for an indefinite grace period, so
   in-flight clones and downstream `<scm>` references keep working
   while consumers update.
3. **Change all four identifiers in the same release cycle.**
   Half-migrated states (e.g., new repo name but old artifactId)
   re-introduce the very inconsistency the rule prevents.
4. **Cross-reference sweep.** Update every README, CLAUDE.md,
   `src/site/asciidoc/*.adoc`, `OrgSiteSupport.FOUNDATION` entry,
   `~/ike-dev/CLAUDE.md` repo-location notes, and landing-page
   registration before closing the migration issue.

## Edge cases

### A repo's primary artifact isn't its reactor POM

The `ike-tooling` repo has the reactor POM `network.ike.tooling:ike-tooling`
and the most user-facing artifact is `network.ike.tooling:ike-maven-plugin`.
Both are valid IKE artifacts and both satisfy the rule:

- Repo `ike-tooling` &harr; reactor `<artifactId>ike-tooling</artifactId>`.
- Submodule directory `ike-maven-plugin/` &harr;
  `<artifactId>ike-maven-plugin</artifactId>`.

No identifier has two meanings; nothing translates. Each name
points to exactly one thing.

### Aliased artifacts (e.g., a renamed module that keeps an old
alias)

Don't. The rule is "no aliases." If a consumer pins the old name,
the migration plan updates them before closing #467's follow-on.

## Where the rule is enforced

| Touchpoint | How |
|---|---|
| Repo creation | Code reviewer rejects names that violate the policy. |
| `ws:scaffold-init` | Aborts if the workspace.yaml subproject key doesn't match the repo name from the cloned URL. |
| `ws:scaffold-draft` | Reports per-subproject drift between key, dir, and `<artifactId>`. |
| Site rendering | The `OrgSiteSupport.FOUNDATION` map keys must match the repo names that produce the registered sites. |

## See also

- IKE-Network/ike-issues#467 — the policy issue.
- `IKE-MAVEN.md` — Maven-side conventions (refers here for naming).
- `IKE-WORKSPACE.md` — workspace.yaml structure and `ws:*` goals.

# IKE Release Conventions

## Issue Tracking

All cross-project issues are tracked in a single GitHub repository:
[IKE-Network/ike-issues](https://github.com/IKE-Network/ike-issues).

One tracker for all components avoids scattering issues across per-repo
trackers and makes cross-cutting concerns (architecture, CI/CD,
provenance) easy to find.

## Labels

### Component Labels

Assign one component label per issue to indicate the primary affected area.

| Label | Description |
|-------|-------------|
| `tooling` | Build tooling, Maven plugins, workspace plugin |
| `tinkar` | Tinkar core data model and providers |
| `komet` | Komet UI framework and desktop application |
| `nexus` | Artifact repository, staging, promotion |

### Classification Labels

Assign one or more classification labels to describe the nature of the work.

| Label | Description |
|-------|-------------|
| `bug` | Something is broken |
| `enhancement` | New capability or improvement |
| `documentation` | Standards, README, or inline doc updates |
| `tech-debt` | Code quality, refactoring, cleanup |
| `architecture` | Design decisions, structural changes |
| `ci-cd` | CI pipelines, GitHub Actions, release automation |
| `provenance` | Build provenance, SLSA, supply chain |

### Workflow Labels

| Label | Description |
|-------|-------------|
| `pending-release` | Code is committed locally; waiting for next release to ship |
| `release-notes` | Noteworthy change — include prominently in release notes |

## Milestones

Milestones group issues into a specific release. One milestone per
component release.

### Naming Convention

```
<component> v<version>
```

Examples: `ike-tooling v140`, `ike-platform v23`, `ike-docs v2`, `tinkar-core 1.80.0`.

### Lifecycle

1. **Create** the milestone when planning the next release or when the
   first issue targeting that release is filed.
2. **Assign** issues to the milestone as they are committed or triaged.
3. **Close** the milestone after the release ships and all issues are
   resolved.

## The pending-release Workflow

This workflow bridges the gap between completing work locally and
shipping it in a release.

1. **Commit** — Work is done and committed to the local branch.
2. **Label** — Add `pending-release` to the issue. This signals that
   the fix or feature is code-complete but not yet released.
3. **Assign milestone** — Set the target release milestone
   (e.g., `ike-tooling v57`).
4. **Release ships** — Run `ike:release-publish` (single repo) or
   `ws:release-publish` (workspace-wide cascade). The release process
   tags, deploys to Nexus + komet.sh + GitHub Pages, and bumps the
   version. Use `ike:release-draft` / `ws:release-draft` to preview
   without writing.
5. **Automatic close + label removal** — `ike:release-publish` does
   this for you as of #390:
   - Closes the milestone matching `<projectId> v<version>`.
   - Walks commits in `<previous-tag>..v<version>`, parses
     `Fixes`/`Closes`/`Resolves` trailers, and removes the
     `pending-release` label from every referenced issue (including
     cross-org references in `<owner>/<repo>#N` form).
   - GitHub auto-closes issues referenced by `Fixes`/`Closes` trailers
     in the same org. Cross-org issues need a manual close once the
     consuming repo's release lands.

## Release Preflight

`ike:release-publish` runs a sequence of preflight checks before any
mutation. From #392:

- **Git push auth** — fail-fast if `git push --dry-run` can't reach
  `origin`.
- **gh CLI authenticated** — warn-only; GitHub Release creation
  needs `gh` but is skipped cleanly without it.
- **gh write permission on `issueRepo`** — fail-fast. Required so
  the auto-close + label-removal step (above) doesn't 403 mid-release.
- **`pending-release` label exists on `issueRepo`** — warn if missing;
  label removal becomes a no-op without it.
- **Trailer compliance** — walk commits in the release range; warn
  on any without a `Fixes`/`Closes`/`Resolves`/`Refs` trailer per
  [IKE-COMMITS.md](IKE-COMMITS.md). Warn-only initially; promotes to
  fail-fast after a release cycle of adoption.
- **Milestone exists for the release** — warn if the
  `<projectId> v<version>` milestone is absent; release falls back
  to auto-generated notes.
- **Maven wrapper present** — fail-fast.

## Release Notes

Release notes are generated from the closed issues in a milestone
using the `ws:release-notes` goal:

```bash
mvn ws:release-notes -Dmilestone="ike-tooling v140"
```

The output categorizes issues by label:

- **Fixes** — issues labeled `bug`
- **Enhancements** — issues labeled `enhancement`
- **Internal** — all other issues (tech-debt, documentation, ci-cd, etc.)

Issues with the `release-notes` label are listed first within their
category for emphasis.

The output can be used directly as the body of a GitHub Release:

```bash
mvn ws:release-notes -Dmilestone="ike-tooling v140" -Doutput=release-notes.md
gh release edit v140 --repo IKE-Network/ike-tooling --notes-file release-notes.md
```

## Release Cadence

Releases are on-demand, not scheduled. The trigger is a Java code
change — documentation-only changes accumulate in the current SNAPSHOT
and ship with the next capability release.

This avoids churning version numbers for non-functional changes while
ensuring that every release contains something meaningful.

## Release Execution

Single-component releases use `ike:release-publish`. Multi-component
coordinated releases use `ws:release-publish` (the workspace plugin
fans out across all release-pending subprojects in topological order).
See the Workspace Release Orchestration section in `IKE-WORKSPACE.md`
for the full workflow, including draft previews, cascade updates,
and recovery from failures (`ike:release-status` /
`ws:release-status`).

## Foundation Release Cascade

The IKE foundation repos must be released in topological order
because each downstream repo consumes the artifacts of those above
it through property indirection (`${ike-tooling.version}`,
`${ike-docs.version}`):

    ike-tooling  →  ike-docs  →  ike-platform  →  workspace consumers

This order is **declarative and decentralized**, not folklore. The
cascade is a loosely-coupled distributed system: every foundation
repo version-controls its own `src/main/cascade/release-cascade.yaml`
in its own git tree, declaring *only its own* `upstream` and
`downstream` edges. No project authors the global ordering — the
full graph is assembled by traversing those per-project files
(IKE-Network/ike-issues#420). To change the cascade, edit the
relevant repo's own manifest.

Each upstream edge names the `${X.version}` property the consuming
repo carries; the cascade `head` (no upstream) and `terminal` (no
downstream) endpoints are asserted positively, so a forgotten edge
is a manifest error rather than a silent omission.

The release goals read the local manifest so the cascade cannot be
silently forgotten:

- **`ike:release-draft`** — when the repo has a `release-cascade.yaml`,
  the draft preview enumerates the downstream repos this release
  will make stale.
- **`ike:release-publish`** — before cutting a release, aligns every
  `${X.version}` upstream pin to the latest released upstream, so a
  single-repo release never ships on a stale foundation. After the
  release, a cascade footer names the next repo and the exact
  command to continue (`cd ../<next> && mvn ike:release-publish`, or
  `mvn ike:release-cascade` for the whole loop).
- **`ike:release-cascade`** — assembles the graph from the
  per-project manifests and walks it in topological order, running
  `ike:release-publish` on every foundation repo that has unreleased
  changes.

A repo with no `release-cascade.yaml` (an ordinary consumer) sees no
cascade output — the cascade only orders the foundation.

### The cascade graph today

The current foundation members and their edges:

| Repo | Tier | Upstream | Downstream |
|---|---|---|---|
| `ike-base-parent` | Tier 0 (apex) | _none — parent-inheritance bumps are not yet cascade-tracked, see #462_ | (every inheritor — but no cascade edges today; declares the canonical `${G·A}` pins) |
| `ike-java-support` | Tier 0 (head) | _none — `head: true`_ | `ike-version-management-extension` (via compile-scope dep) |
| `ike-workspace-extension` | Tier 0 (head) | _none today; ike-base-parent inheritance pending #462_ | `ike-platform` |
| `ike-version-management-extension` | Tier 0 (head) | _none cascade-tracked; consumes `ike-java-support` at compile_ | _none — `terminal: true`_ |
| `ike-tooling` | Tier 1 (head) | _none in this column today; ike-base-parent inheritance pending #462_ | `ike-docs`, `ike-platform` |
| `ike-docs` | Tier 1 | `ike-tooling` (via `${network.ike.tooling·ike-tooling}`) | `ike-platform` |
| `ike-platform` | Tier 1 (terminal) | `ike-tooling`, `ike-docs`, `ike-workspace-extension` (all via canonical `${G·A}` pins) | _none — `terminal: true`_ |

### Adding a new foundation artifact

When you spin up a new IKE-Network artifact (anything under
`network.ike.*`, published to Maven Central, inheriting from
`ike-base-parent`), the cascade must learn about it explicitly.
Each step below is a manifest edit in a different file — the cost
of the decentralized design is that the convention has to be
applied consistently. Miss any of these and the cascade either
falls back to manual driving (best case) or silently ships a
release on a stale foundation (worst case).

1. **In the new repo:** create
   `src/main/cascade/release-cascade.yaml`. Declare `upstream`
   (any IKE foundation artifacts this repo consumes via
   `${X.version}` property), `downstream` (any IKE foundation
   artifacts that consume this one), and — when applicable —
   `head: true` (no upstream) or `terminal: true` (no
   downstream). The schema is enforced positively so a forgotten
   edge is a hard error, not a silent omission.

2. **In every downstream repo's `release-cascade.yaml`:** add a
   matching `upstream` entry with the consuming
   `version-property`. The graph is bidirectional — both ends of
   the edge must be declared so the traversal closes.

3. **In every downstream repo's parent POM (or reactor root):**
   declare the `${X.version}` property. The cascade aligns this
   literal before a release; without the property, the alignment
   is a no-op.

4. **If the artifact is consumed by `ws:scaffold-init`** (i.e.
   `WorkspaceBootstrap` writes it into a workspace's `.mvn/`
   files), also add the property to
   `ike-workspace-maven-plugin/src/main/resources/network/ike/plugin/ws/ws-plugin.properties`
   so the build-time literal is reachable from
   `loadBuildProperty`.

5. **In the cascade graph table above:** add the new row.

For Tier 0 artifacts (those that downstreams inherit via
`<parent>`), no cascade edges can be declared yet — parent-version
bumps are not cascade-tracked. Drive parent bumps by hand until
[`IKE-Network/ike-issues#462`](https://github.com/IKE-Network/ike-issues/issues/462)
ships.

### Topology vs. execution

The per-project manifests declare *topology* — which repos, in what
order — and are environment-neutral. *Execution* is
environment-specific:

- **Local workstation** — foundation repos are checked out as
  siblings under one directory; `ike:release-cascade` walks them in
  a single process. The containing directory is `ike.release.cascade.basedir`
  (default: the parent of the repo the goal runs in).
- **CI server** — each foundation repo is its own build
  configuration with its own checkout. The topology is mirrored as
  CI build-chain dependencies, and each build runs the
  location-independent `ike:release-publish`, whose cascade footer
  is the signal the next stage triggers on. Artifact handoff is via
  Nexus, not the filesystem. The build-chain edges are not
  hand-wired: a CI meta-runner generates them from the assembled
  graph via `ike:cascade-export` (`-Dformat=json` or `properties`),
  so the CI graph derives from the manifests rather than drifting
  from them.

See IKE-Network/ike-issues#402 and #420.

## Issue Tracking Discipline

### File Before Implementing

Every code change must trace to a tracked issue. File the issue in
IKE-Network/ike-issues BEFORE starting implementation, not
retroactively. This ensures release notes are complete without
manual cleanup.

Pattern: file issue → assign to milestone → implement → close
issue → release closes milestone.

### Artifact Labels

Use workspace subproject artifactIds as labels (e.g., `tinkar-core`,
`komet`, `ike-tooling`). The workspace aggregator gets its own label
(e.g., `komet-ws`) for cross-cutting issues. Labels stay at the
subproject level — the unit of release. Submodule detail goes in the
issue description.

### Milestone Discipline

Create the milestone when planning a release or filing the first
issue for it. Assign every issue as it is committed or triaged.
Close the milestone after the release ships. `ike:release-publish`
closes the milestone automatically when a matching one is found.

### Commit Messages

See [IKE-COMMITS.md](IKE-COMMITS.md) for the full commit-message
standard, including the mandatory issue-trailer rule (every commit
references a tracked issue via `Fixes <owner>/<repo>#N` or
`Refs <owner>/<repo>#N`) and the AI-attribution prohibition.

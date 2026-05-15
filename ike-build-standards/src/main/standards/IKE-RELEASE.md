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

This order is **declarative**, not folklore. It lives in
`release-cascade.yaml` — the single source of truth, authored in
`ike-build-standards/src/main/cascade/` and shipped as the
`ike-build-standards` `cascade` classified artifact. `ike-parent`
unpacks it at the `validate` phase to `target/release-cascade.yaml`.
To change the cascade, edit that one file. Cascade members are
keyed off their reactor-root Maven coordinates (`groupId` +
`artifactId`) — the same identity a releasing project reports from
its own POM.

The manifest *location* is itself declared in the POM, not
discovered by filesystem heuristics: the standard property
`ike.release.cascade.manifest` (set in `ike-parent`'s
`<properties>`, and in `ike-tooling`'s own root POM) names the
path. Override it in any project's `<properties>` or with `-D`.

When no on-disk manifest is found — the case for `ike-docs` and
`ike-platform`, which sit upstream of `ike-parent` and so inherit
neither the unpack execution nor the property — the release goals
resolve the `ike-build-standards` `cascade` artifact through the
Maven session and read `release-cascade.yaml` from inside it. This
runtime resolution is the universal fallback: every foundation repo
already consumes `ike-build-standards`, so the cascade is always
reachable regardless of checkout layout.

The release goals read the manifest so the cascade cannot be
silently forgotten:

- **`ike:release-draft`** — when the project is a cascade member,
  the draft preview enumerates the downstream repos this release
  will make stale.
- **`ike:release-publish`** — after a single-repo release completes,
  a cascade footer names the next repo and the exact command to
  continue (`cd ../<next> && mvn ike:release-publish`, or
  `mvn ws:cascade-foundation-publish` for the whole loop).
- **`ws:cascade-foundation-publish`** — walks the manifest order,
  releasing every foundation repo that has unreleased changes, then
  the workspace itself. `-Dfoundations=<csv>` overrides the order
  for a partial run.

A repo that is not a cascade member (an ordinary consumer) sees no
cascade output — the manifest only orders the foundation.

### Topology vs. execution

The manifest declares *topology* — which repos, in what order — and
is environment-neutral. *Execution* is environment-specific:

- **Local workstation** — foundation repos are checked out as
  siblings under one directory; `ws:cascade-foundation-publish`
  walks them in a single process. Checkout locations are
  property-driven: `ike.release.cascade.basedir` sets the base
  directory, and the `cascadeRepoDirs` map parameter overrides
  individual repos for non-standard layouts.
- **CI server** — each foundation repo is its own build
  configuration with its own checkout; `ws:cascade-foundation-publish`
  is not used. The topology is mirrored as CI build-chain
  dependencies, and each build runs the location-independent
  `ike:release-publish`, whose cascade footer is the signal the
  next stage triggers on. Artifact handoff is via Nexus, not the
  filesystem. The build-chain edges are not hand-wired: a CI
  meta-runner generates them from the manifest via
  `ike:cascade-export` (`-Dformat=json` or `properties`), so the CI
  graph derives from `release-cascade.yaml` rather than drifting
  from it.

`release-cascade.yaml` is the single specification both models —
and the CI build-chain wiring — derive from. See
IKE-Network/ike-issues#402.

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

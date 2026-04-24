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

Examples: `ike-tooling v57`, `ike-pipeline 43`, `tinkar-core 1.80.0`.

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
4. **Release ships** — Run `ike:release` or `ws:release`. The release
   process tags, deploys, and bumps the version.
5. **Close issues** — After the release, close all issues in the
   milestone. Remove `pending-release` labels.
6. **Close milestone** — Mark the milestone as closed.

## Release Notes

Release notes are generated from the closed issues in a milestone
using the `ws:release-notes` goal:

```bash
mvn ws:release-notes -Dmilestone="ike-tooling v57"
```

The output categorizes issues by label:

- **Fixes** — issues labeled `bug`
- **Enhancements** — issues labeled `enhancement`
- **Internal** — all other issues (tech-debt, documentation, ci-cd, etc.)

Issues with the `release-notes` label are listed first within their
category for emphasis.

The output can be used directly as the body of a GitHub Release:

```bash
mvn ws:release-notes -Dmilestone="ike-tooling v57" -Doutput=release-notes.md
gh release edit v57 --repo kec/ike-tooling --notes-file release-notes.md
```

## Release Cadence

Releases are on-demand, not scheduled. The trigger is a Java code
change — documentation-only changes accumulate in the current SNAPSHOT
and ship with the next capability release.

This avoids churning version numbers for non-functional changes while
ensuring that every release contains something meaningful.

## Release Execution

Single-component releases use `ike:release`. Multi-component coordinated
releases use `ws:release`. See the Workspace Release Orchestration
section in `IKE-WORKSPACE.md` for the full workflow, including dry runs,
cascade updates, and recovery from failures.

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
Close the milestone after the release ships. `ike:release`
closes the milestone automatically when a matching one is found.

### Commit Messages

Do not add any AI-attribution trailer to commits — no `Assisted-by`,
no `Co-Authored-By`, no `Generated-with`. The developer is the sole
author. Normal trailers that would exist independent of AI tooling
(`Signed-off-by`, `Reviewed-by`, issue refs) are still appropriate.

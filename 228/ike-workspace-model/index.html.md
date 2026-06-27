---
date_published: 2026-06-26
date_modified: 2026-06-26
canonical_url: https://ike.network/ike-tooling/ike-workspace-model/index.html
---

# IKE Workspace Model

The `ike-workspace-model` library defines the data structures and conventions that describe an IKE workspace. It is consumed by both `ike-workspace-maven-plugin` (the `ws:*` goals) and `ike-maven-plugin` (the `ike:*` release-orchestration goals), so the two plugins share a single source of truth for what a workspace **is**.

| Coordinate | Value |
| --- | --- |
| Group ID | `network.ike.tooling` |
| Artifact ID | `ike-workspace-model` |
| Packaging | `jar` |

## [#core-types](#core-types)Core types

### [#workspace](#workspace)Workspace

A `Workspace` is the parsed form of `workspace.yaml`. It contains:

- The workspace’s own GAV (group, artifact, version).
- An ordered list of `Subproject` entries.
- Optional groups of related subprojects.

The model is generated from the YAML at parse time and is immutable once constructed. Mutations to disk go through dedicated manipulation classes (e.g., `PomRewriter` for POMs, never sed/regex on YAML).

### [#subproject](#subproject)Subproject

A `Subproject` describes one repository within the workspace:

- `name` — the directory name and YAML key.
- `repo` — the git URL.
- `branch` — the expected git branch.
- `groupId` / `artifactId` / `version` — the Maven coordinates.
- `state` — alignment state (see below).
- `type` — `release` or `checkpoint` (the artifact-cadence).

The denormalized fields (groupId, version) are kept in sync with the on-disk POM by `ws:scaffold-publish’s field-normalization reconciler (see ike-issues#393). They’re cached in the YAML so workspace-wide reads (graph traversal, ordering) don’t require parsing every POM.

## [#alignment-states](#alignment-states)Alignment states

A subproject is in one of four alignment states at any moment:

| State | Meaning |
| --- | --- |
| **snapshot-aligned** | `state="snapshot"`. Default daily-driver. POM is on a SNAPSHOT version; consumers resolve via the workspace, not Nexus. |
| **tag-aligned (release)** | `state="tag-aligned"`, `kind="release"`. POM is at a tagged release version, artifact is published to Nexus. Other workspace subprojects can resolve it through normal Maven. |
| **tag-aligned (checkpoint)** | `state="tag-aligned"`, `kind="checkpoint"`. POM has a SNAPSHOT version but the workspace remembers a checkpoint tag for reproduction. Not deployed to Nexus. |
| **external-consumer** | `state="external-consumer"`. The subproject lives outside the workspace’s release cadence; we only consume it. Treated as read-only by alignment goals. |
| **unrelated** | `state="unrelated"`. Co-located but not part of the workspace’s Maven graph. Excluded from all alignment operations. |

The four-state model (ike-issues#233) replaces an earlier two-state design that conflated "released" and "checkpoint-tagged". Splitting them lets `ws:align-publish` and `ws:checkpoint-publish` have non-overlapping behavior without ambiguity.

## [#branch-coherence](#branch-coherence)Branch coherence

A workspace-wide invariant: every checked-out subproject must be on the same git branch as the workspace repo itself. There is no per-subproject opt-out from this rule. When `ws:feature-start-publish` creates `feature/foo`, **all** subprojects move to `feature/foo`. When `ws:switch-publish` checks out a different branch, **all** subprojects switch.

This invariant simplifies reasoning (the workspace state is one branch, not N) and makes it possible to release a coherent set of artifacts.

## [#used-by](#used-by)Used by

- `ike-workspace-maven-plugin` — the `ws:*` goals, all of which read and (for publish variants) write the workspace model.
- `ike-maven-plugin` — `ike:release-publish` writes the workspace VCS state file when invoked from a workspace context; `ike:release-status` reads it.

## [#source](#source)Source

- GitHub: [ike-tooling/ike-workspace-model](https://github.com/IKE-Network/ike-tooling/tree/main/ike-workspace-model)[1]
- Issues: [IKE-Network/ike-issues](https://github.com/IKE-Network/ike-issues)[2]

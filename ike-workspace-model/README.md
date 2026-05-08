# ike-workspace-model

**Documentation:** https://ike.network/ike-tooling/ike-workspace-model/

Data model and conventions for an IKE workspace. Consumed by both
`ike-workspace-maven-plugin` (the `ws:*` goals) and `ike-maven-plugin`
(the `ike:*` release-orchestration goals), so the two plugins share
a single source of truth for what a workspace *is*.

## Core types

* `Workspace` — parsed form of `workspace.yaml`.
* `Subproject` — one repository within the workspace.
* Four-state alignment model — snapshot-aligned, tag-aligned/release,
  tag-aligned/checkpoint, external-consumer, unrelated.

See the [full module documentation](https://ike.network/ike-tooling/ike-workspace-model/)
for the complete model description and the branch-coherence rule.

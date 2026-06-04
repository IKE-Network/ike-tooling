---
date_published: 2026-06-03
date_modified: 2026-06-03
canonical_url: https://ike.network/ike-tooling/ike-maven-plugin/release-cascade.html
---

# The IKE Release Cascade

The IKE foundation is three repositories that must release in topological order, because each consumes the artifacts of the ones above it:

```
ike-tooling  →  ike-docs  →  ike-platform
```

`ike-docs` and `ike-platform` declare `ike-maven-plugin` — and consume `ike-build-standards` — through `${ike-tooling.version}`; `ike-platform` also consumes `ike-docs` through `${ike-docs.version}`. A downstream repo released against a stale upstream pin ships a **split foundation**. The release cascade is the machinery that keeps the three coherent.

## [#a-decentralized-loosely-coupled-model](#a-decentralized-loosely-coupled-model)A decentralized, loosely-coupled model

There is no central manifest. Each foundation repo version-controls its own cascade edges in `src/main/cascade/release-cascade.yaml`, declaring **only its own** upstream and downstream neighbours (`IKE-Network/ike-issues#420`). The full graph is never authored in one place — it is assembled by traversing these per-project files.

`ike-docs/src/main/cascade/release-cascade.yaml`, the middle repo:

```
schema: 1

# Projects this repo consumes. Each names the ${X.version} POM
# property the cascade aligns to the upstream's latest release
# before a release here.
upstream:
  - groupId: network.ike.tooling
    artifactId: ike-tooling
    version-property: ike-tooling.version
    url: https://github.com/IKE-Network/ike-tooling.git

# Projects that consume this repo — the cascade walks forward to
# each after a release here.
downstream:
  - groupId: network.ike.platform
    artifactId: ike-platform
    url: https://github.com/IKE-Network/ike-platform.git
```

The two endpoints are asserted, not inferred. The repo at the head of the cascade declares `head: true` (no `upstream:`); the repo at the end declares `terminal: true` (no `downstream:`). A missing edge would otherwise be indistinguishable from a genuine endpoint, so each endpoint must positively declare itself — a forgotten edge is a manifest error, not a silent omission.

## [#assembly-by-traversal](#assembly-by-traversal)Assembly by traversal

`CascadeAssembler` starts from one repo’s manifest, follows every edge to its neighbours, reads each neighbour’s manifest, and stitches the result into one topologically ordered graph. It enforces two consistency rules:

- **Edge reciprocity** — if A names B downstream, B must name A upstream, and vice versa. A one-sided edge is rejected.
- **Acyclicity** — the consume relation must be a DAG.

Neighbours are resolved **sibling-first, URL-fallback**: a repo checked out as a directory alongside the current one is read from disk; one that is not is shallow-cloned from its edge’s `url` (`IKE-Network/ike-issues#429`). The cascade therefore assembles both on a developer workstation and on a CI agent with a single checkout.

## [#the-goals](#the-goals)The goals

### [#ike-release-publish--one-repo-upstream-aligned](#ike-release-publish--one-repo-upstream-aligned)ike:release-publish — one repo, upstream-aligned

Before cutting a release, `ike:release-publish` reads the repo’s own `release-cascade.yaml` and, for every `upstream` edge, resolves the latest released version of that upstream and bumps the edge’s `version-property` when the POM is behind. A single-repo release is therefore correct on its own — it can never ship on a stale foundation.

The preflight fails on any warning by default; pass `-Dike.release.ignoreWarnings=true` to release past warnings (errors are never ignorable). See `IKE-Network/ike-issues#428`.

### [#ike-release-cascade--the-executor](#ike-release-cascade--the-executor)ike:release-cascade — the executor

Walks the whole cascade. Run from any foundation repo (with the others checked out as siblings); it assembles the graph, walks it in topological order, and runs `ike:release-publish` on every repo that has unreleased changes — skipping repos whose only commits since their last tag are release-cadence bookkeeping.

```
mvn ike:release-cascade                          # release the cascade
mvn ike:release-cascade -DpushRelease=false       # local-only walk
mvn ike:release-cascade -Dike.release.cascade.basedir=/path/to/checkouts
```

### [#ike-cascade-export--the-topology](#ike-cascade-export--the-topology)ike:cascade-export — the topology

Assembles the graph and writes it as JSON or `.properties` — the input a CI meta-runner consumes to generate build-chain edges instead of hand-wiring them (`IKE-Network/ike-issues#403`). Read-only.

```
mvn ike:cascade-export                            # JSON to stdout
mvn ike:cascade-export -Dformat=properties
mvn ike:cascade-export -DoutputFile=target/cascade.json
```

## [#testing-the-cascade-safely](#testing-the-cascade-safely)Testing the cascade safely

Both inspection paths are side-effect-free, so the cascade can be exercised at any time:

- `mvn ike:cascade-export` — proves the per-project manifests assemble into a valid, ordered graph.
- `mvn ike:release-cascade -DpushRelease=false` — walks every repo and reports its state. When the foundation is freshly released, every repo reports **up to date** and nothing is released:

```
Foundation release cascade — 3 repo(s) in order: ike-tooling → ike-docs → ike-platform
─── ike-tooling ───   At v183; no meaningful commits since — skipping (already released).
─── ike-docs ───      At v40; no meaningful commits since — skipping (already released).
─── ike-platform ───  At v66; no meaningful commits since — skipping (already released).
Cascade summary:
  — up to date  ike-tooling  (v183)
  — up to date  ike-docs  (v40)
  — up to date  ike-platform  (v66)
```

## [#running-a-real-cascade](#running-a-real-cascade)Running a real cascade

Check the three foundation repos out as siblings under one directory, then run `ike:release-cascade` (or use the IntelliJ run configuration of the same name) from any of them. The executor releases each repo that has changes, in order, so each repo’s Nexus deploy completes before the next — which `ike:release-publish` then aligns to the just-released upstream — begins.

## [#ci-teamcity](#ci-teamcity)CI / TeamCity

The documented CI model runs one build configuration per foundation repo, with TeamCity — not `ike:release-cascade` — orchestrating. The build-chain edges are generated from `ike:cascade-export`; because the assembler falls back to cloning from each edge’s `url`, the export runs on a bare CI agent that has only one repo checked out.

## [#related](#related)Related

- `IKE-Network/ike-issues#420` — the decentralized per-project model.
- `IKE-Network/ike-issues#419` — the `ike:release-cascade` executor and per-repo upstream alignment.
- `IKE-Network/ike-issues#428` — fail-on-warnings release preflight.
- `IKE-Network/ike-issues#429` — URL-mode cascade resolver.
- [ike-maven-plugin goal reference](index.html)[1].

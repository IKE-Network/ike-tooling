# IKE Release Recovery

When `ws:release-publish` (or a per-subproject `ike:release-publish`)
fails partway through, this document is the decision tree for getting
the workspace back to a known-good state.

> **Audience:** developer recovering an interrupted release.
> **Tooling assumed:** `ws:release-status` (read-only diagnostic).
> Forthcoming `ws:release-rollback` and `ws:release-resume` (Cycle 2+
> of issue [#187](https://github.com/IKE-Network/ike-issues/issues/187))
> will automate the manual steps below; until they ship, follow the
> manual git commands.

## Quick Start

```bash
mvn ws:release-status
```

The output is a punch list — one line per subproject — with a status
glyph and a one-line "next action" footer. Match the per-subproject
status to the section below.

| Glyph | Status | Meaning |
|-------|--------|---------|
| ✓ | clean | No release artifacts in flight; nothing to do. |
| ⚠ | in-flight | A `release/*` branch or unpushed `v*` tag remains from an interrupted run. |
| ✗ | diverged | A `release/*` branch is local-only, but the remote already has the matching tag — release happened elsewhere. |
| ─ | not checked out | Subproject directory is missing; out of scope. |

If every subproject is **clean**, you are done. The release
either finished cleanly or was rolled back already.

## Per-State Recovery

### Clean

No action. Move on.

### In-Flight

The subproject has at least one of:

- A `release/<version>` branch left behind (the release loop did not
  reach the merge-and-delete step).
- A local `v<version>` tag that is not on `origin` (tag was created
  but never pushed).

You have two paths. Choose based on **how far the release got**:

#### Decision: forward-fix vs rollback

Run, in the affected subproject:

```bash
git log --oneline release/<version> | head -10
```

- **No `release: set version to <version>` commit on the branch?**
  The release barely started. **Rollback** is safest.
- **Tag exists locally but not on `origin`, and Nexus has not seen
  this version?** **Forward-fix** — push the tag and finish manually.
- **Tag exists locally and on `origin`, OR the artifact is on Nexus?**
  Treat as **published** — never rewrite a published version.
  See "Forward-fix only" below.

#### Forward-fix

Use when the release made it past Nexus deploy but a later step
(site deploy, GitHub Release, push) failed.

```bash
cd <subproject>
git checkout main
git merge --no-ff release/<version> -m "merge: release <version>"
# bump to next SNAPSHOT if not already done:
mvn ike:post-release -DnewVersion=<next>-SNAPSHOT
git push origin main
git push origin v<version>
git branch -d release/<version>
```

Re-run `ws:release-status` to confirm **clean**.

#### Rollback

Use when the release did not reach the tag step, or when the only
artifacts produced are local.

> **Never roll back a version that already shipped to Nexus or
> appears on `origin` as a tag.** Bump the version and release
> forward instead — see the
> [`feedback_fix_not_workaround`](../memory/feedback_fix_not_workaround.md)
> rule.

```bash
cd <subproject>
git checkout main
# Drop the local tag if it exists (local-only — confirmed not on origin):
git tag -d v<version>          # safe iff `git ls-remote --tags origin v<version>` is empty
git branch -D release/<version>
# If main was advanced by a partial post-release commit, reset to the
# last known-good SHA recorded in checkpoints/:
git log --oneline -5
git reset --hard <pre-release-sha>
```

Re-run `ws:release-status` to confirm **clean**, then `mvn ws:release`
to retry from a known state.

### Diverged

`release/<version>` is local-only but `origin` already has the
`v<version>` tag. The release was completed somewhere else — likely
on the other Syncthing-paired machine, or via a manual recovery.

The local branch is debris. Verify with:

```bash
cd <subproject>
git fetch --tags origin
git ls-remote --tags origin v<version>     # should print a SHA
git log --oneline release/<version> | head
```

If the local branch has nothing the remote tag does not already
contain, delete it:

```bash
git branch -D release/<version>
git checkout main
git pull --ff-only origin main
```

Re-run `ws:release-status` to confirm **clean**.

If the local branch has commits that are **not** on the remote tag,
stop and triage by hand — the two machines disagree about what was
released. Open an issue rather than improvising.

## Per-Failure-Mode Quick Reference

| Failure point | Likely state | Action |
|---------------|--------------|--------|
| `mvn install` failed mid-release | `release/X` branch present, no tag | Rollback. |
| `mvn site` / javadoc failed | `release/X` branch present, no tag | Rollback. Fix the javadoc, re-run. |
| Tag created, push to origin failed | Local tag missing on origin | Forward-fix: `git push origin v<version>`. |
| Nexus deploy failed | Local tag may exist; nothing on Nexus | Rollback if tag local-only; otherwise re-run `mvn deploy` from the tag. |
| Site deploy failed | Tag on origin; site missing | Forward-fix: re-run site goals from the tag (see `ReleaseDraftMojo` retry instructions). |
| GitHub Release create failed | Tag on origin; no GH release | Forward-fix: `gh release create v<version> --generate-notes`. |
| Workspace loop crashed mid-cascade | Some subprojects released, others untouched | `ws:release-status` shows which are in-flight; finish each individually with the table above. |

## Checkpoints

`ws:release-publish` writes a pre-release checkpoint to
`checkpoints/checkpoint-pre-release-<timestamp>.yaml` before touching
any subproject (unless `-DskipCheckpoint=true` was passed).

The checkpoint records each subproject's branch, SHA, and version at
release start. Use it as the rollback target for the
`git reset --hard <pre-release-sha>` step above:

```bash
ls checkpoints/                                    # find the most recent
cat checkpoints/checkpoint-pre-release-*.yaml      # inspect SHAs
```

## What's Coming (Cycle 2+)

Cycle 2 of [#187](https://github.com/IKE-Network/ike-issues/issues/187)
adds a machine-readable `.ike/release-state.json` written by
`ws:release-publish` at every phase boundary. `ws:release-status` will
then merge that state file with the git evidence here for richer
findings.

Cycle 3 adds:

- `ws:release-rollback` — automates the **Rollback** flow above when
  the state file confirms nothing reached the remote.
- `ws:release-resume` — automates the **Forward-fix** flow above by
  picking up the recorded checkpoint and replaying the remaining
  phases.

Until then, this document is the contract.

## See Also

- `IKE-RELEASE.md` — issue tracking, labels, milestones
- `IKE-WORKSPACE.md` — workspace manifest schema and goal reference
- Issue [#175](https://github.com/IKE-Network/ike-issues/issues/175) —
  SNAPSHOT preflight (prevents the `ike-parent-105.pom` failure class)
- Issue [#187](https://github.com/IKE-Network/ike-issues/issues/187) —
  Failed-release recovery toolkit (this doc is Cycle 1, Phase 7)

# IKE Commit Message Standard

Every commit in an IKE repository follows this standard. It applies to
direct commits to `main`, feature branches, and workspace cascades
alike. `ws:commit-publish` and `ike:release-publish` both rely on the trailer
format defined below for issue↔commit linkage.

## Subject and body

- **Subject line**: imperative mood, fewer than 72 characters, no
  trailing period. Examples:
  - `Bump ike-parent to 58 across komet workspace`
  - `Fix stamp persistence on pattern inactivation`
  - `Add scaffold rule for shared site-theme CSS`
- **Blank line**, then a body explaining the *why* and any non-obvious
  trade-offs. Wrap at ~72 characters.
- Note user-visible behavior changes in the body so release notes can
  pick them up.

## Issue association

**Every commit MUST reference at least one tracked issue.** If no issue
exists for the change, file one first. This includes:

- Bug fixes and feature work.
- Refactors, dependency bumps, documentation edits, scaffolding
  changes, and other "chores."
- Release cascades and workspace alignment commits.

The only commits exempt from this rule are those produced
mechanically by tooling (e.g., the `ike:release-publish` version-bump
commit) — and those tooling pipelines still cite the milestone or
release tag.

### Trailer format

References go in **commit trailers**, one per line, separated from the
body by a blank line:

    Bump ike-parent to 58 across komet workspace

    Picks up the in-place sha rewrite from the workspace plugin so
    workspace.yaml stops accumulating duplicate keys on checkpoint.

    Fixes IKE-Network/ike-issues#387
    Refs ikmdev/komet-desktop#12

### Use the full `<owner>/<repo>#N` form

Always write the full `<owner>/<repo>#N` reference, even when the issue
lives in the commit's own repo. Reasons:

- Cross-org references are common (IKE-Network commits resolving
  `ikmdev/komet-desktop` issues, and vice versa) and ambiguous short
  `#N` resolves to the commit's own repo only.
- Mirrored or migrated repos retain the link.
- `git log --grep` queries become unambiguous.

### Verb choice

- **`Fixes`** / **`Closes`** — the commit completes the issue.
  `ike:release-publish` closes the referenced issue when the release
  ships (IKE-Network/ike-issues#799). Do **not** rely on GitHub's native
  `Fixes #N` auto-close: it only fires when the issue is in the commit's
  **own repository**, and IKE keeps every issue in a separate tracker
  repo — so a cross-repo trailer is a link GitHub records but never acts
  on. The release closes it for you; there is no manual close step.
- **`Refs`** — partial progress, related work, or cross-repo links
  that must not close the issue on release.

One trailer per line. Multiple trailers are allowed when one commit
spans multiple issues.

## Interaction with `pending-release`

When a commit lands that completes an issue but the release has not
shipped:

1. The commit uses `Fixes <owner>/<repo>#N` in the trailer.
2. The issue receives the `pending-release` label.

The trailer is the durable, queryable record of the link; the label
is the live state. When the release ships, `ike:release-publish`:

1. **Closes** every issue referenced by a closing trailer in the
   release range (full `<owner>/<repo>#N`, cross-repo included),
   posting an audit comment that links the release
   (IKE-Network/ike-issues#799). This stands in for GitHub's native
   auto-close, which never fires here because the issue and the commit
   live in different repositories.
2. Closes the matching milestone.
3. Removes `pending-release` from those issues (per #390).

There is no manual close step — the closing trailer is the whole
contract.

See [IKE-RELEASE.md](IKE-RELEASE.md) for the full release workflow.

## Checkpoint and Release Reporting

Checkpoints and releases differ in their relationship to issues:

**Checkpoints** report what's accumulated since the last release tag.
They:

- Walk commits in `<previous-v-tag>..HEAD` per subproject.
- Parse `Fixes`/`Closes`/`Resolves` trailers and include the issue
  list in the checkpoint coordinate YAML (under each subproject's
  `issues-since-last-release:` array) and the checkpoint markdown
  report.
- **Do not close any issues.**
- **Do not remove `pending-release` labels.**

A checkpoint is a snapshot for testing or internal consumption — it
does not claim that any issue is "released." Issues remain
`pending-release` until an actual release ships.

**Releases** report what shipped and close it. They:

- Generate release notes from the GitHub milestone when one matches.
- Close that milestone automatically.
- Remove the `pending-release` label from every issue referenced by
  closing trailers in the release range (#390).
- Use `Fixes`/`Closes`/`Resolves` trailers as the authoritative
  record of what shipped.

The distinction matters: a `Fixes IKE-Network/ike-issues#123` trailer
that lands in a commit between releases gets:

1. **At commit time** — issue auto-closes on push to the default
   branch (GitHub behavior); `pending-release` label applied per
   the convention above.
2. **At checkpoint time** — reported in the per-subproject
   "issues since last release" list. Nothing is closed or unlabeled.
3. **At release time** — `pending-release` is removed.

## Documentation Impact

Every issue that introduces a new convention, renames or removes a
goal, changes a plugin groupId or artifact name, or otherwise alters
how users or agents interact with the project, must include a
**Documentation Impact** section in the issue body before
implementation begins. The section enumerates every documentation
surface the change will touch, with a checklist that is part of the
issue's close criteria.

Documentation surfaces to consider:

- `IKE-*.md` standards in `ike-build-standards/src/main/standards/`
- `ike-workspace-conventions.adoc` and related `src/main/docs/*.adoc`
- `README.md` files (every affected repo)
- `CLAUDE.md` and `CLAUDE-<name>.md` files (every affected repo)
- `src/site/asciidoc/index.adoc` (and friends) where applicable
- `docs/design/*.md` design notes referencing the changed surface
- Deployed Maven sites at `ike.network/<repo>/`
- The `ike-network-site` landing page
- Inline Javadoc (`@see`, `{@link}`, class-level summaries)
- `workspace.yaml` bootstrap comments in example workspaces

Mark each surface as **affected** (the doc edit lands in the same
commit or PR as the code change) or **not affected** (verified no
references exist). Each affected surface gets a checklist item that
must be checked off before the issue is closed.

**The doc edit is never deferred.** It lands in the same change as
the code it documents — so every release is self-consistent: the
docs in release N describe the code in release N. The only thing
that legitimately defers is *deployment* — a site's published copy
at `ike.network/<repo>/` refreshes when that repo next releases.
But the edit to the `.adoc`/`.md` source is always part of the
change itself.

Site `.adoc` and standards `.md` files do **not** update
automatically; a human commits the edit. Marking a doc surface
"deferred" and landing the edit in a later sweep produces a release
whose docs contradict its own code — exactly the stale-docs failure
this rule exists to prevent. IKE-Network/ike-issues#398 did this
(shipped the `ike:site-*` convergence code in v175, deferred the
site docs to a later sweep, leaving v175's site documenting deleted
goals); IKE-Network/ike-issues#401 closed the loophole. Do not
defer doc edits.

If the documentation impact is genuinely zero — a rare case for pure
internal refactors with no surface change — state that explicitly
with a one-sentence justification.

This rule applies to all standards-changing issues, including the
issue that introduces this rule. Reviewers should reject (or request
amendment to) issues missing the section.

## AI attribution

Do not add any AI-attribution trailer — no `Assisted-by`, no
`Co-Authored-By`, no `Generated-with`. The developer is the sole
author. Normal trailers that would exist independent of AI tooling
(`Signed-off-by`, `Reviewed-by`, issue refs) remain appropriate.

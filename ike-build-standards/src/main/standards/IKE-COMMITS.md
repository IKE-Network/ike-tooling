# IKE Commit Message Standard

Every commit in an IKE repository follows this standard. It applies to
direct commits to `main`, feature branches, and workspace cascades
alike. `ws:commit` and `ike:release-publish` both rely on the trailer
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

- **`Fixes`** / **`Closes`** — the commit completes the issue. GitHub
  auto-closes the issue when the trailer references the commit's own
  repo. For cross-org trailers, close manually after the consuming
  repo's release lands.
- **`Refs`** — partial progress, related work, or cross-repo links
  that must not auto-close on this commit.

One trailer per line. Multiple trailers are allowed when one commit
spans multiple issues.

## Interaction with `pending-release`

When a commit lands that completes an issue but the release has not
shipped:

1. The commit uses `Fixes <owner>/<repo>#N` in the trailer.
2. The issue receives the `pending-release` label.

The trailer is the durable, queryable record of the link; the label
is the live state. `ike:release-publish` closes the milestone when one
matches; cross-org issues need a manual close once the consuming
repo's release lands.

See [IKE-RELEASE.md](IKE-RELEASE.md) for the full release workflow.

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

Mark each surface as **affected** (will be updated), **not affected**
(sweep verified no references exist), or **deferred** (e.g., live
site docs that update automatically via the next release cycle).
Each affected surface gets a checklist item that must be checked off
before the issue is closed.

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

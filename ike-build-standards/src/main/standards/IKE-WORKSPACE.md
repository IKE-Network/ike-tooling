# IKE Workspace Conventions

## What is an IKE Workspace?

An IKE Workspace is a multi-repository development environment managed
through `workspace.yaml` — a YAML manifest that declares all subprojects
and their inter-repository dependencies.

Workspace operations are implemented as Maven plugin goals in
`ike-workspace-maven-plugin` (groupId `network.ike.platform`),
invokable via the `ws:` prefix.

Single-repo goals (release, setup, asciidoc, etc.) remain in
`ike-maven-plugin` (groupId `network.ike.tooling`), invokable via
the `ike:` prefix.

Both prefixes require the corresponding groupIds in
`~/.m2/settings.xml` `<pluginGroups>` — see Prerequisites below.

### Schema Migration (#150)

As of the #150 schema rename, the manifest's top-level key is
`subprojects:` (was `components:`). The `component-types:` and `groups:`
sections have been removed — subproject types are now a compile-time enum
(`network.ike.workspace.SubprojectType`) rather than runtime configuration.

Workspaces that still use the legacy `components:` schema are automatically
migrated on the first run of `ws:align`. All other goals hard-cut on the
legacy schema with an error pointing users at `ws:align`. The migration is
idempotent — running it twice is a no-op.

### POM Changes — Use Tooling, Not Manual Edits

Never use `sed`, `awk`, or regex-based POM manipulation. Use:

- **`ws:scaffold-publish`** for routine workspace-state reconciliation
  (parent cascade, inter-subproject alignment, denormalized field
  sync, scaffold conventions). Pin a specific non-current parent
  version with `-DparentVersion=<v>`.
- **`ws:align-publish`** as the standalone shortcut when only
  inter-subproject dependency-version alignment is needed.
- **OpenRewrite** for structural migrations:
  `mvn rewrite:run -Drewrite.activeRecipes=network.ike.MigrateGroupIds`
- **`PomRewriter`** (programmatic API in ike-workspace-maven-plugin) for
  AST-aware version updates that preserve formatting

## Prerequisites

### Maven Settings

Add both IKE plugin groups to `<pluginGroups>` in `~/.m2/settings.xml`
so the `ike:` and `ws:` prefixes resolve to the correct plugins:

```xml
<settings>
  <pluginGroups>
    <pluginGroup>network.ike.tooling</pluginGroup>
    <pluginGroup>network.ike.platform</pluginGroup>
  </pluginGroups>
</settings>
```

Maven matches `<pluginGroup>` entries as exact groupIds, not as
namespace prefixes — both entries are required.

### Workspace POM

The workspace root must contain a `pom.xml` that declares both plugins:

```xml
<project xmlns="http://maven.apache.org/POM/4.1.0" ...>
    <modelVersion>4.1.0</modelVersion>

    <!-- Inherit ike-parent to get managed plugin versions
         (including ike-tooling.version). Use the latest
         released ike-parent version from Nexus. -->
    <parent>
        <groupId>network.ike.platform</groupId>
        <artifactId>ike-parent</artifactId>
        <version>LATEST-RELEASE</version>
        <relativePath/>
    </parent>

    <groupId>local.aggregate</groupId>
    <artifactId>ike-workspace</artifactId>
    <version>1-SNAPSHOT</version>
    <packaging>pom</packaging>

    <build>
        <plugins>
            <!-- Workspace goals (ws:*) — managed by ike-parent -->
            <plugin>
                <groupId>network.ike.platform</groupId>
                <artifactId>ike-workspace-maven-plugin</artifactId>
            </plugin>
            <!-- Single-repo goals (ike:*) — managed by ike-parent -->
            <plugin>
                <groupId>network.ike.tooling</groupId>
                <artifactId>ike-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

    <!-- File-activated profiles for partial checkout -->
    <profiles>
        <profile>
            <id>subproject-name</id>
            <activation>
                <file><exists>${project.basedir}/subproject-name/pom.xml</exists></file>
            </activation>
            <subprojects>
                <subproject>subproject-name</subproject>
            </subprojects>
        </profile>
        <!-- Repeat for each subproject -->
    </profiles>
</project>
```

File-activated profiles enable incremental IntelliJ builds: only checked-out
subprojects participate in the reactor. Missing subprojects are silently skipped.

## workspace.yaml Manifest

The manifest lives at the workspace root alongside `pom.xml`:

```yaml
schema-version: "1.0"
generated: 2026-02-25

defaults:
  branch: main

subprojects:
  ike-platform:
    type: infrastructure
    repo: git@github.com:IKE-Network/ike-platform.git
    version: "24-SNAPSHOT"
    depends-on: []

  tinkar-core:
    type: software
    repo: git@github.com:ikmdev/tinkar-core.git
    version: "1.80.0-SNAPSHOT"
    depends-on:
      - subproject: ike-platform
        relationship: build
```

Workspaces on the legacy `components:` schema are automatically migrated
on the first run of `ws:align`. See "Schema Migration" above.

### Manifest Fields

| Field | Required | Description |
|-------|----------|-------------|
| `schema-version` | Yes | Schema version for forward compatibility |
| `defaults.branch` | Yes | Default branch for all subprojects |
| `subprojects` | Yes | Map of subproject-name → subproject definition |

### Subproject Fields

Each subproject entry is keyed by its name (which must match the
directory name on disk). The entry is a map of these fields:

| Field | Required | Description |
|-------|----------|-------------|
| `type` | Yes | One of `software`, `infrastructure`, `document` (values of `SubprojectType` enum) |
| `repo` | Yes | Git clone URL |
| `branch` | No | Override default branch |
| `version` | No | Current version string (null/`~` for unversioned) |
| `groupId` | No | Maven groupId for version updates |
| `parent` | No | Name of the workspace subproject that provides this one's Maven parent POM |
| `depends-on` | No | List of dependency declarations |
| `sha` | No | Pinned commit SHA (written by `ws:checkpoint`) |
| `maven-version` | No | Override `defaults.maven-version` for this subproject |
| `notes` | No | Free-text migration notes |

### Dependency Relationships

```yaml
depends-on:
  - subproject: ike-platform
    relationship: build      # needs compiled artifacts
  - subproject: tinkar-core
    relationship: content    # references architecture/concepts
  - subproject: ike-platform
    relationship: tooling    # uses CLI tools or plugins
```

Relationship types matter for cascade analysis: `build` dependencies require
rebuild; `content` dependencies may require only review.

### Version Cascade Mechanisms

When `ws:feature-start` creates a feature branch, it cascades
branch-qualified SNAPSHOT versions to downstream subprojects via three
complementary mechanisms:

1. **version-property** (workspace.yaml `depends-on` declaration):
   Explicit `version-property: <prop-name>` in workspace.yaml tells
   `feature-start` which POM property to update. This is the most
   precise and recommended for cross-subproject version tracking.

2. **cascadeBomProperties** (naming convention):
   Scans POM `<properties>` blocks for entries matching
   `<{subproject-name}.version>`. When a workspace subproject publishes
   artifacts, any downstream POM that tracks the upstream version via
   this naming convention gets automatically updated. No workspace.yaml
   declaration needed — the convention is enough.

3. **cascadeBomImports** (workspace-internal BOM imports):
   Scans `<dependencyManagement>` for `<type>pom</type>` /
   `<scope>import</scope>` entries published by workspace subprojects.
   Updates the BOM import version to the branch-qualified SNAPSHOT.

**Cascade gap detection:** `ws:feature-start` reports cascade gaps when
a dependency edge has *none* of the above mechanisms. This means the
downstream subproject may resolve stale versions from external BOMs
instead of the feature branch versions. The gap detection accounts for
all three mechanisms — convention-based properties suppress false positives.

## Goal Reference

Most mutating goals come in `-draft` / `-publish` pairs.
Draft previews the operation; publish executes it.
Run `ws:help` for the complete auto-discovered list.

### Inspection & Setup

| Goal | Description |
|------|-------------|
| `ws:scaffold-init` | Bootstrap a workspace: create `workspace.yaml` if absent, clone declared-but-missing subprojects (folds the retired `ws:create` and `ws:init` per #393) |
| `ws:scaffold-draft` | Drift report — manifest consistency, denormalized field sync, parent cascade, scaffold conventions, alignment (folds the retired `ws:fix`, `ws:verify`, `ws:set-parent` (draft), `ws:scaffold-upgrade` (draft) per #393) |
| `ws:verify-convergence` | Transitive dependency convergence (slow) |
| `ws:overview` | Dashboard: manifest, graph, status, cascade |
| `ws:graph` | Print dependency graph (text or `-Dformat=dot`) |
| `ws:cascade` | Show downstream impact of a change |
| `ws:pull` | `git pull --rebase` across repos (requires clean trees) |
| `ws:stignore` | Generate `.stignore` files for Syncthing |

### Manifest Management (convergence pattern)

`ws:scaffold-publish` is the routine workspace-state reconciler. It
drives the `ReconcilerRegistry` and converges field normalization,
parent cascade, scaffold conventions, and inter-subproject alignment
in a single pass. Each reconciler can be individually disabled.

| Invocation | Description |
|------------|-------------|
| `ws:scaffold-publish` | Apply all reconcilers (the default). |
| `ws:scaffold-publish -DparentVersion=<v>` | Pin the parent cascade to a specific non-current version (replaces the retired `ws:set-parent`). |
| `ws:scaffold-publish -DupdateFields=false` | Skip the field-normalization reconciler (the FieldNormalizationReconciler that folded `ws:fix`). |
| `ws:scaffold-publish -DupdateParent=false` | Skip the parent-cascade reconciler. |
| `ws:scaffold-publish -DupdateScaffold=false` | Skip the scaffold-convention reconciler (folded the retired `ws:scaffold-upgrade`). |
| `ws:scaffold-publish -DupdateAlignment=false` | Skip the alignment reconciler (use `ws:align-publish` standalone for the alignment-only case). |
| `ws:align-draft` / `-publish` | Standalone shortcut for the alignment-only case; shares `AlignmentReconciler` logic with `ws:scaffold-publish` and the feature/release lifecycles. |
| `ws:reconcile-branches-draft` / `-publish` | Reconcile `workspace.yaml` branch fields against on-disk git state (recovery / rare use). |
| `ws:versions-upgrade-draft` / `-publish` | Plan-driven upgrades of parent/property/plugin versions when the scaffold manifest does not cover the upgrade. |

### Feature Branching

| Goal | Description |
|------|-------------|
| `ws:feature-start-draft` / `-publish` | Create feature branch with qualified versions |
| `ws:feature-finish-merge-draft` / `-publish` | No-ff merge (preserves history) |
| `ws:feature-finish-squash-draft` / `-publish` | Squash merge (single commit) |
| `ws:feature-abandon-draft` / `-publish` | Delete feature branch |
| `ws:switch-draft` / `-publish` | Switch branch across workspace |
| `ws:update-feature-draft` / `-publish` | Rebase feature onto main |

### Release & Checkpoint

| Goal | Description |
|------|-------------|
| `ws:release-draft` / `-publish` | Release release-pending subprojects in dependency order |
| `ws:checkpoint-draft` / `-publish` | Tag all subprojects, record SHAs |
| `ws:post-release` | Bump to next development version |
| `ws:release-notes` | Generate notes from GitHub milestone |

### VCS Bridge (Syncthing)

| Goal | Description |
|------|-------------|
| `ws:commit` | Commit across repos (`-DaddAll=true -Dpush=true -Dmessage="..."`) |
| `ws:push` | Push all subprojects (warns about uncommitted changes) |
| `ws:sync` | Reconcile state after machine switch |

### Common Options

| Option | Applicable Goals | Description |
|--------|------------------|-------------|
| `-Dworkspace.manifest=<path>` | All | Path to workspace.yaml (auto-detected) |
| `-Dsubproject=<name>` | `ws:add`, `ws:remove`, `ws:release-publish` | Restrict to named subproject |
| `-Dfeature=<name>` | feature-start, feature-finish, feature-abandon | Feature name |
| `-DtargetBranch=<name>` | feature-finish, switch | Target branch (default: `main`) |
| `-DparentVersion=<v>` | scaffold-publish | Pin the parent cascade to a specific non-current version |
| `-DupdateFields=false` | scaffold-publish | Skip the field-normalization reconciler |
| `-DupdateParent=false` | scaffold-publish | Skip the parent-cascade reconciler |
| `-DupdateScaffold=false` | scaffold-publish | Skip the scaffold-convention reconciler |
| `-DupdateAlignment=false` | scaffold-publish | Skip the alignment reconciler |
| `-Dmessage=<msg>` | commit, feature-finish | Commit message |
| `-Dlabel=<name>` | checkpoint | Checkpoint label (auto-derived if omitted) |
| `-DskipCheckpoint=true` | release-publish | Skip pre-release checkpoint |
| `-Dforce=true` | feature-abandon, cleanup, remove | Skip confirmation prompt |
| `-DdeleteRemote=true` | feature-abandon | Also delete remote branches |
| `-DkeepBranch=true` | feature-finish-squash | Keep the feature branch (only valid when squashing) |
| `-Dstrategy={merge,rebase}` | update-feature | Strategy for incorporating main into feature |

### Preflight Validation

Multi-repo goals validate that all subproject working trees are clean
before starting. If any subproject has uncommitted changes, the goal
fails immediately with a list of affected repos and files, along with
the specific `ws:commit` command to resolve it. No partial
modifications occur.

**Publish goals with hard preflight:** `release`, `align`,
`scaffold-publish`, `checkpoint`, `pull`, `switch`, `feature-start`,
`feature-finish-*`, `feature-abandon`, `update-feature`

**Draft goals:** warn about uncommitted changes that would block the
corresponding `-publish` goal, but still run the preview.

**`ws:commit`:** skips VCS bridge catch-up when there are pending
changes to commit, preventing branch-switch conflicts. Warns at
WARN level when skipping repos with unstaged changes.

**`ws:push`:** warns about uncommitted changes after pushing, and
automatically sets upstream tracking for new branches.

### Report Generation Contract

Every `ws:*` goal that assesses or mutates cross-subproject state **must**
call `AbstractWorkspaceMojo.writeReport(WsGoal goal, String markdownBody)`
at the end of execution. The report is persisted under the workspace
`session/` directory as `ws꞉<goal-name>.md` (using U+A789, not `:`, to
cluster in IDE file browsers). Overwritten on each run.

The `goal` parameter is the typed `WsGoal` enum entry, not a string
literal — this keeps subprocess exec, report paths, and preflight
messages compiler-visible and guarded by `WsGoalExhaustivenessTest`.

Exceptions:

* Pure help / bootstrap goals (`ws:help`, `ws:scaffold-init`) print
  directly to the console; no per-goal report file.
* Aggregator goals (`ws:report`) aggregate the other per-goal reports
  rather than producing their own.
* Publish mojos that extend a draft parent (e.g.
  `WsAlignPublishMojo extends WsAlignDraftMojo`) inherit the report via
  `super.execute()` — the draft writes a report keyed on whichever
  variant actually ran, via the `publish ? WsGoal.X_PUBLISH : WsGoal.X_DRAFT`
  ternary.

## Version Convention

Feature branches use branch-qualified versions:

```
<base-version>-<safe-branch-name>-SNAPSHOT
```

The main branch uses the unqualified version:

```
<base-version>-SNAPSHOT
```

`ws:feature-start` sets this automatically by updating all POM files in the
reactor. When creating files or modifying POMs in a workspace, respect the
branch-qualified version already set.

Safe branch name: replace `/` with `-` in the Git branch name.

## Maven 4 Project-Local Repository

Each workspace isolates installed artifacts via `.mvn/maven.properties`:

```
maven.repo.local.path.installed=${session.rootDirectory}/.mvn/local-repo
```

Do not modify this configuration. Do not reference artifacts from
other workspaces' local repositories.

## Syncthing

Working trees are synced between machines via Syncthing.
Use `ws:stignore` to generate deterministic `.stignore` files that exclude:

- `**/target`
- `**/.git`
- `**/.idea`
- `**/.DS_Store`
- `**/.claude/worktrees`
- `**/.mvn/local-repo`

Each machine has independent Git state, build output, and IDE config.
`ws:scaffold-init` is Syncthing-aware: when a directory already exists
(synced by Syncthing but not yet a git repo), it runs `git init` +
`git reset` instead of `git clone`.

## Partial Checkout

File-activated profiles in the workspace POM enable partial checkout:
only cloned subprojects participate in the reactor. This supports:

- **Incremental IntelliJ builds**: Open the workspace POM; only checked-out
  modules appear in the project tree.
- **Selective `mvn -pl -am`**: Build a specific subproject and its
  dependencies within the workspace.
- **New developer onboarding**: Clone workspace, run `ws:scaffold-init`,
  build immediately with the set that has been checked out.

## Checkpoint Files

`ws:checkpoint` records per-subproject state to
`checkpoints/checkpoint-<name>.yaml`:

```yaml
checkpoint:
  name: "release-1.0"
  created: "2026-03-20T17:00:00Z"
  subprojects:
    ike-platform:
      sha: "a1b2c3d..."
      short-sha: "a1b2c3d"
      branch: "main"
      type: infrastructure
      version: "24-SNAPSHOT"
```

Checkpoint files are committed to the workspace repository.
Optional tagging (`-Dtag=true`) creates `checkpoint/<name>/<subproject>`
tags in each subproject's repo.

## Workspace Release Orchestration (`ws:release-publish`)

`ws:release-publish` automates multi-subproject release across a workspace.
It replaces manual per-subproject release sequences with a single
orchestrated workflow that respects inter-repository dependency order.

### The Self-Limiting Cascade

The release is *self-limiting*: only checked-out repositories with
commits since their last release tag are candidates. Subprojects that
are not checked out or have no changes are silently skipped. This
means the release scope is determined by the intersection of two sets:

1. Subprojects physically present in the workspace (checked out)
2. Subprojects with commits since their last release tag (release-pending)

A workspace with three of ten subprojects checked out will release
at most three subprojects — and only those with actual changes.

### Workflow

The goal executes five phases:

1. **Scan** — Walk the workspace manifest, identify checked-out repos.
2. **Filter release-pending** — For each checked-out repo, compare HEAD
   against the last release tag. Only repos with new commits are candidates.
3. **Topological sort** — Order candidates by dependency graph so that
   upstream subprojects release before their dependents.
4. **Release in order** — For each candidate (in topo order):
   - Strip `-SNAPSHOT` from the version
   - Build and verify
   - Tag the release commit
   - Optionally push (`-Dpush=true`)
   - Bump to the next SNAPSHOT version
5. **Update cross-references** — After each release, update parent
   version references in downstream POMs that depend on the just-released
   subproject. This keeps the cascade self-consistent: when `ike-platform`
   releases version 24, downstream subprojects that reference
   `ike-platform` as a parent are updated to `<version>24</version>`
   before they build.

### Pre-Release Checkpoint

By default, `ws:release-publish` creates a checkpoint before the first
release to enable recovery. Use `-DskipCheckpoint=true` to bypass this.

### Options

| Option | Default | Description |
|--------|---------|-------------|
| `-Dsubproject=<name>` | (all release-pending) | Release only the named subproject (and its release-pending dependents) |
| `-DdryRun=true` | `false` | Show the release plan without executing |
| `-Dpush=true` | `false` | Push tags and commits to origin after each release |
| `-DskipCheckpoint=true` | `false` | Skip the pre-release checkpoint |

### Examples

```bash
# Dry run — see what would be released and in what order
mvn ws:release-draft

# Release all release-pending subprojects, push results
mvn ws:release-publish

# Release only ike-platform and its release-pending dependents
mvn ws:release-publish -Dsubproject=ike-platform

# Release without creating a checkpoint
mvn ws:release-publish -DskipCheckpoint=true
```

### Dry Run Output

A dry run prints the release plan without executing:

```
[INFO] === Workspace Release Plan (DRY RUN) ===
[INFO] Release-pending subprojects (topo order):
[INFO]   1. ike-platform       24-SNAPSHOT → 24 → 25-SNAPSHOT
[INFO]   2. tinkar-core         1.80.0-SNAPSHOT → 1.80.0 → 1.81.0-SNAPSHOT
[INFO] Cross-reference updates:
[INFO]   tinkar-core: ike-platform parent 24-SNAPSHOT → 24
[INFO] Pre-release checkpoint: checkpoint/pre-release-20260320
[INFO] === No changes made (dry run) ===
```

## Auto-Generated BOM (`ike:generate-bom`)

`ike:generate-bom` produces a standalone Bill of Materials (BOM) POM
from `ike-parent`'s `<dependencyManagement>` section. The generated
BOM resolves all property references (`${project.version}`,
`${tinkar.version}`, etc.) to literal values, producing a self-contained
POM that external consumers can import without inheriting `ike-parent`.

### How It Works

The goal is bound to the `generate-resources` phase in the `ike-bom`
stub module. During a normal reactor build:

1. `ike-parent` builds first (it is earlier in the reactor order).
2. `ike-bom` reaches `generate-resources`.
3. `ike:generate-bom` reads `ike-parent`'s resolved model from the reactor.
4. All managed dependencies are extracted with property references
   resolved to their literal values.
5. A standalone `pom.xml` is written that replaces the stub for
   `install`/`deploy`.

### Zero Maintenance

The BOM is entirely derived from `ike-parent`. Adding a new managed
dependency to `ike-parent`'s `<dependencyManagement>` automatically
includes it in the next BOM build. There is no separate file to
maintain, no manual synchronization, and no risk of drift.

### Consumer Usage

External projects that do not inherit from `ike-parent` can import
the BOM for version alignment:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>network.ike.platform</groupId>
            <artifactId>ike-bom</artifactId>
            <version>26</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### Options

| Option | Default | Description |
|--------|---------|-------------|
| `-Dbom.source=<artifactId>` | `ike-parent` | Artifact ID of the POM whose `dependencyManagement` is extracted |

### Example

```bash
# Normal reactor build generates the BOM automatically
mvn clean install

# Build only the BOM (requires ike-parent in reactor)
mvn clean install -pl ike-bom -am
```

## Gitflow Workflow — End to End

### Single-Subproject Feature

A feature that touches only one subproject (e.g., `tinkar-core`):

```bash
# Start the feature — creates feature/add-nid-index branch, sets
# version to 1.80.0-add-nid-index-SNAPSHOT
mvn ws:feature-start -Dfeature=add-nid-index -Dsubproject=tinkar-core

# Work in tinkar-core/, commit normally
cd tinkar-core
# ... edit, build, test ...
git add -A && git commit -m "feat: add NID index for faster lookups"

# Preview the merge
mvn ws:feature-finish-squash-draft -Dfeature=add-nid-index
# Output:
#   tinkar-core: merge feature/add-nid-index → main
#   Version: 1.80.0-add-nid-index-SNAPSHOT → 1.80.0-SNAPSHOT
#   Tag: tinkar-core-1.80.0-add-nid-index-merge

# Merge and push
mvn ws:feature-finish-squash-publish -Dfeature=add-nid-index
```

### Multi-Subproject Feature

A feature spanning `ike-platform` and `tinkar-core`:

```bash
# Start across all checked-out subprojects
mvn ws:feature-start -Dfeature=new-renderer
# Creates feature/new-renderer in both repos
# ike-platform: 24-new-renderer-SNAPSHOT
# tinkar-core:  1.80.0-new-renderer-SNAPSHOT

# Work across both repos, commit in each
cd ike-platform
# ... edit pipeline code ...
git add -A && git commit -m "feat: add weasyprint2 renderer support"

cd ../tinkar-core
# ... update build config ...
git add -A && git commit -m "feat: enable weasyprint2 for tinkar docs"

# Save a checkpoint for team visibility
mvn ws:checkpoint-publish -Dlabel=new-renderer-wip

# Preview the coordinated merge
mvn ws:feature-finish-squash-draft -Dfeature=new-renderer
# Output:
#   ike-platform: merge feature/new-renderer → main
#     Version: 24-new-renderer-SNAPSHOT → 24-SNAPSHOT
#   tinkar-core: merge feature/new-renderer → main
#     Version: 1.80.0-new-renderer-SNAPSHOT → 1.80.0-SNAPSHOT

# Merge and push all
mvn ws:feature-finish-squash-publish -Dfeature=new-renderer
```

### Release After Feature

After merging a feature, release the affected subprojects:

```bash
# See what needs releasing
mvn ws:release-draft
# Output:
#   Release-pending subprojects (topo order):
#     1. ike-platform       24-SNAPSHOT → 24 → 25-SNAPSHOT
#     2. tinkar-core         1.80.0-SNAPSHOT → 1.80.0 → 1.81.0-SNAPSHOT
#   Cross-reference updates:
#     tinkar-core: ike-platform parent 24-SNAPSHOT → 24

# Execute the release
mvn ws:release-publish
# Releases ike-platform first (upstream), then tinkar-core
# Tags: ike-platform-24, tinkar-core-1.80.0
# Post-release versions: 25-SNAPSHOT, 1.81.0-SNAPSHOT
```

## Troubleshooting

### "Cannot X — uncommitted changes in:"

All multi-repo publish goals require clean working trees. The error
lists each blocking repo and its uncommitted files, along with the
command to resolve:

```bash
# Commit all pending changes across the workspace:
mvn ws:commit -DaddAll=true -Dmessage="your commit message"

# Or commit and push in one step:
mvn ws:commit -DaddAll=true -Dpush=true -Dmessage="your commit message"

# Then retry the blocked goal:
mvn ws:align-publish
```

Draft goals warn about uncommitted changes but still run the preview.

### Stale Clones on CI

`ws:scaffold-init` fetches and rebases existing clones when the
working tree is clean. If clones are stale (e.g., parent POM bumps
not applied), re-run `ws:scaffold-init`. If rebase conflicts occur,
delete the subproject directory and let `ws:scaffold-init` re-clone.

### Recovery from Failed `ws-release`

If `ws:release-publish` fails mid-cascade (e.g., build failure in the
second subproject), the pre-release checkpoint file records the state
of every subproject before the release started. Run `mvn ws:release-status`
first — it's read-only and reports what state each subproject is in plus
a recommended recovery step. Re-running `mvn ws:release-publish` skips
subprojects that were already tagged and released — it resumes from
the point of failure.

```bash
# Check the checkpoint to see what was released
cat checkpoints/checkpoint-pre-release-*.yaml

# Re-run — already-released subprojects are skipped
mvn ws:release-publish
```

### Merge Conflicts in `feature-finish`

When `feature-finish` encounters a merge conflict, it stops in the
conflicting repository. Resolve manually:

```bash
cd <conflicting-subproject>
# Resolve conflicts in the affected files
git add <resolved-files>
git commit

# Re-run feature-finish — already-merged subprojects are skipped
mvn ws:feature-finish-squash-publish -Dfeature=my-feature
```

### Plugin Prefix Not Resolving

If `mvn ws:overview` fails with "No plugin found for prefix 'ws'":

1. Verify `~/.m2/settings.xml` contains the plugin groups:

```xml
<pluginGroups>
  <pluginGroup>network.ike.tooling</pluginGroup>
  <pluginGroup>network.ike.platform</pluginGroup>
  <pluginGroup>network.ike.docs</pluginGroup>
</pluginGroups>
```

2. Verify the workspace `pom.xml` declares `ike-workspace-maven-plugin` in `<build><plugins>`.

3. Verify `ike-workspace-maven-plugin` is installed in the local repository:

```bash
mvn install -pl ike-workspace-maven-plugin -f <path-to-ike-platform>/pom.xml
```

### Subproject Not Found in Manifest

If a goal reports "subproject not found" for a name you expect to exist:

- Check spelling: subproject names in `workspace.yaml` are case-sensitive
  and must match directory names exactly.
- Check checkout: some goals only operate on checked-out subprojects.
  Run `mvn ws:overview` to see which subprojects are present.

## Key Rules

- Never use `${revision}` for version indirection. Versions are literal in POMs.
- All reactor modules share a unified version.
- The version in the root POM is the single source of truth.
- Branch-qualified versions are set once at feature-start and committed.
- Workspace manifest (`workspace.yaml`) is the inter-repository dependency graph.
- The aggregator POM and the manifest are complementary: POM drives `mvn`,
  YAML drives `ws:` workspace goals.

---
date_published: 2026-08-21
date_modified: 2026-08-21
canonical_url: https://ike.network/ike-tooling/ike-build-standards/developer-environment.html
---

# Developer Environment

The canonical "getting started on your machine" page for IKE Network development. It gathers the IDE, operating-system, and command-line setup a contributor needs into one place, so the knowledge is not scattered across per-workspace `CLAUDE.md` files and folklore.

The page is organised per environment. Each section is independent — read the ones that apply to your machine.

## [#intellij-idea](#intellij-idea)IntelliJ IDEA

IntelliJ is the primary IDE for IKE Java and workspace development.

### [#maven-home--use-the-maven-wrapper](#maven-home--use-the-maven-wrapper)Maven home — use the Maven wrapper

**This is the single most important setting.** IKE workspaces build with Maven 4 and POM `modelVersion` `4.1.0`. A Maven 3 binary cannot read that model and the build fails immediately with:

```
'modelVersion' of '4.1.0' is newer than the version supported
by this Maven installation [4.0.0]
```

Point IntelliJ at the wrapper that each workspace ships:

- **Settings → Build, Execution, Deployment → Build Tools → Maven**
- Set **Maven home path** to **Use Maven wrapper**

The wrapper (`.mvn/wrapper/` + `mvnw`) pins the exact Maven 4 version the workspace expects, so every machine and CI runner builds with the same toolchain.

### [#install-the-graphviz-dot-plugin](#install-the-graphviz-dot-plugin)Install the GraphViz / DOT plugin

`ws:overview` and `ws:graph` render the workspace dependency graph as GraphViz DOT (see ike-issues#406 — the renderer is GraphViz, **not** Mermaid). Install JetBrains' **GraphViz / DOT** plugin so the generated `.dot` output previews inside the IDE. Do not install a Mermaid plugin for this — it will not render the workspace graph.

### [#install-the-asciidoc-plugin](#install-the-asciidoc-plugin)Install the AsciiDoc plugin

IKE documentation is authored in AsciiDoc. Install the **AsciiDoc** plugin to get live rendered preview. The workspace ships a `.asciidoctorconfig` fragment (the `asciidoctorconfig` classifier of `ike-build-standards`) so the IDE preview matches the renderer the build uses — no per-machine attribute tweaking.

### [#finding-the-ike-goals](#finding-the-ike-goals)Finding the IKE goals

The `ws:` and `ike:` goals appear in the **Maven** tool window under **Plugins → ws** and **Plugins → ike**. Fully-qualified coordinates are not needed: `~/.m2/settings.xml` carries the IKE `pluginGroups` (the scaffold maintains that entry), so `mvn ws:overview` resolves directly.

### [#per-machine-settings-are-not-synced](#per-machine-settings-are-not-synced)Per-machine settings are not synced

`.idea/workspace.xml` is intentionally excluded from sync, so a few IDE settings are one-time-per-machine — the Maven-home setting above is the important one. Project-level settings under `.idea/` that **are** committed apply automatically.

## [#macos](#macos)macOS

### [#open-md-and-adoc-files-in-intellij](#open-md-and-adoc-files-in-intellij)Open `.md` and `.adoc` files in IntelliJ

By default macOS opens Markdown and AsciiDoc files in TextEdit, which shows raw markup. To get the rendered preview on double-click:

- Select a `.md` (or `.adoc`) file in Finder
- **File → Get Info** (`⌘I`)
- Under **Open with**, choose **IntelliJ IDEA**
- Click **Change All…** so the association applies to every file of that type

## [#jdk-and-preview-features](#jdk-and-preview-features)JDK and preview features

- IKE projects build and run on **JDK 25**.
- Projects that use Java preview features compile and run with `--enable-preview`. The build wires this in; if you run a class directly from the IDE, enable preview features in the run configuration.

## [#goal-report-files](#goal-report-files)Goal report files

Several `ws:` and `ike:` goals write a Markdown report next to the project root — for example `ws꞉overview.md` or `ike꞉release-draft.md` (the `꞉` is a Unicode modifier-colon, not a path separator). These files are gitignored scratch output (ike-issues#407): open them to read the rendered dependency graph, release plan, or foundation-drift report a goal just produced. They are regenerated on each run and are never committed.

## [#command-line](#command-line)Command line

Working-set coordination ships with the development folder itself — nothing to install beyond the IDE plugin:

- `~/ike-dev/scripts/lease.sh` — the working-set lease CLI (`list`, `status <ws>`, `acquire`, `release`). It is a thin wrapper over the Java protocol core (`network.ike.lease.core`, the released `ike-lease` artifact); installing the IntelliJ plugin zip is what provisions the jar it execs. One writer per working set, enforced; opening a project in IntelliJ acquires the lease for you.
- `MaterializeCli` — headless git-state repair for a synced tree: `java -cp <plugins>/ike-lease-plugin/lib/ike-lease-core-*.jar network.ike.lease.core.MaterializeCli materialize|verify|repair <ws>`. Normally unnecessary — the IDE open gesture materializes and aligns automatically.
- The `mvnw` wrapper in each repository is the only Maven you need; Maven-4 workspaces require it (set IntelliJ’s Maven home to "Use Maven wrapper").

## [#vs-code](#vs-code)VS Code

*(Planned — extension recommendations and AsciiDoc preview setup will be documented here.)*

## [#see-also](#see-also)See also

- `ike-workspace-conventions.adoc` — the rationale behind multi-machine, multi-branch IKE development.
- [IKE-Network/ike-issues](https://github.com/IKE-Network/ike-issues)[1] — the cross-project issue tracker.

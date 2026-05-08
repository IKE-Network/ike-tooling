# Maven 4 Build Standards

## Core Principles

- **Declarative over imperative.** Use proper Maven plugins for their intended purpose. Never use `exec-maven-plugin` for tasks that have a dedicated Maven plugin (file copying, resource filtering, dependency management). When imperative logic is unavoidable, write a proper Java mojo in `ike-maven-plugin` rather than embedding shell logic in the POM. The Java-mojo path gives you typed parameters, unit tests, and consistent error reporting; bash scripts inline in POMs do not.
- **Every artifact must have proper coordinates.** GroupId, artifactId, version, classifier, and type must be explicit. No uncoordinated files floating outside the Maven reactor.
- **Consumer POM awareness.** Build-time configuration (plugin settings, profiles, properties used only during build) must not leak into the consumer POM. Use `<pluginManagement>` for inherited defaults, `<plugins>` for concrete bindings.

## Lifecycle Phase Binding

Bind plugin executions to semantically correct phases:

| Phase | Purpose | Examples |
|---|---|---|
| `validate` | Environment setup, prerequisite checks | Enforcer rules, standards unpack |
| `generate-sources` | Code/resource generation from external sources | Unpack cross-module AsciiDoc ZIPs |
| `generate-resources` | Resource preparation | Font download, config staging |
| `prepare-package` | Pre-packaging transforms | AsciiDoc to HTML/DocBook, SVG fixing |
| `package` | Artifact creation | PDF rendering, XSL-FO transforms |
| `verify` | Post-package validation and secondary artifacts | HTML generation, Prawn PDF, ZIP assembly |
| `install` | Local repo installation | Classified artifacts to ~/.m2 |

Never abuse profiles to simulate lifecycle phases. Profiles are configuration toggles, not workflow stages.

## Property-Driven Build Pattern

Use skip-flag properties (defaulting to `true`) to gate plugin executions. Profiles are thin toggles that flip flags to `false`. This makes profiles:
- Discoverable in IDE Maven panels
- Composable (activate multiple simultaneously)
- Overridable from CLI (`-Dike.skip.renderer=false`)

## Plugin Ordering

Within the same lifecycle phase, Maven runs plugins in POM declaration order. When one plugin's output is another's input within the same phase, declaration order is the contract. Document ordering dependencies with comments.

## Assembly Descriptors

- Place assembly descriptors in `src/assembly/`.
- Use `maven-assembly-plugin` for classified ZIP/TAR archives.
- Each assembly produces a properly classified artifact attached to the reactor.
- Schema: `http://maven.apache.org/ASSEMBLY/2.2.0`
- Use `<includeBaseDirectory>false</includeBaseDirectory>` unless the archive needs a wrapper directory.

## Prohibited Patterns

- `maven-antrun-plugin` — write a proper Java mojo in `ike-maven-plugin` instead
- `build-helper-maven-plugin` for multi-execution property chaining — write a proper Maven goal in `ike-maven-plugin` instead. Chaining many timestamp-property and regex-property executions creates fragile, hard-to-test XML that grows with each platform or format variant. A single Mojo with unit tests replaces hundreds of lines of XML.
- Inline shell commands in POM `<configuration>` blocks — write a Java mojo in `ike-maven-plugin` instead
- `exec-maven-plugin` for file operations that have dedicated plugins (copying, moving, filtering) — or for any logic complex enough to merit a Java mojo
- Manual file copying instead of resource filtering
- `<properties>` blocks in profiles that share names across co-activated profiles (last-wins collision)
- `git add -A` or `git add .` (stage specific files to avoid committing secrets or binaries)

## Build-Time Imperative Logic

When the build requires imperative logic that no Maven plugin handles
out of the box (patching files, multi-step transforms, cross-module
coordination), write a Java mojo in `ike-maven-plugin` rather than
inlining shell scripts in POMs.

- **Why Java, not bash**: typed parameters, JUnit-testable, consistent
  error/log handling via `MojoException` and `Log`, no platform
  differences (sed -i, perl variations, GNU vs BSD coreutils). The
  earlier convention of bash scripts shipped via an `ike-build-tools`
  artifact has been retired in favor of Java mojos.
- **Where it goes**: in `ike-maven-plugin` if the logic is generic
  (release orchestration, site deploy, scaffolding), or in
  `ike-workspace-maven-plugin` if it's workspace-spanning. Reuse the
  base classes in `ike-maven-plugin-support` (`AbstractGoalMojo`,
  `GoalReport`, `MojoParamSupport`) for parameter parsing, markdown
  reports, and goal-name/help integration.
- **Subprocess calls**: when shelling out is genuinely the right
  answer (git operations, ssh, native tools), use
  `ReleaseSupport.exec(workDir, log, args...)` rather than
  `ProcessBuilder` directly — it handles logging, exit codes, and
  output capture consistently.

## Required Patterns

- `maven-assembly-plugin` for classified archives
- `maven-resources-plugin` with filtering for environment-specific config
- `maven-enforcer-plugin` for prerequisite validation (Java version, Maven version)
- `maven-dependency-plugin` for artifact unpacking with proper GAV coordinates
- Java mojos in `ike-maven-plugin` for imperative build logic
- Explicit `<version>` on every plugin in `<pluginManagement>`

## Dependency Management

- **Use BOMs (Bill of Materials) when available.** Import BOMs via `<dependencyManagement>` with `<scope>import</scope>` and `<type>pom</type>` to align transitive dependency versions. This is preferred over manually specifying versions for each dependency in a family (e.g., JUnit, Jackson, Spring).
  ```xml
  <dependencyManagement>
      <dependencies>
          <dependency>
              <groupId>org.junit</groupId>
              <artifactId>junit-bom</artifactId>
              <version>${junit.version}</version>
              <type>pom</type>
              <scope>import</scope>
          </dependency>
      </dependencies>
  </dependencyManagement>
  ```
- Child modules declare dependencies without `<version>` to inherit from the BOM or parent `<dependencyManagement>`.
- Never mix BOM-managed and manually-versioned dependencies from the same family.

## Maven 4 Specifics

- Model version `4.1.0` for new POMs.
- **POM namespace must match modelVersion.** `modelVersion 4.1.0` requires `xmlns="http://maven.apache.org/POM/4.1.0"` and `xsi:schemaLocation` pointing to `maven-4.1.0.xsd`. Never mix 4.0.0 namespace with 4.1.0 modelVersion.
  ```xml
  <!-- Correct for modelVersion 4.1.0 -->
  <project xmlns="http://maven.apache.org/POM/4.1.0"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://maven.apache.org/POM/4.1.0
           http://maven.apache.org/xsd/maven-4.1.0.xsd">
      <modelVersion>4.1.0</modelVersion>
  ```
- `<subprojects>` replaces `<modules>` in reactor aggregator POMs.
- `<parent>` element: use either `<relativePath>` alone (when parent is at the default `../pom.xml`) or GAV alone with `<relativePath/>` (empty element — disables filesystem lookup, uses reactor/repo resolution). Never combine a non-empty `<relativePath>` with GAV.
- **Aggregator as parent.** When child modules inherit from an external parent (outside the reactor), the aggregator POM should declare that external parent and child modules should declare the aggregator as their parent. This forms the correct chain (child → aggregator → external parent) and ensures the default `../pom.xml` resolution matches the declared parent.
- Consumer POM is automatic — build-only config is stripped from the published POM.

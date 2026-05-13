# IKE Tooling

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Documentation](https://img.shields.io/badge/docs-ike.network%2Fike--tooling-blue)](https://ike.network/ike-tooling/)
[![IKE Network](https://img.shields.io/badge/IKE-Network-green)](https://ike.network/)

Build tooling for the IKE Network: workspace management, release
orchestration, gitflow workflows, and build-time utilities.

## Modules

| Module | Artifact | Purpose |
|---|---|---|
| `ike-build-standards` | `network.ike.tooling:ike-build-standards` | Versioned reference material (Claude standards, configs, scaffold manifest) — multi-classifier ZIP artifact |
| `ike-workspace-model` | `network.ike.tooling:ike-workspace-model` | Data model and conventions for an IKE workspace; shared by both Maven plugins |
| `ike-maven-plugin-support` | `network.ike.tooling:ike-maven-plugin-support` | Helper library for IKE Maven plugins (goal registry, base mojo, parameter parsing) |
| `ike-maven-plugin` | `network.ike.tooling:ike-maven-plugin` | `ike:*` goals — release orchestration, scaffolding, site deploy, version upgrades |

## Build

```bash
mvn clean install
```

## Release Cascade

```
[ike-tooling] → ike-docs → ike-platform → { doc-example, example-project, ike-example-ws, ike-example-its }
```

`ike-tooling` releases first because both `ike-docs` and `ike-platform`
declare `ike-maven-plugin` in their `<pluginManagement>` (via
`${ike-tooling.version}`) and consume `ike-build-standards` at
`validate` time. Those artifacts must be resolvable from Nexus
when the downstream reactors load.

The cascade ordering is structurally upstream-first; it is not
driven by extension-realm timing. Earlier revisions cited
`<extensions>true</extensions>` for `<packaging>ike-doc</packaging>`
as the reason — that machinery was retired in
[`IKE-Network/ike-issues#321`](https://github.com/IKE-Network/ike-issues/issues/321)
when `ike-doc-maven-plugin` adopted a classifier-canonical doc
shape (`<classifier>adoc</classifier><type>zip</type>`). The
ordering is unchanged; the literal-version pinning is gone.

The cascade is orchestrated by
`ike-workspace-maven-plugin:cascade-foundation-publish` (in
`ike-platform`); see [`cutting-a-release.adoc`](https://ike.network/ike-platform/cutting-a-release.html).

## Doc as Code + LLM-Friendly

`ike-tooling` is the source of the IKE Network's doc-as-code
infrastructure. Its [`ike-build-standards`](ike-build-standards)
submodule ships every build convention, documentation standard,
and AI-assistant instruction as versioned Markdown files. The
`claude` classifier ZIP is unpacked into every consuming project's
`.claude/standards/` directory at `validate` phase — so when a
developer or Claude itself opens an IKE project, the agent reads
the standards locally and applies them automatically; contributors
don't have to memorize the conventions.

See the [`ike-build-standards` README](ike-build-standards#readme)
for the inventory of standards (each is a linkable Markdown file
covering one topic: `MAVEN.md`, `IKE-DOC.md`, `IKE-DIAGRAMS.md`,
`IKE-RELEASE.md`, etc.) and the
[published index](https://ike.network/ike-tooling/ike-build-standards/).

## Links

- **Documentation:** [`https://ike.network/ike-tooling/`](https://ike.network/ike-tooling/)
- **Build standards:** [`ike-build-standards`](https://ike.network/ike-tooling/ike-build-standards/)
- **Issues:** [`IKE-Network/ike-issues`](https://github.com/IKE-Network/ike-issues) (cross-project tracker)
- **Source:** [`IKE-Network/ike-tooling`](https://github.com/IKE-Network/ike-tooling)

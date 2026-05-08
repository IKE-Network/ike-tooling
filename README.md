# IKE Tooling

**Documentation:** https://ike.network/ike-tooling/

Build tooling for the IKE Community: workspace management, release
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
[ike-tooling] → ike-docs → ike-platform → { downstream consumers }
```

`ike-tooling` releases first because both `ike-docs` and `ike-platform`
declare `ike-maven-plugin` with `extensions=true` at literal versions
in their `<pluginManagement>`. Maven resolves extension plugins at
project-load time, before property interpolation, so the JAR must
already be on Nexus.

## Issues

Cross-project tracker: [`IKE-Network/ike-issues`](https://github.com/IKE-Network/ike-issues).

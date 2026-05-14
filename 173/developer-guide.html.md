---
date_published: 2026-05-13
date_modified: 2026-05-13
canonical_url: https://ike.network/ike-tooling/developer-guide.html
---

# Developer Guide

Notes for contributors to `ike-tooling` itself. For end-user documentation on how to invoke `ike:*` and `ws:*` goals, see the per-plugin pages ([ike-maven-plugin home](ike-maven-plugin/index.html)[1]).

## [#self-host-bootstrap](#self-host-bootstrap)Self-host bootstrap

`ike-tooling` is the one reactor in the IKE Network that builds the plugin it also wants to **use** against its own site. That creates a Maven reactor cycle, and the release flow sidesteps it with an **X-SNAPSHOT bootstrap** — a property-activated profile that references the plugin at a different GAV than the reactor submodules.

The pattern is documented in full on the plugin site: [Self-host bootstrap pattern](ike-maven-plugin/self-host-bootstrap.html)[2]. Read that if you are:

- Touching the release flow’s site invocations (`ReleaseDraftMojo` — search for **"X-SNAPSHOT bootstrap (2 of 2)"**).
- Modifying the `releaseSelfSite` profile in this reactor’s `pom.xml` (search for **"X-SNAPSHOT bootstrap (1 of 2)"**).
- Adding more pre-site bindings to the reactor root.
- Hitting a reactor-cycle error during `mvn site` and wondering why the rest of the build is fine.

The short version: dev-time `mvn install` does not activate the profile (no property set → no `<plugins>` entry → no cycle), and the release flow passes `-Drelease.bootstrap.version=X-SNAPSHOT` only on its three site invocations.

## [#more-sections-as-they-accrue](#more-sections-as-they-accrue)More sections, as they accrue

This is a stub. Add subsections here when there are non-obvious contributor-facing patterns that would otherwise live as in-code comments scattered across the reactor.

---
date_published: 2026-05-12
date_modified: 2026-05-12
canonical_url: https://ike.network/ike-tooling/ike-maven-plugin/self-host-bootstrap.html
---

# Self-host bootstrap pattern

How a Maven reactor that **contains** `ike-maven-plugin` (or any other plugin) can also **use** that plugin against itself, without falling into a reactor cycle. The canonical example is `ike-tooling’s own reactor root: it ships the plugin, and it wants to bind the plugin’s `built-with` and `render-sbom-viewer` goals so its own published site has `/ike-tooling/built-with.html` and `/ike-tooling/dependencies.html` on every release.

This is a niche concern — only relevant if your reactor builds the plugin you want to bind. Most consumers inherit `ike-maven-plugin` via `pluginManagement` and never hit this.

## [#the-cycle](#the-cycle)The cycle

An unconditional reactor-root `<plugins>` reference to a plugin that this reactor **builds** creates a Maven reactor cycle. For `ike-tooling`, the edge is:

```
root → ike-maven-plugin → ike-build-standards → root
```

The middle module exists because `ike-maven-plugin` depends on `ike-build-standards` for shared compile-time machinery, and `ike-build-standards` inherits from root. Maven flags this at **reactor evaluation time**, before any phase runs — so even `mvn install` fails with a cycle error.

Important subtlety: `pluginManagement` does **not** create such an edge. Only the live `<plugins>` binding does. The rest of the pom can safely list the plugin in `<pluginManagement>` — the cycle problem is specific to wanting to actually **execute** goals from the plugin at reactor-root build time.

## [#the-fix-x-snapshot-bootstrap](#the-fix-x-snapshot-bootstrap)The fix: X-SNAPSHOT bootstrap

Indirect through a property whose value is a **different GAV** than the reactor submodules, and put the plugin binding inside a profile that activates only when the property is set:

```
<profile>
    <id>releaseSelfSite</id>
    <activation>
        <property>
            <name>release.bootstrap.version</name>
        </property>
    </activation>
    <build>
        <plugins>
            <plugin>
                <groupId>network.ike.tooling</groupId>
                <artifactId>ike-maven-plugin</artifactId>
                <version>${release.bootstrap.version}</version>
                <executions>
                    <execution>
                        <id>built-with</id>
                        <phase>pre-site</phase>
                        <goals><goal>built-with</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

And invoke `mvn site` with:

```
mvn site -Drelease.bootstrap.version=X-SNAPSHOT
```

where `X` is the version we’re about to release.

### [#why-this-breaks-the-cycle](#why-this-breaks-the-cycle)Why this breaks the cycle

During release flow, submodules are set to `X` (the release version). A `<plugins>` reference to the plugin at `X-SNAPSHOT` is a reference to a **different GAV** than any submodule — no graph edge to a submodule — no reactor cycle.

If we used `171` here, we’d be back to `X`, and the cycle would return. The whole point is that `X-SNAPSHOT` and `X` are different artifacts as far as Maven’s reactor graph is concerned.

### [#why-x-snapshot-is-guaranteed-in--m2](#why-x-snapshot-is-guaranteed-in--m2)Why X-SNAPSHOT is guaranteed in `~/.m2`

The release flow runs `mvn clean install` on the SNAPSHOT pom **before** bumping to `X`. That install puts `X-SNAPSHOT` in the local Maven repository. By the time the site invocation needs to resolve the plugin descriptor, `X-SNAPSHOT` is already there. And `X-SNAPSHOT` carries the latest code being released — not a stale previously- released version.

If there is nothing to install, there is nothing to release — so the guarantee holds by induction on the release contract.

### [#when-it-activates](#when-it-activates)When it activates

Only when the release flow’s `mvn site` / `mvn site:stage` invocations pass `-Drelease.bootstrap.version=…​`. Plain `mvn install` during dev does not set the property, so the profile stays inactive, no `<plugins>` entry exists, and the cycle is invisible. Normal dev workflow is unaffected.

## [#implementation-by-file](#implementation-by-file)Implementation, by file

The pattern is implemented in two cooperating places. Both carry cross-references in their comments so a reader landing at either side can follow the link to the other.

### [#the-pom-that-consumes-the-property](#the-pom-that-consumes-the-property)The pom that consumes the property

[ike-tooling/pom.xml](https://github.com/IKE-Network/ike-tooling/blob/main/pom.xml)[1] declares the `releaseSelfSite` profile (search for **"X-SNAPSHOT bootstrap (1 of 2)"**). The profile:

- Is property-activated, not always-on.
- Binds `ike:built-with` and `ike:render-sbom-viewer` at the `pre-site` phase.
- References `ike-maven-plugin` at `${release.bootstrap.version}` — the literal value supplied by the caller.

### [#the-mojo-that-supplies-the-property](#the-mojo-that-supplies-the-property)The mojo that supplies the property

[ReleaseDraftMojo.java](https://github.com/IKE-Network/ike-tooling/blob/main/ike-maven-plugin/src/main/java/network/ike/plugin/ReleaseDraftMojo.java)[2] captures `oldVersion` (the pre-release pom version, = X-SNAPSHOT) at the top of the release flow, then passes `-Drelease.bootstrap.version= ${oldVersion}` on three site invocations: pre-flight `mvn site`, external-phase `mvn site`, external-phase `mvn site:stage`. Search for **"X-SNAPSHOT bootstrap (2 of 2)"**.

Note that **other** release-flow mvn calls (`verify`, `clean deploy`, `ike:register-site-publish`, etc.) do **not** pass the property. They stay outside the profile and resolve plugin coordinates through ordinary `pluginManagement` — which is fine, because `pluginManagement` does not create reactor edges the way live `<plugins>` does.

## [#when-does-another-reactor-need-this](#when-does-another-reactor-need-this)When does another reactor need this?

You hit the cycle if **all three** are true:

1. Your reactor **builds** the plugin you want to bind.
2. You want to **execute** (not just manage) that plugin’s goals at reactor-root build time — typically for site-level reports.
3. The plugin has a dependency that transitively pulls in your reactor root (e.g., a sibling module that inherits from it).

If you only have #1, `pluginManagement` is enough. If you have #1 and #3 but only want `pluginManagement`, you’re also fine. The cycle is specifically triggered by adding a live `<plugins>` binding.

When you do hit it, the pattern transplants directly: pick a property name, wrap the plugin binding in a property-activated profile, and pass `-D<your-property>=<your-snapshot>` from whatever drives the build (release tool, CI script, operator-typed command).

## [#see-also](#see-also)See also

- [ike-maven-plugin home](index.adoc)[3]
- [ike-issues#370](https://github.com/IKE-Network/ike-issues/issues/370)[4] — the umbrella issue for the `ike-tooling` instance of this pattern.

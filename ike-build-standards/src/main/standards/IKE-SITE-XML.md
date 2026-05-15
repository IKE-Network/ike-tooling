# IKE Per-Module Maven Site Standards

## Purpose

Every module in a multi-module IKE Maven reactor MUST declare its
own `src/site/site.xml` — even when the layout is "the same as the
parent's." A missing per-module site.xml is one of the failure modes
the sentry-maven-skin renders silently: `$site.name` ends up as the
literal string `$site.name` in the page `<title>` and headers
because inheritance from the parent reactor's site.xml does not
propagate the `name` attribute through to the velocity skin context.

See `IKE-Network/ike-issues#369` for the original bug. This document
ships the canonical template so the next new module avoids the trap.

## When this applies

- **Any module under a multi-module IKE reactor** whose published
  site shows a project name or breadcrumb. Foundation reactors
  (`ike-tooling`, `ike-docs`, `ike-platform`) and their consumer
  workspaces all qualify.
- **A new module added to an existing reactor.** Copy the template
  below before the first `mvn site` run.

If a module legitimately ships no rendered site (e.g., a pure
data jar that disables the site lifecycle), this standard does not
apply — but those modules are rare in IKE projects.

## The template

Copy to `src/site/site.xml` under the new module. Replace the four
TODO placeholders. Everything else is identical across modules.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<site xmlns="http://maven.apache.org/SITE/2.0.0"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://maven.apache.org/SITE/2.0.0
      https://maven.apache.org/xsd/site-2.0.0.xsd"
      name="TODO: human-readable module name">

    <skin>
        <groupId>org.sentrysoftware.maven</groupId>
        <artifactId>sentry-maven-skin</artifactId>
        <version>7.0.00</version>
    </skin>

    <custom>
        <bodyClass>sentry-green</bodyClass>
        <checkImageLinks>false</checkImageLinks>
        <convertImagesToWebp>false</convertImagesToWebp>
        <convertImagesToThumbnails>false</convertImagesToThumbnails>
        <setExplicitImageSize>false</setExplicitImageSize>
    </custom>

    <bannerRight name="GitHub &lt;i class='fa-brands fa-github'&gt;&lt;/i&gt;"
        href="https://github.com/IKE-Network/TODO-repo-name/tree/main/TODO-module-name"/>

    <body>
        <breadcrumbs>
            <item name="IKE" href="https://ike.network/"/>
            <item name="TODO: parent reactor display name" href="../index.html"/>
            <item name="TODO: this module display name" href="index.html"/>
        </breadcrumbs>
        <menu name="Overview">
            <item name="About" href="index.html"/>
        </menu>
        <menu ref="parent"/>
        <menu name="Reports" ref="reports"/>
    </body>
</site>
```

### Critical: parent reactor's `IKE` breadcrumb must use absolute URL

Maven Site MERGES the child's breadcrumbs with the parent reactor's
breadcrumbs at render time. If the parent's `IKE` item href differs
from the child's, BOTH end up in the rendered breadcrumb — producing
the four-item pattern that bit `ike-docs` submodules:

```
IKE / IKE Docs / IKE / Minimal Fonts
              ^^^^^^
              extra parent "IKE" injected because parent's href is
              ../index.html while child's is https://ike.network/
```

The dedupe checks RAW href strings, not resolved URLs. So both the
parent reactor's site.xml AND every submodule's site.xml must use
the IDENTICAL absolute href for the `IKE` item:

```xml
<item name="IKE" href="https://ike.network/"/>
```

If you see the four-item pattern, the parent's site.xml is using
a relative href like `../index.html` — change it to the absolute
form and the merge dedupes.

The `combine.self="override"` attribute on `<breadcrumbs>` does NOT
work for this — Maven Site's body-merging XML reader doesn't honor
the Maven model's combine attributes. The only fix is href identity
on the inherited items.

### Placeholders

| Placeholder | Example |
| --- | --- |
| `name=` (root) | `IKE Maven Plugin`, `IKE Workspace Model` |
| `bannerRight href` repo segment | `ike-tooling`, `ike-docs`, `ike-platform` |
| `bannerRight href` module segment | `ike-maven-plugin`, `ike-doc-resources` |
| Parent breadcrumb `name` | `IKE Tooling`, `IKE Documentation Pipeline`, `IKE Platform` |

## What about workspace aggregators?

Workspace repos (`-ws` suffix) follow the same template at their
root and at each subproject's root. The root pom's site.xml uses
no `<menu ref="parent"/>` because it has no Maven parent in the
reactor sense — it points at the IKE landing page via the
breadcrumb only.

See `ike-platform/src/site/site.xml` for a workspace-root example
and any subproject for a child example.

## Validation

A future preflight (`ws:release-publish` and `ws:scaffold-draft`) is
expected to fail when any subproject of a multi-module reactor
lacks `src/site/site.xml`. Until that lands, the manual check is:

```bash
for pom in $(find . -name pom.xml -not -path '*/target/*'); do
    dir=$(dirname "$pom")
    [ -f "$dir/src/site/site.xml" ] || echo "MISSING: $dir/src/site/site.xml"
done
```

Run from any reactor root.

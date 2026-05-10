# Third-Party Notices — IKE Tooling

Three layers of attribution ship with each release:

1. **Software Bill of Materials (CycloneDX, machine-readable):**
   - https://ike.network/ike-tooling/bom.json
   - https://ike.network/ike-tooling/bom.xml
   - Full transitive dependency graph, SPDX-normalized licenses, artifact hashes.
   - Also reachable as a Maven artifact with `<classifier>cyclonedx</classifier>`.

2. **Maven Site dependency report (HTML, human-browseable):**
   - https://ike.network/ike-tooling/dependencies.html
   - https://ike.network/ike-tooling/licenses.html
   - Declared dependencies with verbatim per-license POM text.

3. **Curated Third-Party Notices (this document):**
   - **Current release:** https://ike.network/ike-tooling/THIRD_PARTY_NOTICES.html
   - **Versioned:** https://ike.network/ike-tooling/&lt;version&gt;/THIRD_PARTY_NOTICES.html
   - **Latest:** https://ike.network/ike-tooling/latest/THIRD_PARTY_NOTICES.html
   - The source AsciiDoc lives at [`src/site/asciidoc/THIRD_PARTY_NOTICES.adoc`](src/site/asciidoc/THIRD_PARTY_NOTICES.adoc).

## What's covered

The curated document acknowledges third-party open-source software
that mechanical reports either don't reach (Maven Site skin,
build-time signing engine, plugin transitive deps) or report
verbatim per-POM-string in `licenses.html` (which is noisy across
the same license declared a dozen ways in the wild).

For corresponding notices in the rest of the IKE platform see:

- [ike-docs](https://ike.network/ike-docs/THIRD_PARTY_NOTICES.html) — AsciiDoc rendering chain, fonts, DocBook, frontend assets.
- [ike-platform](https://ike.network/ike-platform/THIRD_PARTY_NOTICES.html) — Java toolchain, BOM-managed dependencies, test framework.

Issues or omissions: file at https://github.com/IKE-Network/ike-issues.

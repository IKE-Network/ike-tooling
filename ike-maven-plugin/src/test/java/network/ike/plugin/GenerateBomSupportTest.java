package network.ike.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the pure functions extracted from {@link GenerateBomMojo}:
 * {@code escapeXml()} and {@code buildBomXml()}.
 */
class GenerateBomSupportTest {

    private static final List<BomLicense> NO_LICENSES = List.of();
    private static final List<BomDeveloper> NO_DEVELOPERS = List.of();

    // ── escapeXml ────────────────────────────────────────────────────

    @Test
    void escapeXml_ampersand() {
        assertThat(GenerateBomMojo.escapeXml("AT&T"))
                .isEqualTo("AT&amp;T");
    }

    @Test
    void escapeXml_lessThan() {
        assertThat(GenerateBomMojo.escapeXml("a < b"))
                .isEqualTo("a &lt; b");
    }

    @Test
    void escapeXml_greaterThan() {
        assertThat(GenerateBomMojo.escapeXml("a > b"))
                .isEqualTo("a &gt; b");
    }

    @Test
    void escapeXml_allSpecialChars() {
        assertThat(GenerateBomMojo.escapeXml("a & b < c > d"))
                .isEqualTo("a &amp; b &lt; c &gt; d");
    }

    @Test
    void escapeXml_null_returnsEmpty() {
        assertThat(GenerateBomMojo.escapeXml(null))
                .isEmpty();
    }

    @Test
    void escapeXml_plainText_unchanged() {
        assertThat(GenerateBomMojo.escapeXml("Hello World"))
                .isEqualTo("Hello World");
    }

    // ── buildBomXml ──────────────────────────────────────────────────

    @Test
    void buildBomXml_emptyEntries_producesValidXml() {
        String xml = GenerateBomMojo.buildBomXml(
                "network.ike", "ike-bom", "20-SNAPSHOT",
                "IKE BOM", "Bill of Materials", "https://ike.network",
                NO_LICENSES, NO_DEVELOPERS, null,
                List.of());

        assertThat(xml)
                .startsWith("<?xml version=\"1.0\"")
                .contains("<groupId>network.ike</groupId>")
                .contains("<artifactId>ike-bom</artifactId>")
                .contains("<version>20-SNAPSHOT</version>")
                .contains("<packaging>pom</packaging>")
                .contains("<name>IKE BOM</name>")
                .contains("<description>Bill of Materials</description>")
                .contains("<url>https://ike.network</url>")
                .contains("<dependencyManagement>")
                .contains("</dependencyManagement>")
                .endsWith("</project>\n");
    }

    @Test
    void buildBomXml_plainJarEntry() {
        BomEntry jar = new BomEntry(
                "network.ike", "minimal-fonts", "1.0.0",
                null, "jar", null);

        String xml = GenerateBomMojo.buildBomXml(
                "network.ike", "ike-bom", "20",
                "BOM", null, null,
                NO_LICENSES, NO_DEVELOPERS, null,
                List.of(jar));

        assertThat(xml)
                .contains("<groupId>network.ike</groupId>")
                .contains("<artifactId>minimal-fonts</artifactId>")
                .contains("<version>1.0.0</version>")
                .doesNotContain("<classifier>")
                .doesNotContain("<type>")
                .doesNotContain("<scope>");
    }

    @Test
    void buildBomXml_classifiedZipEntry() {
        BomEntry zip = new BomEntry(
                "network.ike", "ike-build-standards", "20",
                "claude", "zip", null);

        String xml = GenerateBomMojo.buildBomXml(
                "network.ike", "ike-bom", "20",
                "BOM", null, null,
                NO_LICENSES, NO_DEVELOPERS, null,
                List.of(zip));

        assertThat(xml)
                .contains("<classifier>claude</classifier>")
                .contains("<type>zip</type>");
    }

    @Test
    void buildBomXml_testScopedEntry() {
        BomEntry test = new BomEntry(
                "org.junit.jupiter", "junit-jupiter", "5.11.4",
                null, "jar", "test");

        String xml = GenerateBomMojo.buildBomXml(
                "network.ike", "ike-bom", "20",
                "BOM", null, null,
                NO_LICENSES, NO_DEVELOPERS, null,
                List.of(test));

        assertThat(xml)
                .contains("<scope>test</scope>")
                .doesNotContain("<classifier>")
                .doesNotContain("<type>");
    }

    @Test
    void buildBomXml_nullDescription_omitted() {
        String xml = GenerateBomMojo.buildBomXml(
                "network.ike", "ike-bom", "1",
                "BOM", null, null,
                NO_LICENSES, NO_DEVELOPERS, null,
                List.of());

        assertThat(xml)
                .doesNotContain("<description>");
    }

    @Test
    void buildBomXml_nullUrl_emptyElement() {
        String xml = GenerateBomMojo.buildBomXml(
                "network.ike", "ike-bom", "1",
                "BOM", null, null,
                NO_LICENSES, NO_DEVELOPERS, null,
                List.of());

        assertThat(xml)
                .contains("<url></url>");
    }

    @Test
    void buildBomXml_multipleEntries_allPresent() {
        List<BomEntry> entries = List.of(
                new BomEntry("a", "first", "1.0", null, "jar", null),
                new BomEntry("b", "second", "2.0", null, "jar", null),
                new BomEntry("c", "third", "3.0", null, "jar", null));

        String xml = GenerateBomMojo.buildBomXml(
                "g", "a", "1", "N", null, null,
                NO_LICENSES, NO_DEVELOPERS, null, entries);

        assertThat(xml)
                .contains("<artifactId>first</artifactId>")
                .contains("<artifactId>second</artifactId>")
                .contains("<artifactId>third</artifactId>");
    }

    @Test
    void buildBomXml_nameWithSpecialChars_escaped() {
        String xml = GenerateBomMojo.buildBomXml(
                "g", "a", "1",
                "IKE <BOM> & More", null, null,
                NO_LICENSES, NO_DEVELOPERS, null,
                List.of());

        assertThat(xml)
                .contains("<name>IKE &lt;BOM&gt; &amp; More</name>");
    }

    // ── buildBomXml: additional edge cases ───────────────────────────

    @Test
    void buildBomXml_entryWithNullClassifier_noClassifierElement() {
        BomEntry entry = new BomEntry(
                "g", "a", "1.0", null, null, null);

        String xml = GenerateBomMojo.buildBomXml(
                "g", "bom", "1", "BOM", null, null,
                NO_LICENSES, NO_DEVELOPERS, null,
                List.of(entry));

        assertThat(xml)
                .doesNotContain("<classifier>")
                .doesNotContain("<type>")
                .doesNotContain("<scope>");
    }

    @Test
    void buildBomXml_entryWithEmptyClassifier_noClassifierElement() {
        BomEntry entry = new BomEntry(
                "g", "a", "1.0", "", "jar", "compile");

        String xml = GenerateBomMojo.buildBomXml(
                "g", "bom", "1", "BOM", null, null,
                NO_LICENSES, NO_DEVELOPERS, null,
                List.of(entry));

        // Empty classifier should not be output
        assertThat(xml).doesNotContain("<classifier>");
        // "jar" type is the default — should not be output
        assertThat(xml).doesNotContain("<type>");
        // "compile" scope is the default — should not be output
        assertThat(xml).doesNotContain("<scope>");
    }

    @Test
    void buildBomXml_entryWithNullType_noTypeElement() {
        BomEntry entry = new BomEntry(
                "g", "a", "1.0", "sources", null, "test");

        String xml = GenerateBomMojo.buildBomXml(
                "g", "bom", "1", "BOM", null, null,
                NO_LICENSES, NO_DEVELOPERS, null,
                List.of(entry));

        // null type should not produce <type> element
        assertThat(xml).doesNotContain("<type>");
        // classifier and non-compile scope should be present
        assertThat(xml).contains("<classifier>sources</classifier>");
        assertThat(xml).contains("<scope>test</scope>");
    }

    @Test
    void buildBomXml_descriptionWithSpecialChars_escaped() {
        String xml = GenerateBomMojo.buildBomXml(
                "g", "a", "1",
                "BOM", "Bill of <Materials> & dependencies", null,
                NO_LICENSES, NO_DEVELOPERS, null,
                List.of());

        assertThat(xml)
                .contains("<description>Bill of &lt;Materials&gt; &amp; dependencies</description>");
    }

    @Test
    void buildBomXml_autoGeneratedComment() {
        String xml = GenerateBomMojo.buildBomXml(
                "g", "a", "1", "BOM", null, null,
                NO_LICENSES, NO_DEVELOPERS, null, List.of());

        assertThat(xml).contains("Auto-generated BOM");
        assertThat(xml).contains(IkeGoal.GENERATE_BOM.qualified());
    }

    // ── buildBomXml: Central-required metadata (#967) ────────────────

    @Test
    void buildBomXml_licensesDevelopersScm_emitted() {
        // #967: the first live central-stage fire (platform v148) was
        // rejected by Central's PomChecker — the generated BOM carried
        // none of the three blocks the stub inherits from its parents.
        String xml = GenerateBomMojo.buildBomXml(
                "network.ike.platform", "ike-bom", "149",
                "IKE BOM", null, "https://ike.network",
                List.of(new BomLicense("Apache License, Version 2.0",
                        "https://www.apache.org/licenses/LICENSE-2.0")),
                List.of(new BomDeveloper("kec",
                        "Keith E. Campbell", "kec@knowledge.design")),
                new BomScm(
                        "scm:git:https://github.com/IKE-Network/ike-platform.git",
                        "scm:git:ssh://git@github.com/IKE-Network/ike-platform.git",
                        "https://github.com/IKE-Network/ike-platform",
                        "HEAD"),
                List.of());

        assertThat(xml)
                .contains("<licenses>")
                .contains("<name>Apache License, Version 2.0</name>")
                .contains("<url>https://www.apache.org/licenses/LICENSE-2.0</url>")
                .contains("<developers>")
                .contains("<id>kec</id>")
                .contains("<email>kec@knowledge.design</email>")
                .contains("<scm>")
                .contains("<connection>scm:git:https://github.com/IKE-Network/ike-platform.git</connection>")
                .contains("<developerConnection>")
                .contains("<tag>HEAD</tag>");
    }

    @Test
    void buildBomXml_metadataBlocks_orderedBeforeDependencyManagement() {
        String xml = GenerateBomMojo.buildBomXml(
                "g", "bom", "1", "BOM", null, null,
                List.of(new BomLicense("L", null)),
                List.of(new BomDeveloper("d", null, null)),
                new BomScm("c", null, null, null),
                List.of());

        assertThat(xml.indexOf("<licenses>"))
                .isLessThan(xml.indexOf("<developers>"));
        assertThat(xml.indexOf("<developers>"))
                .isLessThan(xml.indexOf("<scm>"));
        assertThat(xml.indexOf("<scm>"))
                .isLessThan(xml.indexOf("<dependencyManagement>"));
    }

    @Test
    void buildBomXml_emptyMetadata_blocksOmitted() {
        // Generation is faithful to the model; compliance enforcement
        // lives in GeneratedBomSwap.verify, which rejects a BOM
        // missing any of the three blocks (#967).
        String xml = GenerateBomMojo.buildBomXml(
                "g", "bom", "1", "BOM", null, null,
                NO_LICENSES, NO_DEVELOPERS, null,
                List.of());

        assertThat(xml)
                .doesNotContain("<licenses>")
                .doesNotContain("<developers>")
                .doesNotContain("<scm>");
    }

    @Test
    void buildBomXml_scmPartialFields_onlyPresentOnesEmitted() {
        String xml = GenerateBomMojo.buildBomXml(
                "g", "bom", "1", "BOM", null, null,
                NO_LICENSES, NO_DEVELOPERS,
                new BomScm("scm:git:https://example.org/r.git", null,
                        "https://example.org/r", null),
                List.of());

        assertThat(xml)
                .contains("<connection>scm:git:https://example.org/r.git</connection>")
                .contains("<url>https://example.org/r</url>")
                .doesNotContain("<developerConnection>")
                .doesNotContain("<tag>");
    }

    @Test
    void buildBomXml_developerNameWithSpecialChars_escaped() {
        String xml = GenerateBomMojo.buildBomXml(
                "g", "bom", "1", "BOM", null, null,
                NO_LICENSES,
                List.of(new BomDeveloper(null, "Health & Knowledge <Team>",
                        null)),
                null,
                List.of());

        assertThat(xml)
                .contains("<name>Health &amp; Knowledge &lt;Team&gt;</name>")
                .doesNotContain("<id>")
                .doesNotContain("<email>");
    }
}

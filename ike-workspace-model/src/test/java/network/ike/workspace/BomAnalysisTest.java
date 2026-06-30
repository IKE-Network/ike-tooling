package network.ike.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Tests for {@link BomAnalysis}.
 *
 * <p>Focus is the cascade-coverage rule from IKE-Network/ike-issues#794:
 * an edge {@code A → B} is covered when {@code A} imports a workspace-internal
 * BOM that <em>manages</em> one of {@code B}'s published artifacts — not only
 * when the imported BOM <em>is</em> {@code B}'s own BOM. Earlier behavior
 * (version-property tracking, upstream-is-the-BOM, and genuine gaps) is
 * regression-guarded here too.
 */
class BomAnalysisTest {

    @TempDir Path tempDir;

    // ── extractManagedArtifacts ───────────────────────────────────

    @Test
    void extractManagedArtifactsReadsDependencyManagement() throws IOException {
        Path bom = tempDir.resolve("shared-bom/pom.xml");
        writePom(bom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.komet</groupId>
                    <artifactId>shared-bom</artifactId>
                    <version>3.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>dev.ikm.tinkar</groupId>
                                <artifactId>tinkar-core</artifactId>
                                <version>1.0.0</version>
                            </dependency>
                            <dependency>
                                <groupId>dev.ikm.komet</groupId>
                                <artifactId>komet-core</artifactId>
                                <version>2.0.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        Set<PublishedArtifactSet.Artifact> managed =
                BomAnalysis.extractManagedArtifacts(bom);

        assertThat(managed).containsExactlyInAnyOrder(
                new PublishedArtifactSet.Artifact("dev.ikm.tinkar", "tinkar-core"),
                new PublishedArtifactSet.Artifact("dev.ikm.komet", "komet-core"));
    }

    @Test
    void extractManagedArtifactsResolvesPropertyReferences() throws IOException {
        Path bom = tempDir.resolve("shared-bom/pom.xml");
        writePom(bom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.komet</groupId>
                    <artifactId>shared-bom</artifactId>
                    <version>3.0.0-SNAPSHOT</version>
                    <properties>
                        <komet.groupId>dev.ikm.komet</komet.groupId>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>${komet.groupId}</groupId>
                                <artifactId>komet-core</artifactId>
                                <version>2.0.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        Set<PublishedArtifactSet.Artifact> managed =
                BomAnalysis.extractManagedArtifacts(bom);

        assertThat(managed).containsExactly(
                new PublishedArtifactSet.Artifact("dev.ikm.komet", "komet-core"));
    }

    @Test
    void extractManagedArtifactsEmptyWhenNoDependencyManagement() throws IOException {
        Path bom = tempDir.resolve("plain/pom.xml");
        writePom(bom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.example</groupId>
                    <artifactId>plain</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        assertThat(BomAnalysis.extractManagedArtifacts(bom)).isEmpty();
    }

    @Test
    void extractManagedArtifactsEmptyWhenFileAbsent() throws IOException {
        assertThat(BomAnalysis.extractManagedArtifacts(
                tempDir.resolve("nope/pom.xml"))).isEmpty();
    }

    // ── analyzeCascadeIssues: #794 coverage via managing BOM ──────

    /**
     * Regression for #794: the consumer imports a shared BOM that manages the
     * upstream's artifact (but the BOM is not the upstream's own BOM, and no
     * version-property is declared). Before #794 this edge was a false-positive
     * gap; now it is covered.
     */
    @Test
    void managingBomCoversEdgeWithoutVersionProperty() throws IOException {
        writeSharedBom(tempDir.resolve("shared-bom/pom.xml"));
        writeLeaf(tempDir.resolve("tinkar-core/pom.xml"),
                "dev.ikm.tinkar", "tinkar-core");
        writeConsumerImportingSharedBom(tempDir.resolve("app/pom.xml"));

        Manifest manifest = manifestOf(
                sub("shared-bom", "dev.ikm.komet", "3.0.0-SNAPSHOT", List.of()),
                sub("tinkar-core", "dev.ikm.tinkar", "1.0.0-SNAPSHOT", List.of()),
                sub("app", "dev.ikm.komet", "1-SNAPSHOT", List.of(
                        new Dependency("shared-bom", "build"),
                        new Dependency("tinkar-core", "build"))));

        List<BomAnalysis.CascadeIssue> issues = BomAnalysis.analyzeCascadeIssues(
                tempDir, manifest,
                scanAll(tempDir, "shared-bom", "tinkar-core", "app"));

        assertThat(issues).isEmpty();
    }

    /**
     * The managing BOM resolves {@code ${property}} groupIds, and the
     * coverage check still matches the upstream's literal published GA.
     */
    @Test
    void managingBomCoversEdgeAcrossPropertyResolvedGroupId() throws IOException {
        writeSharedBom(tempDir.resolve("shared-bom/pom.xml"));
        // komet-core is managed via ${komet.groupId} in the shared BOM.
        writeLeaf(tempDir.resolve("komet-core/pom.xml"),
                "dev.ikm.komet", "komet-core");
        writeConsumerImportingSharedBom(tempDir.resolve("app/pom.xml"));

        Manifest manifest = manifestOf(
                sub("shared-bom", "dev.ikm.komet", "3.0.0-SNAPSHOT", List.of()),
                sub("komet-core", "dev.ikm.komet", "2.0.0-SNAPSHOT", List.of()),
                sub("app", "dev.ikm.komet", "1-SNAPSHOT", List.of(
                        new Dependency("komet-core", "build"))));

        List<BomAnalysis.CascadeIssue> issues = BomAnalysis.analyzeCascadeIssues(
                tempDir, manifest,
                scanAll(tempDir, "shared-bom", "komet-core", "app"));

        assertThat(issues).isEmpty();
    }

    /**
     * True positive preserved: an upstream that no imported BOM manages, with
     * no version-property, is still reported as a gap.
     */
    @Test
    void unmanagedUpstreamWithoutVersionPropertyStillReportsGap() throws IOException {
        writeSharedBom(tempDir.resolve("shared-bom/pom.xml"));
        // 'lib' is NOT in the shared BOM's dependencyManagement.
        writeLeaf(tempDir.resolve("lib/pom.xml"), "org.example", "lib");
        writeConsumerImportingSharedBom(tempDir.resolve("app/pom.xml"));

        Manifest manifest = manifestOf(
                sub("shared-bom", "dev.ikm.komet", "3.0.0-SNAPSHOT", List.of()),
                sub("lib", "org.example", "1.0.0-SNAPSHOT", List.of()),
                sub("app", "dev.ikm.komet", "1-SNAPSHOT", List.of(
                        new Dependency("lib", "build"))));

        List<BomAnalysis.CascadeIssue> issues = BomAnalysis.analyzeCascadeIssues(
                tempDir, manifest,
                scanAll(tempDir, "shared-bom", "lib", "app"));

        assertThat(issues)
                .extracting(BomAnalysis.CascadeIssue::subprojectName,
                        BomAnalysis.CascadeIssue::dependsOn)
                .containsExactly(tuple("app", "lib"));
    }

    /**
     * A declared version-property covers the edge even when no BOM manages
     * the upstream — existing behavior, regression-guarded.
     */
    @Test
    void versionPropertyCoversEdge() throws IOException {
        writeLeaf(tempDir.resolve("lib/pom.xml"), "org.example", "lib");
        writePom(tempDir.resolve("app/pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.example</groupId>
                    <artifactId>app</artifactId>
                    <version>1-SNAPSHOT</version>
                </project>
                """);

        Manifest manifest = manifestOf(
                sub("lib", "org.example", "1.0.0-SNAPSHOT", List.of()),
                sub("app", "org.example", "1-SNAPSHOT", List.of(
                        new Dependency("lib", "build", "lib.version"))));

        List<BomAnalysis.CascadeIssue> issues = BomAnalysis.analyzeCascadeIssues(
                tempDir, manifest, scanAll(tempDir, "lib", "app"));

        assertThat(issues).isEmpty();
    }

    /**
     * Existing structural path preserved: importing the upstream's <em>own</em>
     * BOM (BOM GA == upstream's published GA) covers the edge.
     */
    @Test
    void importingUpstreamsOwnBomCoversEdge() throws IOException {
        // 'shared-bom' subproject publishes dev.ikm.komet:shared-bom; the
        // consumer depends on shared-bom and imports it directly.
        writeSharedBom(tempDir.resolve("shared-bom/pom.xml"));
        writeConsumerImportingSharedBom(tempDir.resolve("app/pom.xml"));

        Manifest manifest = manifestOf(
                sub("shared-bom", "dev.ikm.komet", "3.0.0-SNAPSHOT", List.of()),
                sub("app", "dev.ikm.komet", "1-SNAPSHOT", List.of(
                        new Dependency("shared-bom", "build"))));

        List<BomAnalysis.CascadeIssue> issues = BomAnalysis.analyzeCascadeIssues(
                tempDir, manifest, scanAll(tempDir, "shared-bom", "app"));

        assertThat(issues).isEmpty();
    }

    /**
     * The ike-komet-wsr shape: several consumers each importing one shared BOM
     * that manages every upstream's artifacts → no gaps across the workspace.
     */
    @Test
    void sharedBomCoversAllEdgesAcrossMultipleConsumers() throws IOException {
        writeSharedBom(tempDir.resolve("shared-bom/pom.xml"));
        writeLeaf(tempDir.resolve("tinkar-core/pom.xml"),
                "dev.ikm.tinkar", "tinkar-core");
        writeLeaf(tempDir.resolve("komet-core/pom.xml"),
                "dev.ikm.komet", "komet-core");
        writeConsumerImportingSharedBom(tempDir.resolve("app-a/pom.xml"));
        writeConsumerImportingSharedBom(tempDir.resolve("app-b/pom.xml"));

        Manifest manifest = manifestOf(
                sub("shared-bom", "dev.ikm.komet", "3.0.0-SNAPSHOT", List.of()),
                sub("tinkar-core", "dev.ikm.tinkar", "1.0.0-SNAPSHOT", List.of()),
                sub("komet-core", "dev.ikm.komet", "2.0.0-SNAPSHOT", List.of()),
                sub("app-a", "dev.ikm.komet", "1-SNAPSHOT", List.of(
                        new Dependency("tinkar-core", "build"),
                        new Dependency("komet-core", "build"))),
                sub("app-b", "dev.ikm.komet", "1-SNAPSHOT", List.of(
                        new Dependency("tinkar-core", "build"),
                        new Dependency("komet-core", "build"))));

        List<BomAnalysis.CascadeIssue> issues = BomAnalysis.analyzeCascadeIssues(
                tempDir, manifest, scanAll(tempDir, "shared-bom",
                        "tinkar-core", "komet-core", "app-a", "app-b"));

        assertThat(issues).isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────

    /** A shared BOM managing dev.ikm.tinkar:tinkar-core and (via property)
     *  dev.ikm.komet:komet-core. */
    private void writeSharedBom(Path pom) throws IOException {
        writePom(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.komet</groupId>
                    <artifactId>shared-bom</artifactId>
                    <version>3.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <properties>
                        <komet.groupId>dev.ikm.komet</komet.groupId>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>dev.ikm.tinkar</groupId>
                                <artifactId>tinkar-core</artifactId>
                                <version>1.0.0</version>
                            </dependency>
                            <dependency>
                                <groupId>${komet.groupId}</groupId>
                                <artifactId>komet-core</artifactId>
                                <version>2.0.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
    }

    /** A consumer POM importing the shared BOM. */
    private void writeConsumerImportingSharedBom(Path pom) throws IOException {
        writePom(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dev.ikm.komet</groupId>
                    <artifactId>app</artifactId>
                    <version>1-SNAPSHOT</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>dev.ikm.komet</groupId>
                                <artifactId>shared-bom</artifactId>
                                <version>3.0.0-SNAPSHOT</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
    }

    private void writeLeaf(Path pom, String groupId, String artifactId)
            throws IOException {
        writePom(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """.formatted(groupId, artifactId));
    }

    private Map<String, Set<PublishedArtifactSet.Artifact>> scanAll(
            Path ws, String... names) throws IOException {
        Map<String, Set<PublishedArtifactSet.Artifact>> map =
                new LinkedHashMap<>();
        for (String name : names) {
            map.put(name, PublishedArtifactSet.scan(ws.resolve(name)));
        }
        return map;
    }

    private static Subproject sub(String name, String groupId, String version,
                                  List<Dependency> deps) {
        return new Subproject(name, null, null, "main", version, groupId,
                deps, null, null, null, null, null, null, null);
    }

    private static Manifest manifestOf(Subproject... subs) {
        Map<String, Subproject> map = new LinkedHashMap<>();
        for (Subproject s : subs) {
            map.put(s.name(), s);
        }
        return new Manifest("1.0", null, null, null, map, null);
    }

    private void writePom(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}

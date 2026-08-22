package network.ike.tooling.buildreport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the two questions a repository finding has to answer for the
 * reader: what is this repository, and whose is it.
 */
class RepositoryProvenanceTest {

    private static final Path ROOT = Path.of("/ws/ike-komet-wsr");

    @Test
    void declaredIdStripsMavenFoursDisambiguationHash() {
        assertThat(RepositoryProvenance.declaredId(
                "spring-release-e22ad65be0b5fee6143d584bba6f2b61b37bb6bf"))
                .isEqualTo("spring-release");
        assertThat(RepositoryProvenance.declaredId("central")).isEqualTo("central");
        assertThat(RepositoryProvenance.declaredId(null)).isEqualTo("unknown");
    }

    @Test
    void declaredIdKeepsHyphenatedIdsThatAreNotHashes() {
        assertThat(RepositoryProvenance.declaredId("sonatype-nexus-snapshots"))
                .isEqualTo("sonatype-nexus-snapshots");
        assertThat(RepositoryProvenance.declaredId("repo-0123456789")).isEqualTo("repo-0123456789");
    }

    @Test
    void aThirdPartyPomIsNamedAsTheDeclarer() {
        Finding enriched = enrich(
                "spring-release-e22ad65be0b5fee6143d584bba6f2b61b37bb6bf",
                "https://repo.spring.io/release",
                model("org.springdoc", "springdoc-openapi", "2.6.0",
                        Path.of("/home/kec/.m2/repository/org/springdoc/springdoc-openapi/2.6.0/pom.xml"),
                        "spring-release", "https://repo.spring.io/release"));

        assertThat(enriched.context().get(Finding.CONTEXT_DECLARED_BY))
                .isEqualTo("declared by the POM of org.springdoc:springdoc-openapi:2.6.0"
                        + " — a dependency, not this workspace");
    }

    @Test
    void aWorkspacePomIsDistinguishedFromADependency() {
        Finding enriched = enrich(
                "ike-restricted-bff715265cb50fa01210a181420037a7528c7ddd",
                "https://nexus.tinkar.org/repository/ike-restricted/",
                model("dev.ikm", "komet-bom", "1.0.0", ROOT.resolve("komet-bom/pom.xml"),
                        "ike-restricted", "https://nexus.tinkar.org/repository/ike-restricted"));

        assertThat(enriched.context().get(Finding.CONTEXT_DECLARED_BY))
                .isEqualTo("declared in this workspace by dev.ikm:komet-bom:1.0.0");
    }

    @Test
    void aRepositoryNoObservedPomDeclaredGetsNoProvenanceLine() {
        Finding enriched = enrich("central", "https://repo.maven.apache.org/maven2",
                model("org.jruby", "jruby-stdlib", "10.0.3.0", Path.of("/m2/jruby-stdlib.pom"),
                        "mavengems", "mavengem:https://rubygems.org"));

        assertThat(enriched.context()).doesNotContainKey(Finding.CONTEXT_DECLARED_BY);
    }

    @Test
    void nonRepositoryFindingsPassThroughUntouched() {
        Finding model = new Finding(
                FindingCategory.MODEL, Severity.WARNING, "model-problem/x", "rocks-kb imports komet-bom");

        assertThat(RepositoryProvenance.enrich(List.of(model), List.of(), List.of(), null, ROOT))
                .containsExactly(model);
    }

    @TempDir
    private Path localRepository;

    @Test
    void aResolvedDependencyPomIsFoundByItsRepositoryUrl() throws IOException {
        Path pom = writePom("org/jruby", "jruby-stdlib", "9.4.14.0", """
                <project>
                  <repositories>
                    <repository>
                      <id>mavengems</id>
                      <url>mavengem:https://rubygems.org</url>
                    </repository>
                  </repositories>
                </project>
                """);

        Finding enriched = enrichWithPoms(
                "mavengems-51da717cfb6c8ccc8113d76d8e4a2aaa714a0f55",
                "mavengem:https://rubygems.org",
                List.of(pom));

        assertThat(enriched.context().get(Finding.CONTEXT_DECLARED_BY))
                .isEqualTo("declared by the POM of org.jruby:jruby-stdlib:9.4.14.0"
                        + " — a dependency, not this workspace");
    }

    @Test
    void aResolvedPomThatDoesNotMentionTheUrlIsNotNamed() throws IOException {
        Path pom = writePom("org/other", "other", "1.0", "<project><name>other</name></project>");

        Finding enriched = enrichWithPoms("mavengems", "mavengem:https://rubygems.org", List.of(pom));

        assertThat(enriched.context()).doesNotContainKey(Finding.CONTEXT_DECLARED_BY);
    }

    @Test
    void coordinatesFallBackToArtifactAndVersionWithoutARepositoryRoot() {
        Path pom = Path.of("/elsewhere/org/jruby/jruby-stdlib/9.4.14.0/jruby-stdlib-9.4.14.0.pom");

        assertThat(RepositoryProvenance.coordinatesOf(pom, null)).isEqualTo("jruby-stdlib:9.4.14.0");
        assertThat(RepositoryProvenance.coordinatesOf(Path.of("/a/not-a-layout.txt"), null)).isEmpty();
    }

    private Path writePom(String groupPath, String artifactId, String version, String xml)
            throws IOException {
        Path directory = localRepository.resolve(groupPath).resolve(artifactId).resolve(version);
        Files.createDirectories(directory);
        Path pom = directory.resolve(artifactId + "-" + version + ".pom");
        Files.writeString(pom, xml);
        return pom;
    }

    private Finding enrichWithPoms(String repositoryId, String url, List<Path> poms) {
        Finding finding = new Finding(
                FindingCategory.REPOSITORY, Severity.WARNING,
                "repository/metadata-resolve-failed/" + RepositoryProvenance.declaredId(repositoryId),
                "/.meta/prefixes.txt: no connector",
                Map.of(Finding.CONTEXT_REPOSITORY_ID, repositoryId, Finding.CONTEXT_REPOSITORY_URL, url));
        return RepositoryProvenance.enrich(List.of(finding), List.of(), poms, localRepository, ROOT).get(0);
    }

    private static Finding enrich(String repositoryId, String url, ModelObservations.FileModel observed) {
        Finding finding = new Finding(
                FindingCategory.REPOSITORY, Severity.WARNING,
                "repository/metadata-resolve-failed/" + RepositoryProvenance.declaredId(repositoryId),
                "metadata: HTTP Status: 401",
                Map.of(Finding.CONTEXT_REPOSITORY_ID, repositoryId, Finding.CONTEXT_REPOSITORY_URL, url));
        return RepositoryProvenance.enrich(List.of(finding), List.of(observed), List.of(), null, ROOT)
                .get(0);
    }

    private static ModelObservations.FileModel model(
            String groupId, String artifactId, String version, Path pom, String repoId, String repoUrl) {
        return new ModelObservations.FileModel(
                groupId, artifactId, version, pom, List.of(),
                List.of(new ModelObservations.RepositoryDeclaration(repoId, repoUrl, false)));
    }
}

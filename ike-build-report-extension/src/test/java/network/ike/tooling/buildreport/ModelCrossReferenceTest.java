package network.ike.tooling.buildreport;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session-end cross-referencing of file-model observations — the
 * derived replacement for Maven's log-text-only MNG-8012 warning.
 */
class ModelCrossReferenceTest {

    private static final Path ROOT = Path.of("/workspace");

    private static ModelObservations.FileModel member(
            String artifactId, String version, ModelObservations.BomImport... imports) {
        return new ModelObservations.FileModel(
                "dev.ikm.komet", artifactId, version,
                ROOT.resolve(artifactId).resolve("pom.xml"), List.of(imports));
    }

    private static ModelObservations.BomImport bomImport(String artifactId, String version, int line) {
        return new ModelObservations.BomImport("dev.ikm.komet", artifactId, version, line);
    }

    @Test
    void importMatchingReactorMemberYieldsSourceKeyedFinding() {
        List<Finding> findings = ModelCrossReference.findings(List.of(
                member("komet-bom", "3.0.7-SNAPSHOT"),
                member("rocks-kb", "0.1.0-SNAPSHOT", bomImport("komet-bom", "3.0.7-SNAPSHOT", 55))), ROOT);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).key()).isEqualTo(ModelCrossReference.BOM_IMPORT_KEY);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.WARNING);
        assertThat(findings.get(0).detail())
                .isEqualTo("rocks-kb imports dev.ikm.komet:komet-bom:3.0.7-SNAPSHOT (rocks-kb/pom.xml:55)");
    }

    @Test
    void releasedVersionImportDoesNotMatchReactorSnapshot() {
        List<Finding> findings = ModelCrossReference.findings(List.of(
                member("komet-bom", "3.0.7-SNAPSHOT"),
                member("rocks-kb", "0.1.0-SNAPSHOT", bomImport("komet-bom", "3.0.6", 55))), ROOT);

        assertThat(findings).isEmpty();
    }

    @Test
    void unresolvableVersionNeverMatches() {
        List<Finding> findings = ModelCrossReference.findings(List.of(
                member("komet-bom", "3.0.7-SNAPSHOT"),
                member("rocks-kb", "0.1.0-SNAPSHOT", bomImport("komet-bom", null, 55))), ROOT);

        assertThat(findings).isEmpty();
    }

    @Test
    void externalModelsAreNeitherMembersNorConsumers() {
        ModelObservations.FileModel external = new ModelObservations.FileModel(
                "dev.ikm.komet", "komet-bom", "3.0.7-SNAPSHOT",
                Path.of("/Users/kec/.m2/repository/dev/ikm/komet/komet-bom/3.0.7-SNAPSHOT/komet-bom.pom"),
                List.of());
        List<Finding> findings = ModelCrossReference.findings(List.of(
                external,
                member("rocks-kb", "0.1.0-SNAPSHOT", bomImport("komet-bom", "3.0.7-SNAPSHOT", 55))), ROOT);

        assertThat(findings).isEmpty();
    }

    @Test
    void twoConsumersYieldTwoFindingsUnderOneKey() {
        List<Finding> findings = ModelCrossReference.findings(List.of(
                member("komet-bom", "3.0.7-SNAPSHOT"),
                member("rocks-kb", "0.1.0-SNAPSHOT", bomImport("komet-bom", "3.0.7-SNAPSHOT", 55)),
                member("complex-clause-plugin", "1-SNAPSHOT", bomImport("komet-bom", "3.0.7-SNAPSHOT", 70))), ROOT);

        assertThat(findings).hasSize(2);
        assertThat(findings).allSatisfy(f -> assertThat(f.key()).isEqualTo(ModelCrossReference.BOM_IMPORT_KEY));
        LedgerEvaluation evaluation = Ledger.empty().evaluate(findings);
        assertThat(evaluation.attention()).hasSize(1);
        assertThat(evaluation.attention().get(0).observed()).isEqualTo(2);
    }

    @Test
    void repeatedReadsOfTheSamePomCountOnce() {
        ModelObservations.FileModel consumer =
                member("rocks-kb", "0.1.0-SNAPSHOT", bomImport("komet-bom", "3.0.7-SNAPSHOT", 55));
        List<Finding> findings = ModelCrossReference.findings(List.of(
                member("komet-bom", "3.0.7-SNAPSHOT"),
                member("komet-bom", "3.0.7-SNAPSHOT"),
                consumer,
                consumer), ROOT);

        assertThat(findings).hasSize(1);
    }

    @Test
    void resolveVersionHandlesLiteralsPropertiesAndFailures() {
        Map<String, String> properties = Map.of(
                "komet-bom.version", "3.0.7-SNAPSHOT",
                "nested.version", "${other}");

        assertThat(ModelCrossReference.resolveVersion("3.0.7-SNAPSHOT", properties))
                .isEqualTo("3.0.7-SNAPSHOT");
        assertThat(ModelCrossReference.resolveVersion("${komet-bom.version}", properties))
                .isEqualTo("3.0.7-SNAPSHOT");
        assertThat(ModelCrossReference.resolveVersion("${missing.version}", properties)).isNull();
        assertThat(ModelCrossReference.resolveVersion("${nested.version}", properties)).isNull();
        assertThat(ModelCrossReference.resolveVersion(null, properties)).isNull();
    }
}

package network.ike.knowledge.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Wire-format fidelity: every request and result round-trips through its properties
 * form, and the value invariants hold.
 */
class RequestCodecTest {

    private static final ViewSpec VIEW = ViewSpec.of(Map.of(
            ViewSpec.STAMP_ALLOWED_STATES, "Active",
            ViewSpec.EDIT_DEFAULT_MODULE, "#RICH_SURFACE_MODULE"));

    @Test
    @DisplayName("AssembleRequest round-trips; STORE_SEED is first-position-only; inputs keep order")
    void assembleRequest() {
        AssembleRequest request = new AssembleRequest(
                Path.of("target/kb"), true,
                List.of(new ArtifactInput(ArtifactInput.Role.STORE_SEED, Path.of("base-reasoned-sa.zip")),
                        new ArtifactInput(ArtifactInput.Role.PB, Path.of("starter.pb.zip")),
                        new ArtifactInput(ArtifactInput.Role.CHANGESET, Path.of("set-changeset.zip"))),
                VIEW, true, Optional.of("ExampleReasonerService"), Optional.of(Path.of("install/here")));
        AssembleRequest decoded = AssembleRequest.fromProperties(request.toProperties());
        assertThat(decoded).isEqualTo(request);
        assertThat(decoded.inputs()).containsExactlyElementsOf(request.inputs());

        assertThatIllegalArgumentException().isThrownBy(() -> new AssembleRequest(
                        Path.of("target/kb"), false,
                        List.of(new ArtifactInput(ArtifactInput.Role.PB, Path.of("starter.pb.zip")),
                                new ArtifactInput(ArtifactInput.Role.STORE_SEED, Path.of("late-seed.zip"))),
                        ViewSpec.empty(), true, Optional.empty(), Optional.empty()))
                .withMessageContaining("first position");
        assertThatIllegalArgumentException().isThrownBy(() -> new AssembleRequest(
                Path.of("target/kb"), false, List.of(), ViewSpec.empty(), true,
                Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("Reasoned-pb export round-trips, and demands classification"
            + " — reasoned-pb means inferred results baked in (ike-issues#933)")
    void reasonedPbExport() {
        AssembleRequest request = new AssembleRequest(
                Path.of("target/kb"), true,
                List.of(new ArtifactInput(ArtifactInput.Role.PB, Path.of("starter.pb.zip"))),
                VIEW, true, Optional.empty(), Optional.empty(),
                Optional.of(Path.of("target/set-reasoned-pb.zip")));
        assertThat(AssembleRequest.fromProperties(request.toProperties())).isEqualTo(request);

        assertThatIllegalArgumentException().isThrownBy(() -> new AssembleRequest(
                        Path.of("target/kb"), true,
                        List.of(new ArtifactInput(ArtifactInput.Role.PB, Path.of("starter.pb.zip"))),
                        ViewSpec.empty(), false, Optional.empty(), Optional.empty(),
                        Optional.of(Path.of("target/set-reasoned-pb.zip"))))
                .withMessageContaining("classify");

        AssembleResult produced = new AssembleResult(
                List.of(new LoadSummary("starter.pb.zip", new EntityCounts(1, 2, 3, 4))),
                Optional.of(new ClassificationSummary("ExampleReasonerService", 10, 2, 3, 42)),
                Optional.empty(), Optional.of(Path.of("target/set-reasoned-pb.zip")));
        assertThat(AssembleResult.fromProperties(produced.toProperties())).isEqualTo(produced);
    }

    @Test
    @DisplayName("Indexed lists fail closed: a gap is an error, never a silent truncation")
    void indexedListsFailClosed() {
        AssembleRequest request = new AssembleRequest(Path.of("target/kb"), false,
                List.of(new ArtifactInput(ArtifactInput.Role.PB, Path.of("a.pb.zip")),
                        new ArtifactInput(ArtifactInput.Role.CHANGESET, Path.of("b-changeset.zip"))),
                ViewSpec.empty(), true, Optional.empty(), Optional.empty());
        java.util.Properties wire = request.toProperties();
        wire.remove("input.1.role");
        wire.remove("input.1.path");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssembleRequest.fromProperties(wire))
                .withMessageContaining("gap");
    }

    @Test
    @DisplayName("Boolean wire values are strict: only true/false, so classify=yes cannot silently disable")
    void strictBooleans() {
        AssembleRequest request = new AssembleRequest(Path.of("target/kb"), false,
                List.of(new ArtifactInput(ArtifactInput.Role.PB, Path.of("a.pb.zip"))),
                ViewSpec.empty(), true, Optional.empty(), Optional.empty());
        java.util.Properties wire = request.toProperties();
        wire.setProperty("classify", "yes");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AssembleRequest.fromProperties(wire))
                .withMessageContaining("true or false");
    }

    @Test
    @DisplayName("AssembleResult round-trips with and without a classification summary")
    void assembleResult() {
        AssembleResult classified = new AssembleResult(
                List.of(new LoadSummary("starter.pb.zip", new EntityCounts(388, 4450, 42, 429)),
                        new LoadSummary("set-changeset.zip", new EntityCounts(15, 129, 4, 1))),
                Optional.of(new ClassificationSummary("ExampleReasonerService", 403, 17, 21, 297)),
                Optional.of(Path.of("target/kb-effective-view.properties")));
        assertThat(AssembleResult.fromProperties(classified.toProperties())).isEqualTo(classified);
        assertThat(classified.loads().getFirst().counts().total()).isEqualTo(5309);

        AssembleResult unclassified = new AssembleResult(
                List.of(new LoadSummary("starter.pb.zip", new EntityCounts(1, 2, 3, 4))),
                Optional.empty(), Optional.empty());
        assertThat(AssembleResult.fromProperties(unclassified.toProperties())).isEqualTo(unclassified);
    }

    @Test
    @DisplayName("Export request and result round-trip, optionals encoded by absence")
    void export() {
        ExportRequest request = new ExportRequest(Path.of("target/set-changeset.zip"),
                Optional.of(Path.of("target/koncepts.yml")), Optional.empty(), VIEW);
        assertThat(ExportRequest.fromProperties(request.toProperties())).isEqualTo(request);

        ExportResult result = new ExportResult(Path.of("target/set-changeset.zip"),
                new EntityCounts(15, 129, 4, 1), Optional.empty());
        ExportResult decoded = ExportResult.fromProperties(result.toProperties());
        assertThat(decoded).isEqualTo(result);
        assertThat(decoded.konceptsYmlFile()).isEmpty();
    }

    @Test
    @DisplayName("Bindings request enforces mode semantics and round-trips membership refs")
    void bindings() {
        BindingsRequest scan = new BindingsRequest(BindingsRequest.Mode.STORE_SCAN,
                Path.of("target/generated-sources/bindings"), "network.ike.example", "ExampleTerms",
                Optional.empty(),
                List.of(ConceptRef.parse("#EXAMPLE_MEMBERSHIP_PATTERN")),
                ViewSpec.empty());
        assertThat(BindingsRequest.fromProperties(scan.toProperties())).isEqualTo(scan);

        assertThatIllegalArgumentException().isThrownBy(() -> new BindingsRequest(
                        BindingsRequest.Mode.LEDGER, Path.of("out"), "pkg", "Cls", Optional.empty(),
                        List.of(ConceptRef.parse("#SOME_PATTERN")), ViewSpec.empty()))
                .withMessageContaining("STORE_SCAN");
        assertThatIllegalArgumentException().isThrownBy(() -> new BindingsRequest(
                        BindingsRequest.Mode.STORE_SCAN, Path.of("out"), "pkg", "Cls",
                        Optional.of("network.ike.example.ExampleSource"), List.of(), ViewSpec.empty()))
                .withMessageContaining("LEDGER");

        BindingsResult result = new BindingsResult(Path.of("out/ExampleTerms.java"), 21);
        assertThat(BindingsResult.fromProperties(result.toProperties())).isEqualTo(result);
    }

    @Test
    @DisplayName("Verify request validates DELTA's prior release; report round-trips and gates on ERROR")
    void verify() {
        VerifyRequest request = new VerifyRequest(Path.of("target/set-changeset.zip"),
                List.of(new ArtifactInput(ArtifactInput.Role.PB, Path.of("starter.pb.zip"))),
                Set.of(VerifyRequest.Check.FIT_REFERENCES, VerifyRequest.Check.PRESENCE),
                Optional.empty(), VIEW);
        assertThat(VerifyRequest.fromProperties(request.toProperties())).isEqualTo(request);

        assertThatIllegalArgumentException().isThrownBy(() -> new VerifyRequest(
                        Path.of("artifact.zip"), List.of(), Set.of(VerifyRequest.Check.DELTA),
                        Optional.empty(), ViewSpec.empty()))
                .withMessageContaining("prior release");

        VerifyReport failing = new VerifyReport(List.of(
                new Finding(Finding.Severity.ERROR, VerifyRequest.Check.FIT_REFERENCES,
                        "3c9d1e7a-52f4-4b8e-b1a6-0d5e8c2f7a41",
                        "Reference resolves in neither the artifact nor the declared base"),
                new Finding(Finding.Severity.INFO, VerifyRequest.Check.PRESENCE,
                        "set-changeset.zip", "149 entities match the manifest")));
        VerifyReport decoded = VerifyReport.fromProperties(failing.toProperties());
        assertThat(decoded).isEqualTo(failing);
        assertThat(decoded.passed()).isFalse();
        assertThat(new VerifyReport(List.of()).passed()).isTrue();

        // The verifier's own wire fails closed: a gap cannot silently pass the gate.
        java.util.Properties gappy = failing.toProperties();
        gappy.remove("finding.1.severity");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> VerifyReport.fromProperties(gappy))
                .withMessageContaining("gap");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Finding(Finding.Severity.ERROR, VerifyRequest.Check.PRESENCE,
                        "component", "  "))
                .withMessageContaining("message");
    }
}

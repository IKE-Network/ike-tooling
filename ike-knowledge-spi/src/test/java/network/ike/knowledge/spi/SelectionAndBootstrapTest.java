package network.ike.knowledge.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Selection and the forked-JVM bootstrap, exercised through the real ServiceLoader
 * mechanics with the real (trivial) {@link TextService} implementations registered in
 * this module's test resources — no mocks.
 */
class SelectionAndBootstrapTest {

    @Test
    @DisplayName("Two implementations without a selection is an error that lists both")
    void ambiguousSelection() {
        assertThatIllegalStateException()
                .isThrownBy(() -> KnowledgeServices.select(TextService.class, Optional.empty()))
                .withMessageContaining("found 2")
                .withMessageContaining(ReversingTextService.class.getName())
                .withMessageContaining(UppercasingTextService.class.getName());
    }

    @Test
    @DisplayName("Named selection matches the implementation's simple name exactly")
    void namedSelection() {
        TextService selected = KnowledgeServices.select(TextService.class,
                Optional.of("ReversingTextService"));
        assertThat(selected.transform(new TextRequest("ledger")).text()).isEqualTo("regdel");

        assertThatIllegalStateException()
                .isThrownBy(() -> KnowledgeServices.select(TextService.class, Optional.of("NoSuchService")))
                .withMessageContaining("NoSuchService");
    }

    @Test
    @DisplayName("No implementation on the classpath names the missing dependency, not an NPE")
    void absentImplementation() {
        assertThatIllegalStateException()
                .isThrownBy(() -> KnowledgeServices.select(KnowledgeBaseAssembler.class, Optional.empty()))
                .withMessageContaining("found none")
                .withMessageContaining("Declare an implementation dependency");
    }

    @Test
    @DisplayName("The bootstrap runs a service end-to-end: request file in, result file out")
    void bootstrapEndToEnd(@TempDir Path tempDir) throws Exception {
        Path requestFile = tempDir.resolve("request.properties");
        Properties request = new TextRequest("knowledge set").toProperties();
        request.setProperty("implementation", "UppercasingTextService");
        try (OutputStream out = Files.newOutputStream(requestFile)) {
            request.store(out, null);
        }
        Path resultFile = tempDir.resolve("nested/result.properties");

        IkeServiceBootstrap.main(new String[]{TextService.class.getName(),
                requestFile.toString(), resultFile.toString()});

        Properties result = new Properties();
        try (InputStream in = Files.newInputStream(resultFile)) {
            result.load(in);
        }
        assertThat(TextResult.fromProperties(result).text()).isEqualTo("KNOWLEDGE SET");
    }

    @Test
    @DisplayName("The bootstrap rejects bad arguments and non-service interfaces")
    void bootstrapValidation(@TempDir Path tempDir) throws Exception {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> IkeServiceBootstrap.main(new String[]{"only-one-arg"}))
                .withMessageContaining("Usage");

        Path requestFile = tempDir.resolve("request.properties");
        try (OutputStream out = Files.newOutputStream(requestFile)) {
            new Properties().store(out, null);
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> IkeServiceBootstrap.main(new String[]{String.class.getName(),
                        requestFile.toString(), tempDir.resolve("result.properties").toString()}))
                .withMessageContaining("is not a");
    }
}

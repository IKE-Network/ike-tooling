package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectoryTemplateSourceTest {

    @Test
    void readsFilesUnderRoot(@TempDir Path root) throws IOException {
        Files.writeString(
                Files.createDirectories(root.resolve("a")).resolve("b"),
                "body");
        DirectoryTemplateSource src = new DirectoryTemplateSource(root);
        assertThat(new String(src.read("a/b"), StandardCharsets.UTF_8))
                .isEqualTo("body");
    }

    @Test
    void missingFileThrows(@TempDir Path root) {
        DirectoryTemplateSource src = new DirectoryTemplateSource(root);
        assertThatThrownBy(() -> src.read("nope"))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void escapeAttemptRejected(@TempDir Path root) {
        DirectoryTemplateSource src = new DirectoryTemplateSource(root);
        assertThatThrownBy(() -> src.read("../etc/passwd"))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void directoryNotAccepted(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("subdir"));
        DirectoryTemplateSource src = new DirectoryTemplateSource(root);
        assertThatThrownBy(() -> src.read("subdir"))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("not found");
    }
}

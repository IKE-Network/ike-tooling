package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CentralStageMojo} parameter validation
 * (IKE-Network/ike-issues#966). The staging work itself is covered
 * by {@code CentralStagingTest} and {@code GeneratedBomSwapTest}.
 */
class CentralStageMojoTest {

    @Test
    void requireDirectory_resolves_existing_directory(@TempDir Path dir) {
        assertThat(CentralStageMojo.requireDirectory(
                dir.toString(), "ike.central.buildRoot")).isEqualTo(dir);
    }

    @Test
    void requireDirectory_rejects_missing_path_naming_property(
            @TempDir Path dir) {
        Path missing = dir.resolve("absent");
        assertThatThrownBy(() -> CentralStageMojo.requireDirectory(
                missing.toString(), "ike.central.stagingDir"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("ike.central.stagingDir");
    }
}

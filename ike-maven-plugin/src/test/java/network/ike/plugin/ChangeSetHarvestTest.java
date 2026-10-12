package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Harvesting the change sets captured in an installed knowledge base into the project.
 */
class ChangeSetHarvestTest {

    @TempDir
    Path temp;

    @Test
    void harvestsWhatTheProjectLacksAndKeepsNameOrder() throws IOException {
        Path install = temp.resolve("installed");
        Path captured = Files.createDirectories(install.resolve("changeSets/src/main/resources"));
        Files.writeString(captured.resolve("KEC 20261012T0900 abc ike-cs.zip"), "first");
        Files.writeString(captured.resolve("KEC 20261012T1000 def ike-cs.zip"), "second");
        Path kept = temp.resolve("extension/changesets");
        Files.createDirectories(kept);
        Files.writeString(kept.resolve("KEC 20261012T0900 abc ike-cs.zip"), "first");

        assertThat(ChangeSetHarvest.harvest(ChangeSetHarvest.captured(install), kept))
                .extracting(p -> p.getFileName().toString())
                .containsExactly("KEC 20261012T1000 def ike-cs.zip");
        assertThat(ChangeSetHarvest.kept(kept)).extracting(p -> p.getFileName().toString())
                .containsExactly("KEC 20261012T0900 abc ike-cs.zip", "KEC 20261012T1000 def ike-cs.zip");
    }

    @Test
    void refusesAnOpenStoreAndAConflictingName() throws IOException {
        Path install = temp.resolve("installed");
        Path captured = Files.createDirectories(install.resolve("changeSets"));
        Files.writeString(captured.resolve("KEC 20261012T1100 ghi ike-cs.spool"), "open");
        assertThatThrownBy(() -> ChangeSetHarvest.captured(install))
                .isInstanceOf(MojoException.class).hasMessageContaining("is open");

        Files.delete(captured.resolve("KEC 20261012T1100 ghi ike-cs.spool"));
        Files.writeString(captured.resolve("same ike-cs.zip"), "captured");
        Path kept = Files.createDirectories(temp.resolve("kept"));
        Files.writeString(kept.resolve("same ike-cs.zip"), "different");
        assertThatThrownBy(() -> ChangeSetHarvest.harvest(ChangeSetHarvest.captured(install), kept))
                .isInstanceOf(MojoException.class).hasMessageContaining("differs");
    }

    @Test
    void noStoreMeansNothingCaptured() {
        assertThat(ChangeSetHarvest.captured(temp.resolve("absent"))).isEmpty();
    }
}

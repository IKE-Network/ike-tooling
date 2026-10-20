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
    void sortsOutChangeSetsWithoutTheExtensionModule() throws IOException {
        String overlay = "9ecc154c-e490-5cf8-805d-d2865d62aef3";
        Path captured = Files.createDirectories(temp.resolve("installed/changeSets"));
        Path authored = changeSet(captured.resolve("authored ike-cs.zip"), overlay + ",1f2016a6-960e-11e5-8994-feff819cdc9f", "SOLOR overlay module");
        Path chat = changeSet(captured.resolve("chat ike-cs.zip"), "631d5df5-5011-567a-9367-035d63dc47fb", "Conversation journal module");
        Path parking = temp.resolve("installed-sessions");

        assertThat(ChangeSetHarvest.sortOut(java.util.List.of(authored, chat), java.util.Set.of(overlay), parking))
                .containsExactly(authored);
        assertThat(parking.resolve("chat ike-cs.zip")).exists();
        assertThat(parking.resolve("authored ike-cs.zip")).doesNotExist();
    }

    private static Path changeSet(Path file, String name, String description) throws IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
            zip.write(("Manifest-Version: 1.0\r\n\r\nName: " + name + "\r\nDescription: " + description + "\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    @Test
    void noStoreMeansNothingCaptured() {
        assertThat(ChangeSetHarvest.captured(temp.resolve("absent"))).isEmpty();
    }
}

package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaffoldLockfileIoTest {

    @Test
    void emptyLockfileRoundTrips() {
        ScaffoldLockfile lf = ScaffoldLockfile.empty();
        String yaml = ScaffoldLockfileIo.writeToString(lf);
        ScaffoldLockfile parsed = ScaffoldLockfileIo.read(
                new StringReader(yaml));
        assertThat(parsed).isEqualTo(lf);
    }

    @Test
    void lockfileWithMixedTiersRoundTrips() {
        Instant when = Instant.parse("2026-04-23T10:15:00Z");
        ManagedElement el1 = new ManagedElement(
                "pluginGroups/pluginGroup[.=\"network.ike.tooling\"]",
                when, "7");
        ManagedElement el2 = new ManagedElement(
                "profiles/profile[@id=\"ike-nexus\"]", when, "7");

        ScaffoldLockfile lf = ScaffoldLockfile.empty()
                .withEntry("mvnw",
                        LockfileEntry.toolOwned("sha256:9f3c"))
                .withEntry(".mvn/maven.config",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED,
                                "sha256:b712", "sha256:b712"))
                .withEntry(".gitignore",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED_BLOCK,
                                "sha256:1234", "sha256:1234"))
                .withEntry("~/.m2/settings.xml",
                        LockfileEntry.modelManaged(List.of(el1, el2)))
                .withAppliedStamp("7", when);

        String yaml = ScaffoldLockfileIo.writeToString(lf);
        ScaffoldLockfile parsed = ScaffoldLockfileIo.read(
                new StringReader(yaml));

        assertThat(parsed.schema())
                .isEqualTo(ScaffoldLockfile.CURRENT_SCHEMA);
        assertThat(parsed.standardsVersion()).isEqualTo("7");
        assertThat(parsed.applied()).isEqualTo(when);
        assertThat(parsed.files().keySet())
                .containsExactly(
                        "mvnw",
                        ".mvn/maven.config",
                        ".gitignore",
                        "~/.m2/settings.xml");

        assertThat(parsed.files().get("mvnw"))
                .isEqualTo(LockfileEntry.toolOwned("sha256:9f3c"));
        LockfileEntry settings =
                parsed.files().get("~/.m2/settings.xml");
        assertThat(settings.tier())
                .isEqualTo(ScaffoldTier.MODEL_MANAGED);
        assertThat(settings.managedElements())
                .containsExactly(el1, el2);
    }

    @Test
    void writtenYamlIsHumanReadable() {
        ScaffoldLockfile lf = ScaffoldLockfile.empty()
                .withEntry("mvnw",
                        LockfileEntry.toolOwned("sha256:9f3c"))
                .withEntry(".mvn/maven.config",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED,
                                "sha256:b712", "sha256:b712"));

        String yaml = ScaffoldLockfileIo.writeToString(lf);

        // Block style (not flow { ... }); insertion order preserved:
        // schema first, files afterwards, mvnw before maven.config.
        assertThat(yaml).startsWith("schema:");
        assertThat(yaml.indexOf("mvnw"))
                .isLessThan(yaml.indexOf(".mvn/maven.config"));
        assertThat(yaml.indexOf("tier: tool-owned"))
                .isLessThan(yaml.indexOf("tier: tracked"));
        assertThat(yaml).doesNotContain("!!");
    }

    @Test
    void unsupportedSchemaRejected() {
        String yaml = "schema: 99\nfiles: {}\n";
        assertThatThrownBy(() ->
                ScaffoldLockfileIo.read(new StringReader(yaml)))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void missingTierRejected() {
        String yaml = """
                schema: 1
                files:
                  mvnw:
                    template-sha: sha256:abc
                """;
        assertThatThrownBy(() ->
                ScaffoldLockfileIo.read(new StringReader(yaml)))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("tier");
    }

    @Test
    void missingManagedElementFieldRejected() {
        String yaml = """
                schema: 1
                files:
                  ~/.m2/settings.xml:
                    tier: model-managed
                    managed-elements:
                      - path: x
                        installed-at: "2026-04-23T10:15:00Z"
                """;
        assertThatThrownBy(() ->
                ScaffoldLockfileIo.read(new StringReader(yaml)))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("standards-version");
    }

    @Test
    void emptyYamlRejected() {
        assertThatThrownBy(() ->
                ScaffoldLockfileIo.read(new StringReader("")))
                .isInstanceOf(ScaffoldException.class);
    }

    @Test
    void readWriteFileRoundTrips(@TempDir Path tmp) {
        Path file = tmp.resolve("inner/.ike/scaffold.lock");
        ScaffoldLockfile lf = ScaffoldLockfile.empty()
                .withEntry("mvnw",
                        LockfileEntry.toolOwned("sha256:abc"));

        ScaffoldLockfileIo.write(lf, file);
        ScaffoldLockfile loaded = ScaffoldLockfileIo.read(file);

        assertThat(loaded).isEqualTo(lf);
    }
}

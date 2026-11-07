package network.ike.plugin.adoc;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a manifest is read from text and from a zip, derived from a reactor member's tree, and
 * written back.
 */
class AdocManifestTest {

    @TempDir
    Path temp;

    @Test
    void theManifestNamesItsCoordinatesAndPackages() {
        AdocManifest manifest = AdocManifest.parse(AdocFixtures.manifest("network.ike.lab:unlv-topics",
                Map.of("network.ike.lab/aip", "topics", "network.ike.lab/unlv", "library")), "test");

        assertThat(manifest.ga()).isEqualTo("network.ike.lab:unlv-topics");
        assertThat(manifest.packages()).containsExactlyInAnyOrder(
                new AdocPackage("network.ike.lab/aip", "topics"),
                new AdocPackage("network.ike.lab/unlv", "library"));
        assertThat(manifest.registry()).isEqualTo("META-INF/ike/network.ike.lab/unlv-topics/topic-registry.yaml");
    }

    @Test
    void aManifestWithoutCoordinatesOrPackagesIsRefused() {
        assertThatThrownBy(() -> AdocManifest.parse("packages: []\n", "x"))
                .isInstanceOf(MojoException.class).hasMessageContaining("no coordinates");
        assertThatThrownBy(() -> AdocManifest.parse("coordinates: a:b\n", "x"))
                .isInstanceOf(MojoException.class).hasMessageContaining("lists no packages");
    }

    @Test
    void theLongestPackageOwnsAPath() {
        AdocManifest manifest = AdocManifest.parse(AdocFixtures.manifest("network.ike.kompendium.anf:topics",
                Map.of("network.ike.kompendium.anf", "topics", "network.ike.kompendium.anf/images", "library")), "x");

        assertThat(manifest.packageOwning("network.ike.kompendium.anf/determine-topic.adoc"))
                .map(AdocPackage::name).contains("network.ike.kompendium.anf");
        assertThat(manifest.packageOwning("network.ike.kompendium.anf/images/x.svg"))
                .map(AdocPackage::name).contains("network.ike.kompendium.anf/images");
        assertThat(manifest.packageOwning("network.ike.lab/dev/x.adoc")).isEmpty();
    }

    @Test
    void aZipWithoutAManifestIsALegacyLibrary() throws Exception {
        AdocCoordinates coordinates = new AdocCoordinates("network.ike.lab", "topics", "12");
        Path legacy = AdocFixtures.zip(temp.resolve("legacy.zip"),
                Map.of("topics/dev/x.adoc", AdocFixtures.topic("dev-x", "X")));
        Path packaged = AdocFixtures.libraryZip(temp.resolve("packaged.zip"), coordinates,
                Map.of("network.ike.lab/dev", "topics"),
                Map.of("network.ike.lab/dev/x.adoc", AdocFixtures.topic("dev-x", "X")));

        assertThat(AdocManifest.readFromZip(legacy, coordinates)).isEmpty();
        assertThat(AdocManifest.readFromZip(packaged, coordinates)).map(AdocManifest::ga)
                .contains("network.ike.lab:topics");
    }

    @Test
    void aReactorMembersPackagesAreDerivedFromItsTree() throws Exception {
        Path root = temp.resolve("topics/src/docs/asciidoc");
        AdocFixtures.write(root.resolve("network.ike.lab/dev/gitflow-overview.adoc"),
                AdocFixtures.topic("dev-gitflow-overview", "Gitflow"));
        AdocFixtures.write(root.resolve("network.ike.lab/dev/images/flow.svg"), "<svg/>");
        AdocFixtures.write(root.resolve("network.ike.lab/lab/index.adoc"), "= Preview\n");
        AdocFixtures.write(root.resolve("network.ike.kompendium.anf/determine-topic.adoc"),
                AdocFixtures.topic("anf-determine-topic", "Determine"));
        AdocFixtures.write(root.resolve("topics/old/legacy.adoc"), "= Legacy\n");
        AdocFixtures.write(root.resolve(".asciidoctorconfig"), ":adoc: x\n");

        AdocManifest manifest = AdocManifest.fromSourceTree(
                new AdocCoordinates("network.ike.lab", "topics", "1-SNAPSHOT"), root);

        assertThat(manifest.packages()).containsExactly(
                new AdocPackage("network.ike.kompendium.anf", "topics"),
                new AdocPackage("network.ike.lab/dev", "topics"),
                new AdocPackage("network.ike.lab/lab", "library"));
        assertThat(AdocManifest.parse(manifest.toYaml(), "round trip")).isEqualTo(manifest);
    }

    @Test
    void aLegacyTreeYieldsNoPackages() throws Exception {
        Path root = temp.resolve("src/docs/asciidoc");
        AdocFixtures.write(root.resolve("topics/dev/x.adoc"), AdocFixtures.topic("dev-x", "X"));
        AdocFixtures.write(root.resolve("index.adoc"), "= Preview\n");

        assertThat(AdocManifest.fromSourceTree(new AdocCoordinates("g", "a", "1"), root).packages()).isEmpty();
    }
}

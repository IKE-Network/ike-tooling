package network.ike.plugin.adoc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** How a library that ships a package is found in the local repository by the group prefix of a path. */
class AdocCatalogTest {

    @TempDir
    Path repo;

    @Test
    void theNewestZipWhoseManifestShipsThePackageIsFoundUnderTheGroupOrAPrefixOfIt() throws Exception {
        AdocFixtures.installed(repo, new AdocCoordinates("network.ike.lab", "topics", "9"),
                Map.of("network.ike.lab/dev", "topics"), Map.of("network.ike.lab/dev/x.adoc", "= X\n"));
        AdocFixtures.installed(repo, new AdocCoordinates("network.ike.lab", "topics", "12"),
                Map.of("network.ike.lab/dev", "topics"), Map.of("network.ike.lab/dev/x.adoc", "= X\n"));
        AdocFixtures.installed(repo, new AdocCoordinates("network.ike", "shared-topics", "4"),
                Map.of("network.ike.kompendium.anf", "topics"), Map.of("network.ike.kompendium.anf/d.adoc", "= D\n"));
        AdocFixtures.zip(repo.resolve("network/ike/lab/topics/12/topics-12.jar"), Map.of("a.class", ""));

        assertThat(AdocCatalog.inLocalRepository(repo, "network.ike.lab/dev/x.adoc"))
                .map(h -> h.coordinates().gav()).contains("network.ike.lab:topics:zip:adoc:12");
        assertThat(AdocCatalog.inLocalRepository(repo, "network.ike.kompendium.anf/d.adoc"))
                .map(h -> h.coordinates().gav()).contains("network.ike:shared-topics:zip:adoc:4");
        assertThat(AdocCatalog.inLocalRepository(repo, "network.ike.lab/ext/x.adoc")).isEmpty();
        assertThat(AdocCatalog.inLocalRepository(repo.resolve("absent"), "network.ike.lab/dev/x.adoc")).isEmpty();
    }
}

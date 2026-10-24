package network.ike.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an assembled store says about itself: the Maven descriptor at maven-archiver's path,
 * read the standard way, and the manifest's {@code Ike-*} attributes, read with the JDK alone.
 */
class StoreDescriptorTest {

    @TempDir
    Path temp;

    @Test
    void descriptorIsReadTheStandardWay() throws IOException {
        Path store = Files.createDirectories(temp.resolve("solor-kb"));
        Path pom = Files.writeString(temp.resolve("pom.xml"), "<project/>\n");

        StoreDescriptor.write(store, "network.ike.data", "solor-kb", "1-SNAPSHOT", Optional.of(pom),
                "ike-maven-plugin 300", Map.of());

        Path maven = store.resolve("META-INF/maven/network.ike.data/solor-kb");
        Properties descriptor = new Properties();
        try (InputStream in = Files.newInputStream(maven.resolve("pom.properties"))) {
            descriptor.load(in);
        }
        assertThat(descriptor).containsOnly(Map.entry("groupId", "network.ike.data"),
                Map.entry("artifactId", "solor-kb"), Map.entry("version", "1-SNAPSHOT"));
        assertThat(maven.resolve("pom.xml")).hasContent("<project/>");
        assertThat(Files.readString(maven.resolve("pom.properties")))
                .as("no timestamp, so a rebuild writes the same bytes")
                .doesNotContainPattern("\\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void manifestCarriesTheAttributesAndKeepsWhatWasThere() throws IOException {
        Path store = Files.createDirectories(temp.resolve("kb"));
        Files.createDirectories(store.resolve("META-INF"));
        Files.writeString(store.resolve("META-INF/MANIFEST.MF"),
                "Manifest-Version: 1.0\nIke-Store-Layout: 32-bit\nIke-Last-Opened-By: Komet 26.1016.346\n\n");

        StoreDescriptor.write(store, "network.ike.data", "solor-kb", "1-SNAPSHOT", Optional.empty(),
                "ike-maven-plugin 300", Map.of(StoreDescriptor.LAUNCH_INTERFACE_VERSION, StoreDescriptor.LAUNCH_INTERFACE,
                        StoreDescriptor.REASONER, "ElkSnomedReasonerService", "Ike-Store-Layout", "64-bit"));

        Manifest manifest = new Manifest();
        try (InputStream in = Files.newInputStream(store.resolve("META-INF/MANIFEST.MF"))) {
            manifest.read(in);
        }
        Attributes main = manifest.getMainAttributes();
        assertThat(main.getValue("Created-By")).isEqualTo("ike-maven-plugin 300");
        assertThat(main.getValue("Ike-Launch-Interface-Version")).isEqualTo("1");
        assertThat(main.getValue("Ike-Reasoner")).isEqualTo("ElkSnomedReasonerService");
        assertThat(main.getValue("Ike-Store-Layout")).as("written again, so replaced").isEqualTo("64-bit");
        assertThat(main.getValue("Ike-Last-Opened-By")).as("not written again, so kept")
                .isEqualTo("Komet 26.1016.346");
        assertThat(store.resolve("META-INF/maven/network.ike.data/solor-kb/pom.xml"))
                .as("no POM to copy").doesNotExist();
    }
}

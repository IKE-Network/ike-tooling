package network.ike.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every store ike:kb-assemble writes carries a complete level-1 knowledge-base descriptor and the
 * icon it names: the project's own, or a monogram drawn for it.
 */
class KnowledgeBaseDescriptorTest {

    private static final KnowledgeBaseDescriptor.Fields PIZZA = new KnowledgeBaseDescriptor.Fields("network.ike.data",
            "pizza-kb", "20250627.1-SNAPSHOT", "Pizza Knowledge Base", 3, Instant.parse("2026-10-24T13:58:56.219966Z"),
            KnowledgeBaseDescriptor.Access.RESTRICTED);

    @TempDir
    Path temp;

    @Test
    void aStoreCarriesACompleteLevelOneDescriptorAndItsIcon() throws IOException {
        Path store = Files.createDirectories(temp.resolve("kb/rocks")).getParent();
        Path kb = Files.createDirectories(temp.resolve("pizza-kb/src/main/kb"));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1};
        byte[] png2x = {(byte) 0x89, 'P', 'N', 'G', 2};
        Files.write(kb.resolve("icon.png"), png);
        Files.write(kb.resolve("icon@2x.png"), png2x);

        Path icon = KnowledgeBaseDescriptor.icon(temp.resolve("pizza-kb")).orElseThrow();
        Path written = KnowledgeBaseDescriptor.write(store, PIZZA, icon);

        assertThat(written).isEqualTo(store.resolve("META-INF/ike/knowledge-base.properties"));
        assertThat(read(written)).containsOnly(
                Map.entry("descriptorLevel", "1"),
                Map.entry("groupId", "network.ike.data"),
                Map.entry("artifactId", "pizza-kb"),
                Map.entry("version", "20250627.1-SNAPSHOT"),
                Map.entry("displayName", "Pizza Knowledge Base"),
                Map.entry("layout", "rocks"),
                Map.entry("entityFormat", "3"),
                Map.entry("assembledAt", "2026-10-24T13:58:56.219966Z"),
                Map.entry("access", "restricted"),
                Map.entry("icon", "META-INF/ike/icon.png"));
        assertThat(store.resolve("META-INF/ike/icon.png")).hasBinaryContent(png);
        assertThat(store.resolve("META-INF/ike/icon@2x.png")).hasBinaryContent(png2x);
        assertThat(Files.readString(written)).as("no timestamp, so a rebuild writes the same bytes")
                .doesNotContainPattern("\\d{2}:\\d{2}:\\d{2} ");
    }

    @Test
    void theMemoryTheAssemblyMeasuredIsCarriedInTheDescriptor() throws IOException {
        Path store = Files.createDirectories(temp.resolve("kb/rocks")).getParent();
        Map<String, Long> memory = KnowledgeBaseDescriptor.memory(Map.of(
                "Ike-Stated-Axiom-Bytes", "168032664",
                "Ike-Loaded-Heap-Bytes", "1469861888",
                "Ike-Reasoner-Heap-Bytes", "not a size",
                "Ike-Open-Controller", "Open Rocks KB"));
        assertThat(memory).containsExactly(Map.entry("statedAxiomBytes", 168032664L),
                Map.entry("loadedHeapBytes", 1469861888L));

        KnowledgeBaseDescriptor.Fields measured = new KnowledgeBaseDescriptor.Fields(PIZZA.groupId(), PIZZA.artifactId(),
                PIZZA.version(), PIZZA.displayName(), PIZZA.entityFormat(), PIZZA.assembledAt(), PIZZA.access(), memory);
        Properties descriptor = read(KnowledgeBaseDescriptor.write(store, measured, monogram()));

        assertThat(descriptor.getProperty("statedAxiomBytes")).isEqualTo("168032664");
        assertThat(descriptor.getProperty("loadedHeapBytes")).isEqualTo("1469861888");
        assertThat(descriptor.getProperty("reasonerHeapBytes")).as("left out, not a size").isNull();
    }

    @Test
    void aReleaseNamesItsResolvedVersionAndEachLayoutIsReadFromTheStore() throws IOException {
        Path spined = Files.createDirectories(temp.resolve("sa"));
        Files.writeString(spined.resolve("store-format.properties"), "layout=64-bit\nentityFormat=3\n");
        KnowledgeBaseDescriptor.Fields release = new KnowledgeBaseDescriptor.Fields("network.ike.data", "solor-kb", "20250901",
                "SOLOR", 3, Instant.EPOCH, KnowledgeBaseDescriptor.Access.PUBLIC);

        Properties descriptor = read(KnowledgeBaseDescriptor.write(spined, release, monogram()));

        assertThat(descriptor.getProperty("layout")).isEqualTo("spined-64");
        assertThat(descriptor.getProperty("resolvedVersion")).isEqualTo("20250901");
        assertThat(descriptor.getProperty("access")).isEqualTo("public");
        assertThat(KnowledgeBaseDescriptor.recordedEntityFormat(spined)).contains(3);
        Files.createDirectories(temp.resolve("sa32/nidToByteArrayMap"));
        assertThat(KnowledgeBaseDescriptor.layout(temp.resolve("sa32"))).isEqualTo("spined-32");
        assertThatThrownBy(() -> KnowledgeBaseDescriptor.layout(Files.createDirectories(temp.resolve("empty"))))
                .isInstanceOf(IOException.class).hasMessageContaining("rocks, spined-32, spined-64");
    }

    @Test
    void aProjectWithoutAnIconGetsAMonogram() throws IOException {
        assertThat(KnowledgeBaseDescriptor.icon(Files.createDirectories(temp.resolve("solor-gudid-kb")))).isEmpty();

        Path icon = KnowledgeBaseMonogram.write(temp.resolve("drawn"), "SOLOR GUDID", "solor-gudid-kb");

        BufferedImage image = ImageIO.read(icon.toFile());
        assertThat(image.getWidth()).isEqualTo(64);
        assertThat(ImageIO.read(temp.resolve("drawn/icon@2x.png").toFile()).getWidth()).isEqualTo(128);
        assertThat(new Color(image.getRGB(10, 32), true)).as("the square, in the artifactId's colour")
                .isEqualTo(KnowledgeBaseMonogram.color("solor-gudid-kb"));
        assertThat(new Color(image.getRGB(0, 0), true).getAlpha()).as("rounded corners").isZero();
    }

    @Test
    void theMonogramIsStableForAName() {
        assertThat(KnowledgeBaseMonogram.initials("Pizza Knowledge Base")).isEqualTo("Pi");
        assertThat(KnowledgeBaseMonogram.initials("solor-gudid-kb")).isEqualTo("SG");
        assertThat(KnowledgeBaseMonogram.initials("SOLOR GUDID NUVA")).isEqualTo("SGN");
        assertThat(KnowledgeBaseMonogram.initials("DeX")).as("split where case changes").isEqualTo("DX");
        assertThat(KnowledgeBaseMonogram.initials("--")).isEqualTo("?");
        // The hue is CRC-32 of the lower-case artifactId, modulo 360: fixed, not a per-run hash.
        float[] hsb = Color.RGBtoHSB(KnowledgeBaseMonogram.color("pizza-kb").getRed(),
                KnowledgeBaseMonogram.color("pizza-kb").getGreen(), KnowledgeBaseMonogram.color("pizza-kb").getBlue(), null);
        assertThat(hsb[0] * 360).isCloseTo(19, org.assertj.core.data.Offset.offset(1f));
        assertThat(KnowledgeBaseMonogram.color("Pizza-KB")).isEqualTo(KnowledgeBaseMonogram.color("pizza-kb"))
                .isNotEqualTo(KnowledgeBaseMonogram.color("solor-kb"));
        assertThat(KnowledgeBaseMonogram.draw("Pizza", "pizza-kb", 64).getRGB(32, 10))
                .as("the same pixels on every draw").isEqualTo(KnowledgeBaseMonogram.draw("Pizza", "pizza-kb", 64).getRGB(32, 10));
    }

    private Path monogram() throws IOException {
        return KnowledgeBaseMonogram.write(temp.resolve("drawn-" + System.nanoTime()), "SOLOR", "solor-kb");
    }

    private static Properties read(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }
}

package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PomModelAdapterTest {

    private final ModelAdapter adapter = new PomModelAdapter();
    private final Path dest = Paths.get("/tmp/pom.xml");

    private static ManifestEntry entry(
            List<Map<String, String>> plugins) {
        Map<String, Object> ensure = new LinkedHashMap<>();
        ensure.put("pluginManagement", plugins);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("ensure", ensure);
        return new ManifestEntry(
                "pom.xml", ScaffoldScope.PROJECT,
                ScaffoldTier.MODEL_MANAGED, null,
                PomModelAdapter.MODEL_NAME, extras);
    }

    private static Map<String, String> plugin(
            String g, String a, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("groupId", g);
        m.put("artifactId", a);
        if (v != null) m.put("version", v);
        return m;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void missingPomSkipsWithReason() {
        ModelPlanResult r = adapter.plan(
                entry(List.of(plugin("g", "a", "1"))),
                dest, null, null, "7");
        assertThat(r.action())
                .isInstanceOfSatisfying(TierAction.Skip.class,
                        s -> assertThat(s.reason())
                                .contains("does not exist"));
        assertThat(r.managedElements()).isEmpty();
    }

    @Test
    void existingPluginBecomesUpToDate() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>x</groupId>
                    <artifactId>y</artifactId>
                    <version>1</version>
                    <build>
                        <pluginManagement>
                            <plugins>
                                <plugin>
                                    <groupId>network.ike.tooling</groupId>
                                    <artifactId>ike-maven-plugin</artifactId>
                                    <version>127</version>
                                </plugin>
                            </plugins>
                        </pluginManagement>
                    </build>
                </project>
                """;
        ModelPlanResult r = adapter.plan(
                entry(List.of(plugin(
                        "network.ike.tooling",
                        "ike-maven-plugin", "127"))),
                dest, bytes(pom), null, "7");
        assertThat(r.action())
                .isInstanceOf(TierAction.UpToDate.class);
        assertThat(r.managedElements()).hasSize(1);
    }

    @Test
    void missingPluginAppendedToPluginManagement() {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>x</groupId>
                    <artifactId>y</artifactId>
                    <version>1</version>
                    <build>
                        <pluginManagement>
                            <plugins>
                                <plugin>
                                    <groupId>other</groupId>
                                    <artifactId>other-plugin</artifactId>
                                    <version>1</version>
                                </plugin>
                            </plugins>
                        </pluginManagement>
                    </build>
                </project>
                """;
        ModelPlanResult r = adapter.plan(
                entry(List.of(plugin(
                        "network.ike.tooling",
                        "ike-maven-plugin", "127"))),
                dest, bytes(pom), null, "7");
        TierAction.Write w = (TierAction.Write) r.action();
        String out = new String(
                w.newContent(), StandardCharsets.UTF_8);
        // Preserves existing plugin.
        assertThat(out).contains("other-plugin");
        // Adds ike one.
        assertThat(out)
                .contains("<artifactId>ike-maven-plugin</artifactId>");
        assertThat(out).contains("<version>127</version>");
    }

    @Test
    void reportsModelName() {
        assertThat(adapter.modelName())
                .isEqualTo("pom-openrewrite");
    }
}

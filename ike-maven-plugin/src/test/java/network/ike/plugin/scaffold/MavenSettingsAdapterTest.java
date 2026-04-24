package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MavenSettingsAdapterTest {

    private final ModelAdapter adapter = new MavenSettingsAdapter();
    private final Path dest = Paths.get(
            System.getProperty("user.home"), ".m2", "settings.xml");

    private static ManifestEntry entry(
            Map<String, Object> ensure,
            List<String> neverTouch) {
        Map<String, Object> extras = new LinkedHashMap<>();
        if (ensure != null) extras.put("ensure", ensure);
        if (neverTouch != null) extras.put("never-touch", neverTouch);
        return new ManifestEntry(
                "~/.m2/settings.xml", ScaffoldScope.USER,
                ScaffoldTier.MODEL_MANAGED, null,
                MavenSettingsAdapter.MODEL_NAME, extras);
    }

    private static ManifestEntry defaultEntry() {
        return entry(
                Map.of("pluginGroups", List.of("network.ike.tooling")),
                List.of("servers"));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void missingFileBecomesInstall() {
        ModelPlanResult r = adapter.plan(
                defaultEntry(), dest, null, null, "7");
        TierAction.Write w = (TierAction.Write) r.action();
        assertThat(w.kind())
                .isEqualTo(TierAction.Write.Kind.INSTALL);
        String xml = new String(
                w.newContent(), StandardCharsets.UTF_8);
        assertThat(xml).contains(
                "<pluginGroup>network.ike.tooling</pluginGroup>");
        assertThat(r.managedElements()).hasSize(1);
        assertThat(r.managedElements().get(0).path())
                .contains("network.ike.tooling");
        assertThat(r.managedElements().get(0).standardsVersion())
                .isEqualTo("7");
    }

    @Test
    void existingFileWithoutGroupGetsGroupAppended() {
        String current = """
                <?xml version="1.0" encoding="UTF-8"?>
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                  <servers>
                    <server>
                      <id>keep-me</id>
                    </server>
                  </servers>
                </settings>
                """;
        ModelPlanResult r = adapter.plan(
                defaultEntry(), dest, bytes(current), null, "7");
        TierAction.Write w = (TierAction.Write) r.action();
        assertThat(w.kind())
                .isEqualTo(TierAction.Write.Kind.UPDATE);
        String xml = new String(
                w.newContent(), StandardCharsets.UTF_8);
        // servers preserved
        assertThat(xml).contains("keep-me");
        // plugin group added
        assertThat(xml).contains(
                "<pluginGroup>network.ike.tooling</pluginGroup>");
    }

    @Test
    void existingGroupBecomesUpToDate() {
        String current = """
                <?xml version="1.0" encoding="UTF-8"?>
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                  <pluginGroups>
                    <pluginGroup>network.ike.tooling</pluginGroup>
                  </pluginGroups>
                </settings>
                """;
        ModelPlanResult r = adapter.plan(
                defaultEntry(), dest, bytes(current), null, "7");
        assertThat(r.action())
                .isInstanceOf(TierAction.UpToDate.class);
        assertThat(r.managedElements()).hasSize(1);
    }

    @Test
    void priorStandardsVersionPreservedForPresentElements() {
        String current = """
                <?xml version="1.0" encoding="UTF-8"?>
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                  <pluginGroups>
                    <pluginGroup>network.ike.tooling</pluginGroup>
                  </pluginGroups>
                </settings>
                """;
        LockfileEntry prior = LockfileEntry.modelManaged(List.of(
                new ManagedElement(
                        "/settings/pluginGroups/pluginGroup"
                                + "[text()='network.ike.tooling']",
                        java.time.Instant.parse(
                                "2026-01-01T00:00:00Z"),
                        "3")));
        ModelPlanResult r = adapter.plan(
                defaultEntry(), dest, bytes(current), prior, "7");
        assertThat(r.managedElements().get(0).standardsVersion())
                .isEqualTo("3");
    }

    @Test
    void reportsModelName() {
        assertThat(adapter.modelName())
                .isEqualTo("maven-settings-4");
    }

    @Test
    void malformedEnsureRejected() {
        ManifestEntry bad = entry(
                Map.of("pluginGroups", "not-a-list"), null);
        assertThatThrownBy(() ->
                adapter.plan(bad, dest, null, null, "7"))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("pluginGroups");
    }

    @Test
    void emptyEnsureProducesEmptyManagedElements() {
        ManifestEntry empty = entry(Map.of(), null);
        ModelPlanResult r = adapter.plan(
                empty, dest, null, null, "7");
        assertThat(r.managedElements()).isEmpty();
    }
}

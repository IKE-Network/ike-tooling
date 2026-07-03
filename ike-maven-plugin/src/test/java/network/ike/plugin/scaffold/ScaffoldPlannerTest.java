package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaffoldPlannerTest {

    private final TierHandlers tierHandlers = new TierHandlers();
    private final ModelAdapters modelAdapters = new ModelAdapters();
    private final ScaffoldPlanner planner =
            new ScaffoldPlanner(tierHandlers, modelAdapters);

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ManifestEntry toolOwned(String dest) {
        return new ManifestEntry(
                dest, ScaffoldScope.PROJECT,
                ScaffoldTier.TOOL_OWNED, dest, null, Map.of());
    }

    private static ManifestEntry tracked(String dest) {
        return new ManifestEntry(
                dest, ScaffoldScope.PROJECT,
                ScaffoldTier.TRACKED, dest, null, Map.of());
    }

    private static ManifestEntry userSettings() {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("ensure",
                Map.of("pluginGroups", List.of("network.ike.tooling")));
        return new ManifestEntry(
                "~/.m2/settings.xml", ScaffoldScope.USER,
                ScaffoldTier.MODEL_MANAGED, null,
                "maven-settings-4", extras);
    }

    @Test
    void planIncludesToolOwnedAsInstall(@TempDir Path project)
            throws IOException {
        Files.createDirectories(project);
        ScaffoldManifest manifest = new ScaffoldManifest(
                1, "7", List.of(toolOwned("mvnw")));
        MapTemplateSource templates = MapTemplateSource.ofStrings(
                Map.of("mvnw", "#!/bin/sh\n"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        ScaffoldPlan plan = planner.plan(
                manifest, ScaffoldLockfile.empty(),
                ScaffoldScope.PROJECT, resolver, templates);

        assertThat(plan.manifestStandardsVersion()).isEqualTo("7");
        assertThat(plan.entries()).hasSize(1);
        PlannedEntry pe = plan.entries().get(0);
        assertThat(pe.manifest().dest()).isEqualTo("mvnw");
        assertThat(pe.action())
                .isInstanceOf(TierAction.Write.class);
        TierAction.Write w = (TierAction.Write) pe.action();
        assertThat(w.resolvedDest())
                .isEqualTo(project.resolve("mvnw"));
        assertThat(w.kind())
                .isEqualTo(TierAction.Write.Kind.INSTALL);
    }

    @Test
    void planSkipsEntriesOutsideScope(@TempDir Path project,
                                      @TempDir Path home)
            throws IOException {
        Files.createDirectories(project);
        ScaffoldManifest manifest = new ScaffoldManifest(
                1, "7",
                List.of(toolOwned("mvnw"), userSettings()));
        MapTemplateSource templates = MapTemplateSource.ofStrings(
                Map.of("mvnw", "#!/bin/sh\n"));
        PathResolver resolver = new PathResolver(home, project);

        ScaffoldPlan userPlan = planner.plan(
                manifest, ScaffoldLockfile.empty(),
                ScaffoldScope.USER, resolver, templates);
        assertThat(userPlan.entries()).hasSize(1);
        assertThat(userPlan.entries().get(0).manifest().dest())
                .isEqualTo("~/.m2/settings.xml");

        ScaffoldPlan projectPlan = planner.plan(
                manifest, ScaffoldLockfile.empty(),
                ScaffoldScope.PROJECT, resolver, templates);
        assertThat(projectPlan.entries()).hasSize(1);
        assertThat(projectPlan.entries().get(0).manifest().dest())
                .isEqualTo("mvnw");
    }

    @Test
    void plansUpToDateWhenFileMatchesTemplate(@TempDir Path project)
            throws IOException {
        byte[] template = bytes("-T1C\n");
        Files.createDirectories(project.resolve(".mvn"));
        Files.write(project.resolve(".mvn/maven.config"), template);

        ScaffoldManifest manifest = new ScaffoldManifest(
                1, "7", List.of(tracked(".mvn/maven.config")));
        MapTemplateSource templates = MapTemplateSource.ofStrings(
                Map.of(".mvn/maven.config", "-T1C\n"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        String sha = Sha256.of(template);
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry(".mvn/maven.config",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED, sha, sha));

        ScaffoldPlan plan = planner.plan(
                manifest, lockfile,
                ScaffoldScope.PROJECT, resolver, templates);
        assertThat(plan.entries()).hasSize(1);
        assertThat(plan.entries().get(0).action())
                .isInstanceOf(TierAction.UpToDate.class);
    }

    @Test
    void plannerDispatchesModelManagedToAdapter(@TempDir Path home) {
        ScaffoldManifest manifest = new ScaffoldManifest(
                1, "7", List.of(userSettings()));
        MapTemplateSource templates = MapTemplateSource.ofStrings(
                Map.of());
        PathResolver resolver = new PathResolver(home, null);

        ScaffoldPlan plan = planner.plan(
                manifest, ScaffoldLockfile.empty(),
                ScaffoldScope.USER, resolver, templates);
        assertThat(plan.entries()).hasSize(1);
        PlannedEntry pe = plan.entries().get(0);
        assertThat(pe.action())
                .isInstanceOf(TierAction.Write.class);
        assertThat(pe.managedElements()).hasSize(1);
        assertThat(pe.managedElements().get(0).standardsVersion())
                .isEqualTo("7");
    }

    @Test
    void missingFileStaysNullCurrent(@TempDir Path project)
            throws IOException {
        Files.createDirectories(project);
        ScaffoldManifest manifest = new ScaffoldManifest(
                1, "7", List.of(tracked(".mvn/maven.config")));
        MapTemplateSource templates = MapTemplateSource.ofStrings(
                Map.of(".mvn/maven.config", "-T1C\n"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        ScaffoldPlan plan = planner.plan(
                manifest, ScaffoldLockfile.empty(),
                ScaffoldScope.PROJECT, resolver, templates);
        assertThat(plan.entries()).hasSize(1);
        TierAction.Write w = (TierAction.Write) plan.entries()
                .get(0).action();
        assertThat(w.kind())
                .isEqualTo(TierAction.Write.Kind.INSTALL);
    }

    @Test
    void emptyManifestProducesEmptyPlan() {
        ScaffoldManifest manifest = new ScaffoldManifest(
                1, "7", List.of());
        MapTemplateSource templates = MapTemplateSource.ofStrings(
                Map.of());
        PathResolver resolver = new PathResolver(
                Path.of("/home"), Path.of("/work"));

        ScaffoldPlan plan = planner.plan(
                manifest, ScaffoldLockfile.empty(),
                ScaffoldScope.PROJECT, resolver, templates);
        assertThat(plan.entries()).isEmpty();
        assertThat(plan.hasWrites()).isFalse();
        assertThat(plan.hasSkips()).isFalse();
    }

    // ── create-source resolution (#825) ───────────────────────────

    private static ManifestEntry gitignoreWithCreateSource() {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("block-begin", "# BEGIN ike-managed");
        extras.put("block-end", "# END ike-managed");
        extras.put("create-source",
                "tracked/gitignore.project-template");
        return new ManifestEntry(
                ".gitignore", ScaffoldScope.PROJECT,
                ScaffoldTier.TRACKED_BLOCK,
                "tracked/gitignore.ike-block", null, extras);
    }

    @Test
    void createSourceSeedsMissingTrackedBlockDest(
            @TempDir Path project) throws IOException {
        Files.createDirectories(project);
        ScaffoldManifest manifest = new ScaffoldManifest(
                1, "7", List.of(gitignoreWithCreateSource()));
        MapTemplateSource templates = MapTemplateSource.ofStrings(
                Map.of("tracked/gitignore.ike-block",
                                ".ike/vcs-state\n",
                        "tracked/gitignore.project-template",
                                "# Maven\ntarget/\n"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        ScaffoldPlan plan = planner.plan(
                manifest, ScaffoldLockfile.empty(),
                ScaffoldScope.PROJECT, resolver, templates);

        TierAction.Write w = (TierAction.Write) plan.entries()
                .get(0).action();
        assertThat(w.kind())
                .isEqualTo(TierAction.Write.Kind.INSTALL);
        String produced = new String(
                w.newContent(), StandardCharsets.UTF_8);
        assertThat(produced).isEqualTo(
                "# Maven\ntarget/\n"
                        + "# BEGIN ike-managed\n"
                        + ".ike/vcs-state\n"
                        + "# END ike-managed\n");
        assertThat(w.reason()).contains("seed canonical template");
    }

    @Test
    void createSourceLeavesExistingDestUnseeded(
            @TempDir Path project) throws IOException {
        Files.createDirectories(project);
        Files.write(project.resolve(".gitignore"), bytes("mine/\n"));
        ScaffoldManifest manifest = new ScaffoldManifest(
                1, "7", List.of(gitignoreWithCreateSource()));
        MapTemplateSource templates = MapTemplateSource.ofStrings(
                Map.of("tracked/gitignore.ike-block",
                                ".ike/vcs-state\n",
                        "tracked/gitignore.project-template",
                                "# Maven\ntarget/\n"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        ScaffoldPlan plan = planner.plan(
                manifest, ScaffoldLockfile.empty(),
                ScaffoldScope.PROJECT, resolver, templates);

        TierAction.Write w = (TierAction.Write) plan.entries()
                .get(0).action();
        String produced = new String(
                w.newContent(), StandardCharsets.UTF_8);
        assertThat(produced).isEqualTo(
                "mine/\n"
                        + "# BEGIN ike-managed\n"
                        + ".ike/vcs-state\n"
                        + "# END ike-managed\n");
    }

    @Test
    void missingCreateSourceTemplateThrows(@TempDir Path project)
            throws IOException {
        Files.createDirectories(project);
        ScaffoldManifest manifest = new ScaffoldManifest(
                1, "7", List.of(gitignoreWithCreateSource()));
        MapTemplateSource templates = MapTemplateSource.ofStrings(
                Map.of("tracked/gitignore.ike-block",
                        ".ike/vcs-state\n"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        assertThatThrownBy(() -> planner.plan(
                manifest, ScaffoldLockfile.empty(),
                ScaffoldScope.PROJECT, resolver, templates))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining(
                        "tracked/gitignore.project-template");
    }

    @Test
    void priorLockfileDirectsTrackedToSkipWhenDiverged(
            @TempDir Path project) throws IOException {
        Files.createDirectories(project.resolve(".mvn"));
        Files.write(project.resolve(".mvn/maven.config"),
                bytes("user edit\n"));

        ScaffoldManifest manifest = new ScaffoldManifest(
                1, "7", List.of(tracked(".mvn/maven.config")));
        MapTemplateSource templates = MapTemplateSource.ofStrings(
                Map.of(".mvn/maven.config", "-T1C\n"));
        PathResolver resolver = new PathResolver(
                Path.of("/home/nobody"), project);

        String priorSha = Sha256.of(bytes("-T1C\n"));
        ScaffoldLockfile lockfile = ScaffoldLockfile.empty()
                .withEntry(".mvn/maven.config",
                        LockfileEntry.tracked(
                                ScaffoldTier.TRACKED,
                                priorSha, priorSha));

        ScaffoldPlan plan = planner.plan(
                manifest, lockfile,
                ScaffoldScope.PROJECT, resolver, templates);
        assertThat(plan.entries().get(0).action())
                .isInstanceOf(TierAction.Skip.class);
    }

}

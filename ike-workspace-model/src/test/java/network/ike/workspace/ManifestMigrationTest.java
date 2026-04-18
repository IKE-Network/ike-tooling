package network.ike.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ManifestReader#migrate(String)} and
 * {@link ManifestReader#migrateLegacySchemaIfNeeded(Path, java.util.function.Consumer)}.
 *
 * <p>Covers the 4 individual rewrites (components key, component-types
 * block, groups block, depends-on dash form) in isolation and in
 * combination, plus the no-op case on an already-migrated file and
 * the round-trip through file I/O via {@link #migrateLegacySchemaIfNeeded}.
 */
class ManifestMigrationTest {

    // ── Pure migrate() tests ─────────────────────────────────────

    @Test
    void noOpOnNewSchema() {
        String yaml = """
                schema-version: "1.0"
                subprojects:
                  foo:
                    type: software
                    depends-on:
                      - subproject: bar
                        relationship: build
                """;
        assertThat(ManifestReader.migrate(yaml)).isEqualTo(yaml);
    }

    @Test
    void renamesComponentsKey() {
        String yaml = """
                schema-version: "1.0"
                components:
                  foo:
                    type: software
                """;
        String migrated = ManifestReader.migrate(yaml);
        assertThat(migrated).doesNotContain("components:");
        assertThat(migrated).contains("subprojects:");
        assertThat(migrated).contains("foo:");
    }

    @Test
    void stripsComponentTypesBlock() {
        String yaml = """
                schema-version: "1.0"
                component-types:
                  software:
                    description: "Java"
                    build-command: "mvn install"
                    checkpoint-mechanism: git-tag
                  infrastructure:
                    description: "tooling"
                    build-command: "mvn install"
                subprojects:
                  foo:
                    type: software
                """;
        String migrated = ManifestReader.migrate(yaml);
        assertThat(migrated).doesNotContain("component-types:");
        assertThat(migrated).doesNotContain("build-command:");
        assertThat(migrated).doesNotContain("checkpoint-mechanism:");
        assertThat(migrated).contains("subprojects:");
        assertThat(migrated).contains("  foo:");
    }

    @Test
    void stripsGroupsBlock() {
        String yaml = """
                schema-version: "1.0"
                subprojects:
                  foo:
                    type: software
                groups:
                  all: [foo]
                  studio: [foo]
                """;
        String migrated = ManifestReader.migrate(yaml);
        assertThat(migrated).doesNotContain("groups:");
        assertThat(migrated).doesNotContain("all: [foo]");
        assertThat(migrated).doesNotContain("studio:");
        assertThat(migrated).contains("subprojects:");
        assertThat(migrated).contains("  foo:");
    }

    @Test
    void renamesDependsOnComponent() {
        String yaml = """
                schema-version: "1.0"
                subprojects:
                  foo:
                    type: software
                    depends-on:
                      - component: bar
                        relationship: build
                      - component: baz
                        relationship: content
                """;
        String migrated = ManifestReader.migrate(yaml);
        assertThat(migrated).doesNotContain("- component:");
        assertThat(migrated).contains("- subproject: bar");
        assertThat(migrated).contains("- subproject: baz");
    }

    @Test
    void allFourMigrationsInOneYaml() {
        String yaml = """
                schema-version: "1.0"
                defaults:
                  branch: main
                component-types:
                  software:
                    description: "Java libs"
                    build-command: "mvn install"
                    checkpoint-mechanism: git-tag
                components:
                  foo:
                    type: software
                    depends-on:
                      - component: bar
                        relationship: build
                  bar:
                    type: software
                groups:
                  all: [foo, bar]
                """;
        String migrated = ManifestReader.migrate(yaml);

        // All legacy markers gone
        assertThat(migrated).doesNotContain("component-types:");
        assertThat(migrated).doesNotContain("groups:");
        assertThat(migrated).doesNotContain("- component:");
        assertThat(migrated.lines().anyMatch(l -> l.equals("components:")))
                .as("top-level components: should be gone").isFalse();

        // Expected new-schema content present
        assertThat(migrated).contains("subprojects:");
        assertThat(migrated).contains("  foo:");
        assertThat(migrated).contains("  bar:");
        assertThat(migrated).contains("- subproject: bar");
        assertThat(migrated).contains("branch: main");

        // Still parses as a valid manifest with the renamed top-level key
        Manifest m = ManifestReader.read(new java.io.StringReader(migrated));
        assertThat(m.subprojects()).containsKeys("foo", "bar");
    }

    @Test
    void migrationIsIdempotent() {
        String yaml = """
                schema-version: "1.0"
                component-types:
                  software:
                    description: "x"
                components:
                  foo:
                    type: software
                    depends-on:
                      - component: bar
                        relationship: build
                  bar:
                    type: software
                groups:
                  all: [foo, bar]
                """;
        String once = ManifestReader.migrate(yaml);
        String twice = ManifestReader.migrate(once);
        assertThat(twice).isEqualTo(once);
    }

    // ── migrateLegacySchemaIfNeeded() file-I/O tests ─────────────

    @Test
    void migrateFile_writesAndLogsWhenLegacyPresent(@TempDir Path tmp)
            throws IOException {
        Path yamlPath = tmp.resolve("workspace.yaml");
        Files.writeString(yamlPath, """
                schema-version: "1.0"
                components:
                  foo:
                    type: software
                """, StandardCharsets.UTF_8);

        List<String> logs = new ArrayList<>();
        boolean migrated = ManifestReader.migrateLegacySchemaIfNeeded(
                yamlPath, logs::add);

        assertThat(migrated).isTrue();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0)).contains("Migrated workspace.yaml");
        assertThat(Files.readString(yamlPath)).contains("subprojects:");
        assertThat(Files.readString(yamlPath)).doesNotContain("components:");
    }

    @Test
    void migrateFile_noOpWhenAlreadyMigrated(@TempDir Path tmp)
            throws IOException {
        Path yamlPath = tmp.resolve("workspace.yaml");
        String before = """
                schema-version: "1.0"
                subprojects:
                  foo:
                    type: software
                """;
        Files.writeString(yamlPath, before, StandardCharsets.UTF_8);

        List<String> logs = new ArrayList<>();
        boolean migrated = ManifestReader.migrateLegacySchemaIfNeeded(
                yamlPath, logs::add);

        assertThat(migrated).isFalse();
        assertThat(logs).isEmpty();
        assertThat(Files.readString(yamlPath)).isEqualTo(before);
    }

    @Test
    void migrateFile_tolerateNullLog(@TempDir Path tmp) throws IOException {
        Path yamlPath = tmp.resolve("workspace.yaml");
        Files.writeString(yamlPath, """
                schema-version: "1.0"
                components:
                  foo:
                    type: software
                """, StandardCharsets.UTF_8);

        // Should not throw even with a null log consumer.
        boolean migrated = ManifestReader.migrateLegacySchemaIfNeeded(
                yamlPath, null);
        assertThat(migrated).isTrue();
    }
}

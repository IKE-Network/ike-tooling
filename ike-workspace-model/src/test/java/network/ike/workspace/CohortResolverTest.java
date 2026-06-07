package network.ike.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CohortResolver} (ike-issues#610): a single repo is a
 * cohort of one; a {@code workspace.yaml} resolves to its subprojects in
 * topological order, excluding the aggregator root.
 */
class CohortResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void singleRepo_whenNoManifestAbove() {
        Cohort cohort = CohortResolver.resolve(tempDir);

        assertThat(cohort.decentralized()).isFalse();
        assertThat(cohort.size()).isEqualTo(1);
        assertThat(cohort.members()).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo(tempDir.getFileName().toString());
            assertThat(m.directory()).isEqualTo(tempDir.toAbsolutePath().normalize());
        });
    }

    @Test
    void workspace_membersInTopologicalOrder_withoutAggregatorRoot() throws Exception {
        // lib-b is declared first but depends on lib-a, so a topological
        // order must put lib-a first — the cohort's defining property.
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  lib-b:
                    repo: https://example.com/lib-b.git
                    branch: main
                    version: "2.0.0-SNAPSHOT"
                    depends-on:
                      - subproject: lib-a
                        relationship: build
                  lib-a:
                    repo: https://example.com/lib-a.git
                    branch: main
                    version: "1.0.0-SNAPSHOT"
                """);

        Cohort cohort = CohortResolver.resolve(tempDir);

        assertThat(cohort.decentralized()).isFalse();
        assertThat(cohort.members()).extracting(Cohort.Member::name)
                .containsExactly("lib-a", "lib-b");
        // The aggregator root is not a release member.
        assertThat(cohort.members()).extracting(Cohort.Member::name)
                .doesNotContain(tempDir.getFileName().toString());
        assertThat(cohort.members().getFirst().directory())
                .isEqualTo(tempDir.toAbsolutePath().normalize().resolve("lib-a"));
    }
}

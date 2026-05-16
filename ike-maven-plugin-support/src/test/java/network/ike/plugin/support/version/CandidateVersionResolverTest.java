package network.ike.plugin.support.version;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateVersionResolverTest {

    /** Trivial in-memory resolver for testing the default helper. */
    private static CandidateVersionResolver fake(
            Map<String, List<String>> versions) {
        return (g, a, current) -> versions.getOrDefault(
                g + ":" + a, List.of());
    }

    @Test
    void highestCandidateReturnsStrictlyNewerVersion() {
        CandidateVersionResolver resolver = fake(Map.of(
                "network.ike.platform:ike-bom",
                List.of("1", "2", "3", "4")));

        assertThat(resolver.resolveHighestCandidate(
                "network.ike.platform", "ike-bom", "3"))
                .isEqualTo("4");
    }

    @Test
    void highestCandidateReturnsNullWhenAtLatest() {
        CandidateVersionResolver resolver = fake(Map.of(
                "network.ike.platform:ike-bom",
                List.of("1", "2", "3")));

        assertThat(resolver.resolveHighestCandidate(
                "network.ike.platform", "ike-bom", "3"))
                .isNull();
    }

    @Test
    void highestCandidateIgnoresOlderEntriesEvenIfPresent() {
        CandidateVersionResolver resolver = fake(Map.of(
                "g:a", List.of("1.0", "1.1", "2.0", "0.9")));

        assertThat(resolver.resolveHighestCandidate("g", "a", "1.1"))
                .isEqualTo("2.0");
    }

    @Test
    void emptyCandidatesReturnsNull() {
        CandidateVersionResolver resolver = fake(Map.of());

        assertThat(resolver.resolveHighestCandidate("g", "a", "1.0"))
                .isNull();
    }

    @Test
    void higherWhenCurrentIsNull() {
        // currentVersion may be null from the model; pick highest.
        CandidateVersionResolver resolver = fake(Map.of(
                "g:a", List.of("1.0", "2.0", "1.5")));

        assertThat(resolver.resolveHighestCandidate("g", "a", null))
                .isEqualTo("2.0");
    }
}

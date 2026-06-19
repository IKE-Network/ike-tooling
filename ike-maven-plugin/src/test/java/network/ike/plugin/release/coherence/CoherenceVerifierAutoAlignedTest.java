package network.ike.plugin.release.coherence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the policy-aware "would this edge auto-align?" decision that
 * the post-release coherence assert uses to avoid false-failing
 * hand-gated upstream pins (#705).
 *
 * <p>Real temp POMs, no mocks (TESTING.md mock-last).
 */
class CoherenceVerifierAutoAlignedTest {

    private static final String TYPED = "network.ike.tooling__GA__ike-tooling__POLICY";
    private static final String LEGACY = "network.ike.tooling·ike-tooling·policy";

    private static File pomWithPolicy(Path dir, String propertyXml) throws Exception {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>network.ike.docs</groupId>
                  <artifactId>ike-docs</artifactId>
                  <version>76</version>
                  <properties>
                %s  </properties>
                </project>
                """.formatted(propertyXml);
        Files.createDirectories(dir);
        Path p = dir.resolve("pom.xml");
        Files.writeString(p, pom);
        return p.toFile();
    }

    @Test
    void absentPolicyDefaultsToIntegrateAndAutoAligns(@TempDir Path tmp) throws Exception {
        File pom = pomWithPolicy(tmp, "");
        assertThat(CoherenceVerifier.autoAligned(pom, TYPED, LEGACY)).isTrue();
    }

    @Test
    void integrateAutoAligns(@TempDir Path tmp) throws Exception {
        File pom = pomWithPolicy(tmp, "    <" + TYPED + ">integrate</" + TYPED + ">\n");
        assertThat(CoherenceVerifier.autoAligned(pom, TYPED, LEGACY)).isTrue();
    }

    @Test
    void releaseAutoAligns(@TempDir Path tmp) throws Exception {
        File pom = pomWithPolicy(tmp, "    <" + TYPED + ">release</" + TYPED + ">\n");
        assertThat(CoherenceVerifier.autoAligned(pom, TYPED, LEGACY)).isTrue();
    }

    @Test
    void notifyIsHandGatedAndNotAsserted(@TempDir Path tmp) throws Exception {
        File pom = pomWithPolicy(tmp, "    <" + TYPED + ">notify</" + TYPED + ">\n");
        assertThat(CoherenceVerifier.autoAligned(pom, TYPED, LEGACY)).isFalse();
    }

    @Test
    void verifyAndProposeAreHandGated(@TempDir Path tmp) throws Exception {
        File verify = pomWithPolicy(tmp.resolve("verify"),
                "    <" + TYPED + ">verify</" + TYPED + ">\n");
        File propose = pomWithPolicy(tmp.resolve("propose"),
                "    <" + TYPED + ">propose</" + TYPED + ">\n");
        assertThat(CoherenceVerifier.autoAligned(verify, TYPED, LEGACY)).isFalse();
        assertThat(CoherenceVerifier.autoAligned(propose, TYPED, LEGACY)).isFalse();
    }

    @Test
    void unresolvedPropertyReferenceDefaultsToIntegrate(@TempDir Path tmp) throws Exception {
        File pom = pomWithPolicy(tmp,
                "    <" + TYPED + ">${some.unset.policy}</" + TYPED + ">\n");
        assertThat(CoherenceVerifier.autoAligned(pom, TYPED, LEGACY)).isTrue();
    }

    @Test
    void legacyPolicyPropertyIsHonoured(@TempDir Path tmp) throws Exception {
        File pom = pomWithPolicy(tmp, "    <" + LEGACY + ">notify</" + LEGACY + ">\n");
        assertThat(CoherenceVerifier.autoAligned(pom, TYPED, LEGACY)).isFalse();
    }
}

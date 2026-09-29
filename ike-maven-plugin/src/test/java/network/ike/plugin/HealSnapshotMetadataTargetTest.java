package network.ike.plugin;

import network.ike.plugin.HealSnapshotMetadataMojo.Target;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deploy-target override {@code ike:heal-snapshot-metadata} honours: Maven's
 * {@code altSnapshotDeploymentRepository} / {@code altDeploymentRepository}
 * syntax (IKE-Network/ike-issues#1160).
 */
class HealSnapshotMetadataTargetTest {

    @Test
    void idAndUrl() {
        assertThat(Target.parse("ike-snapshots::https://nexus.example/repository/ike-snapshots/"))
                .contains(new Target("ike-snapshots", "https://nexus.example/repository/ike-snapshots/"));
    }

    @Test
    void legacyIdLayoutUrl() {
        assertThat(Target.parse("ike-snapshots::default::https://nexus.example/snapshots"))
                .contains(new Target("ike-snapshots", "https://nexus.example/snapshots"));
    }

    @Test
    void anythingElseIsNoTarget() {
        assertThat(Target.parse("https://nexus.example/snapshots")).isEqualTo(Optional.empty());
        assertThat(Target.parse("a::b::c::d")).isEqualTo(Optional.empty());
    }
}

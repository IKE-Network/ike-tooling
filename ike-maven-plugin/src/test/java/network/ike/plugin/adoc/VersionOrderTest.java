package network.ike.plugin.adoc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The order the catalog picks the newest local version by. */
class VersionOrderTest {

    @Test
    void numericSegmentsCompareAsNumbersAndASnapshotPrecedesItsRelease() {
        List<String> versions = new ArrayList<>(List.of("10", "9", "10-SNAPSHOT", "2.1.0", "2.10.0", "11-rc-1"));
        versions.sort(VersionOrder.INSTANCE);
        assertThat(versions).containsExactly("2.1.0", "2.10.0", "9", "10-SNAPSHOT", "10", "11-rc-1");
    }
}

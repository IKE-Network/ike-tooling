package network.ike.plugin.support.upgrade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MavenVersionComparatorTest {

    private final MavenVersionComparator cmp = MavenVersionComparator.INSTANCE;

    // ── Basic ordering ──────────────────────────────────────────────

    @Test
    void higherSingleSegmentBeatsLower() {
        assertThat(cmp.compare("3", "2")).isPositive();
        assertThat(cmp.compare("2", "3")).isNegative();
        assertThat(cmp.compare("3", "3")).isZero();
    }

    @Test
    void higherDottedBeatsLower() {
        assertThat(cmp.compare("1.2.0", "1.1.0")).isPositive();
        assertThat(cmp.compare("1.2.0", "1.2.0")).isZero();
        assertThat(cmp.compare("0.9.9", "1.0.0")).isNegative();
    }

    @Test
    void numericSegmentsCompareNumericallyNotLexically() {
        // "10" > "9" numerically; lex comparison would say the opposite
        assertThat(cmp.compare("1.10.0", "1.9.0")).isPositive();
    }

    @Test
    void singleSegmentIntegerVersions() {
        // IKE platform: "127", "128", ...
        assertThat(cmp.compare("128", "127")).isPositive();
        assertThat(cmp.compare("100", "99")).isPositive();
    }

    // ── SNAPSHOT and qualifiers ─────────────────────────────────────

    @Test
    void snapshotIsLessThanRelease() {
        assertThat(cmp.compare("1.2.0-SNAPSHOT", "1.2.0")).isNegative();
        assertThat(cmp.compare("128-SNAPSHOT", "128")).isNegative();
    }

    @Test
    void alphaBetaRcOrdering() {
        assertThat(cmp.compare("1.0-alpha", "1.0-beta")).isNegative();
        assertThat(cmp.compare("1.0-beta", "1.0-rc")).isNegative();
        assertThat(cmp.compare("1.0-rc", "1.0")).isNegative();
        assertThat(cmp.compare("1.0", "1.0-sp")).isNegative();
    }

    @Test
    void rcSeriesOrders() {
        // 1.0-rc-1 < 1.0-rc-2 < 1.0
        assertThat(cmp.compare("1.0-rc-1", "1.0-rc-2")).isNegative();
        assertThat(cmp.compare("1.0-rc-2", "1.0")).isNegative();
    }

    // ── Splitting ───────────────────────────────────────────────────

    @Test
    void splitsOnDotAndDash() {
        assertThat(MavenVersionComparator.split("1.2.3-rc-1"))
                .containsExactly("1", "2", "3", "rc", "1");
    }

    @Test
    void splitsOnDigitLetterTransition() {
        // "1rc1" should split as ["1","rc","1"]
        assertThat(MavenVersionComparator.split("1rc1"))
                .containsExactly("1", "rc", "1");
    }

    @Test
    void splitsSnapshotSuffix() {
        assertThat(MavenVersionComparator.split("128-SNAPSHOT"))
                .containsExactly("128", "SNAPSHOT");
    }

    // ── Null safety ─────────────────────────────────────────────────

    @Test
    void nullsCompareLowerThanNonNull() {
        assertThat(cmp.compare(null, null)).isZero();
        assertThat(cmp.compare(null, "1.0")).isNegative();
        assertThat(cmp.compare("1.0", null)).isPositive();
    }
}

package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LineDiffTest {

    @Test
    void identicalTextsProduceEmptyDiff() {
        assertThat(LineDiff.unified("a\nb\nc\n", "a\nb\nc\n"))
                .isEqualTo(" a\n b\n c\n");
        assertThat(LineDiff.counts("a\nb\nc\n", "a\nb\nc\n"))
                .isEqualTo(new LineDiff.Counts(0, 0));
    }

    @Test
    void pureAdditionCounts() {
        LineDiff.Counts c = LineDiff.counts("a\nb\n", "a\nx\ny\nb\n");
        assertThat(c.added()).isEqualTo(2);
        assertThat(c.removed()).isZero();
        assertThat(c.shortForm()).isEqualTo("+2/-0");
    }

    @Test
    void pureDeletionCounts() {
        LineDiff.Counts c = LineDiff.counts("a\nx\ny\nb\n", "a\nb\n");
        assertThat(c.added()).isZero();
        assertThat(c.removed()).isEqualTo(2);
    }

    @Test
    void replacementCountsBoth() {
        LineDiff.Counts c = LineDiff.counts("a\nb\nc\n", "a\nB\nc\n");
        assertThat(c.added()).isEqualTo(1);
        assertThat(c.removed()).isEqualTo(1);
    }

    @Test
    void unifiedShowsPlusMinusAndContext() {
        String diff = LineDiff.unified(
                "alpha\nbeta\ngamma\n",
                "alpha\nBETA\ngamma\n");
        assertThat(diff).contains(" alpha\n");
        assertThat(diff).contains("-beta\n");
        assertThat(diff).contains("+BETA\n");
        assertThat(diff).contains(" gamma\n");
    }

    @Test
    void emptyFromBecomesAllAdds() {
        LineDiff.Counts c = LineDiff.counts("", "x\ny\n");
        assertThat(c.added()).isEqualTo(2);
        assertThat(c.removed()).isZero();
    }

    @Test
    void emptyToBecomesAllRemoves() {
        LineDiff.Counts c = LineDiff.counts("x\ny\n", "");
        assertThat(c.added()).isZero();
        assertThat(c.removed()).isEqualTo(2);
    }

    @Test
    void nullTextsTreatedAsEmpty() {
        assertThat(LineDiff.counts(null, null))
                .isEqualTo(new LineDiff.Counts(0, 0));
    }
}

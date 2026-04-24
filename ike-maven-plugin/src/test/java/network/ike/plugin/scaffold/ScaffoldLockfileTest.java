package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaffoldLockfileTest {

    @Test
    void emptyHasCurrentSchemaAndNoEntries() {
        ScaffoldLockfile lf = ScaffoldLockfile.empty();
        assertThat(lf.schema())
                .isEqualTo(ScaffoldLockfile.CURRENT_SCHEMA);
        assertThat(lf.standardsVersion()).isNull();
        assertThat(lf.applied()).isNull();
        assertThat(lf.files()).isEmpty();
    }

    @Test
    void withEntryAddsAndReplacesPreservingOrder() {
        LockfileEntry a = LockfileEntry.toolOwned("sha256:a");
        LockfileEntry b = LockfileEntry.toolOwned("sha256:b");
        LockfileEntry aNew = LockfileEntry.toolOwned("sha256:a2");

        ScaffoldLockfile lf = ScaffoldLockfile.empty()
                .withEntry("mvnw", a)
                .withEntry("mvnw.cmd", b)
                .withEntry("mvnw", aNew);

        assertThat(lf.files().keySet())
                .containsExactly("mvnw", "mvnw.cmd");
        assertThat(lf.files().get("mvnw").templateSha())
                .isEqualTo("sha256:a2");
    }

    @Test
    void withoutEntryDropsKey() {
        ScaffoldLockfile lf = ScaffoldLockfile.empty()
                .withEntry("mvnw", LockfileEntry.toolOwned("sha256:a"))
                .withEntry("mvnw.cmd",
                        LockfileEntry.toolOwned("sha256:b"));

        ScaffoldLockfile after = lf.withoutEntry("mvnw");

        assertThat(after.files()).containsOnlyKeys("mvnw.cmd");
    }

    @Test
    void withoutEntryOnMissingKeyReturnsSameInstance() {
        ScaffoldLockfile lf = ScaffoldLockfile.empty();
        assertThat(lf.withoutEntry("nope")).isSameAs(lf);
    }

    @Test
    void withAppliedStampUpdatesTimestampAndVersion() {
        Instant when = Instant.parse("2026-04-23T10:15:00Z");
        ScaffoldLockfile lf = ScaffoldLockfile.empty()
                .withAppliedStamp("7", when);
        assertThat(lf.standardsVersion()).isEqualTo("7");
        assertThat(lf.applied()).isEqualTo(when);
    }

    @Test
    void filesMapIsImmutable() {
        LockfileEntry e = LockfileEntry.toolOwned("sha256:a");
        LinkedHashMap<String, LockfileEntry> src = new LinkedHashMap<>();
        src.put("mvnw", e);
        ScaffoldLockfile lf = new ScaffoldLockfile(
                ScaffoldLockfile.CURRENT_SCHEMA, null, null, src);
        src.clear();
        assertThat(lf.files()).containsOnlyKeys("mvnw");
        assertThatThrownBy(() -> lf.files()
                .put("x", e))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void zeroOrNegativeSchemaRejected() {
        assertThatThrownBy(() -> new ScaffoldLockfile(
                0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankPathKeyRejected() {
        LinkedHashMap<String, LockfileEntry> src = new LinkedHashMap<>();
        src.put("  ", LockfileEntry.toolOwned("sha256:x"));
        assertThatThrownBy(() -> new ScaffoldLockfile(
                1, null, null, src))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package network.ike.plugin;

import network.ike.plugin.JpackagePropsMojo.BuildProps;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class JpackagePropsTest {

    private static final String DEFAULT_PATTERN = "Komet Desktop {date} {hhmm}{s}";

    // 2026-04-08T19:01:00Z — a typical afternoon build
    private static final Instant APRIL_8_1901 = Instant.parse("2026-04-08T19:01:00Z");

    @Test
    void timestampProperties() {
        BuildProps p = JpackagePropsMojo.computeProps(
                APRIL_8_1901, "3.0.0-SNAPSHOT", "Mac OS X", "aarch64", DEFAULT_PATTERN);

        assertEquals("20260408", p.buildDate());
        assertEquals("26", p.buildYear());
        assertEquals("4", p.buildMonth());
        assertEquals("408", p.buildMonthday());  // no leading zero
        assertEquals("1901", p.buildHhmm());
        assertEquals("2026-04-08", p.buildDisplayDate());
    }

    @Test
    void leadingZeroStripping() {
        // January 3, 05:02 — monthday=103, hhmm=502
        Instant jan3 = Instant.parse("2026-01-03T05:02:00Z");
        BuildProps p = JpackagePropsMojo.computeProps(
                jan3, "1.0.0", "Linux", "amd64", DEFAULT_PATTERN);

        assertEquals("103", p.buildMonthday());
        assertEquals("502", p.buildHhmm());
        assertEquals("1", p.buildMonth());
    }

    @Test
    void midnightEdgeCase() {
        Instant midnight = Instant.parse("2026-06-15T00:00:00Z");
        BuildProps p = JpackagePropsMojo.computeProps(
                midnight, "1.0.0", "Mac OS X", "aarch64", DEFAULT_PATTERN);

        assertEquals("1", p.buildHhmm());  // 0000 -> 1
    }

    @Test
    void snapshotDetection() {
        BuildProps snapshot = JpackagePropsMojo.computeProps(
                APRIL_8_1901, "3.0.0-SNAPSHOT", "Mac OS X", "aarch64", DEFAULT_PATTERN);
        assertTrue(snapshot.isSnapshot());
        assertEquals("777", snapshot.buildQualifier());
        assertTrue(snapshot.jpackageAppName().endsWith("1901s"));

        BuildProps release = JpackagePropsMojo.computeProps(
                APRIL_8_1901, "3.0.0", "Mac OS X", "aarch64", DEFAULT_PATTERN);
        assertFalse(release.isSnapshot());
        assertEquals("0", release.buildQualifier());
        assertTrue(release.jpackageAppName().endsWith("1901"));
        assertFalse(release.jpackageAppName().endsWith("1901s"));
    }

    @Test
    void macArmPlatform() {
        BuildProps p = JpackagePropsMojo.computeProps(
                // A development version: the build-clock scheme is what
                // this test is about (a release now carries its own version).
                APRIL_8_1901, "1.0.0-SNAPSHOT", "Mac OS X", "aarch64", DEFAULT_PATTERN);

        assertEquals("osx-aarch_64", p.platform());
        assertEquals("work-osx-aarch_64", p.platformWork());
        assertEquals("osx-aarch64", p.platformSuffix());
    }

    @Test
    void macIntelPlatform() {
        BuildProps p = JpackagePropsMojo.computeProps(
                APRIL_8_1901, "1.0.0", "Mac OS X", "x86_64", DEFAULT_PATTERN);

        assertEquals("osx-x86_64", p.platform());
        assertEquals("work-osx-x86_64", p.platformWork());
        assertEquals("osx-x86_64", p.platformSuffix());
    }

    @Test
    void linuxPlatform() {
        BuildProps p = JpackagePropsMojo.computeProps(
                APRIL_8_1901, "1.0.0", "Linux", "amd64", DEFAULT_PATTERN);

        assertEquals("linux-x86_64", p.platform());
        assertEquals("work-linux-x86_64", p.platformWork());
        assertEquals("linux-x86_64", p.platformSuffix());
    }

    @Test
    void windowsPlatform() {
        BuildProps p = JpackagePropsMojo.computeProps(
                APRIL_8_1901, "1.0.0", "Windows 11", "amd64", DEFAULT_PATTERN);

        assertEquals("windows-x86_64", p.platform());
        assertEquals("work-windows-x86_64", p.platformWork());
        assertEquals("windows-x86_64", p.platformSuffix());
    }

    @Test
    void windowsMsiSafeVersion() {
        // Windows: YY.month.HHmm — Minor=4 (<=255), Build=1901 (<=65535)
        BuildProps win = JpackagePropsMojo.computeProps(
                APRIL_8_1901, "1.0.0-SNAPSHOT", "Windows 11", "amd64", DEFAULT_PATTERN);

        assertEquals("26.4.1901", win.jpackageAppVersion());
        int minor = Integer.parseInt(win.jpackageAppVersion().split("\\.")[1]);
        assertTrue(minor <= 255, "MSI Minor must be <= 255, was " + minor);
    }

    @Test
    void nonWindowsVersion() {
        // Mac/Linux: YY.monthday.HHmm
        BuildProps mac = JpackagePropsMojo.computeProps(
                // A development version: the build-clock scheme is what
                // this test is about (a release now carries its own version).
                APRIL_8_1901, "1.0.0-SNAPSHOT", "Mac OS X", "aarch64", DEFAULT_PATTERN);

        assertEquals("26.408.1901", mac.jpackageAppVersion());
    }

    @Test
    void windowsAppVersion() {
        BuildProps p = JpackagePropsMojo.computeProps(
                APRIL_8_1901, "3.0.0-SNAPSHOT", "Windows 11", "amd64", DEFAULT_PATTERN);

        assertEquals("26.408.1901.777", p.winAppVersion());
        assertEquals("26", p.winVersionMajor());
        assertEquals("408", p.winVersionMinor());
        assertEquals("1901", p.winVersionBuild());
        assertEquals("777", p.winVersionRevision());
    }

    @Test
    void customAppNamePattern() {
        BuildProps p = JpackagePropsMojo.computeProps(
                APRIL_8_1901, "1.0.0-SNAPSHOT",
                "Mac OS X", "aarch64",
                "MyApp {date} build-{hhmm}{s}");

        assertEquals("MyApp 2026-04-08 build-1901s", p.jpackageAppName());
    }

    @Test
    void msiMinorAlwaysFitsForWindows() {
        // Worst case: December (month=12)
        Instant dec31 = Instant.parse("2026-12-31T23:59:00Z");
        BuildProps p = JpackagePropsMojo.computeProps(
                dec31, "1.0.0-SNAPSHOT", "Windows 10", "amd64", DEFAULT_PATTERN);

        assertEquals("26.12.2359", p.jpackageAppVersion());
        int minor = Integer.parseInt(p.jpackageAppVersion().split("\\.")[1]);
        assertTrue(minor <= 255, "MSI Minor must be <= 255, was " + minor);
        int build = Integer.parseInt(p.jpackageAppVersion().split("\\.")[2]);
        assertTrue(build <= 65535, "MSI Build must be <= 65535, was " + build);
    }

    @Test
    void releaseBuildsCarryTheReleasedVersion() {
        // The komet-desktop 3.0.0 release: the installer must say 3.0.0,
        // not the build clock (IKE-Network/ike-issues#996).
        BuildProps mac = JpackagePropsMojo.computeProps(
                Instant.parse("2026-08-14T18:38:00Z"), "3.0.0",
                "Mac OS X", "aarch64", "Komet Desktop {version}{s}");
        assertEquals("3.0.0", mac.jpackageAppVersion());
        assertEquals("Komet Desktop 3.0.0", mac.jpackageAppName());

        BuildProps win = JpackagePropsMojo.computeProps(
                Instant.parse("2026-08-14T18:38:00Z"), "3.0.0",
                "Windows 11", "amd64", "Komet Desktop {version}{s}");
        assertEquals("3.0.0", win.jpackageAppVersion());
        assertEquals("3.0.0.0", win.winAppVersion());
    }

    @Test
    void developmentBuildsStillCarryTheBuildClock() {
        // A SNAPSHOT has no version of its own worth stamping.
        BuildProps p = JpackagePropsMojo.computeProps(
                Instant.parse("2026-08-14T18:38:00Z"), "3.0.1-SNAPSHOT",
                "Mac OS X", "aarch64", "Komet Desktop {date} {hhmm}{s}");
        assertEquals("26.814.1838", p.jpackageAppVersion());
        assertEquals("Komet Desktop 2026-08-14 1838s", p.jpackageAppName());
    }

    @Test
    void releaseVersionsAreReducedToWhatInstallersAccept() {
        // Single segment, qualifier, and MSI ceilings.
        assertEquals("1.0.0", JpackagePropsMojo.msiSafeVersion("1"));
        assertEquals("1.127.2", JpackagePropsMojo.msiSafeVersion("1.127.2"));
        assertEquals("10.4.2", JpackagePropsMojo.msiSafeVersion("10.4.2-r1"));
        assertEquals("255.255.65535", JpackagePropsMojo.msiSafeVersion("300.900.99999"));
        // Nothing numeric to work with: caller falls back to the clock.
        assertNull(JpackagePropsMojo.msiSafeVersion("main"));
        assertNull(JpackagePropsMojo.msiSafeVersion(null));
    }

}

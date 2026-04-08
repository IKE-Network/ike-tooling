package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;

/**
 * Compute build timestamp, platform, and JPackage version properties.
 *
 * <p>Sets ~19 Maven project properties consumed by JReleaser's jpackage
 * assembler via resource-filtered YAML. Replaces build-helper-maven-plugin
 * timestamp/regex executions, maven-antrun-plugin derived-property logic,
 * and OS-activated Maven profiles for platform detection.
 *
 * <p>All timestamps are UTC. Platform detection uses {@code os.name} and
 * {@code os.arch} system properties mapped to JReleaser canonical names.
 * Windows MSI version constraints (Minor &le; 255) are handled automatically.
 *
 * <p>Usage:
 * <pre>
 * mvn ike:jpackage-props
 * </pre>
 */
@Mojo(name = "jpackage-props",
      defaultPhase = LifecyclePhase.INITIALIZE,
      requiresProject = true,
      threadSafe = true)
public class JpackagePropsMojo extends AbstractMojo {

    /** Creates this goal instance. */
    public JpackagePropsMojo() {}

    /** The current Maven project. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Application name pattern. Placeholders:
     * <ul>
     *   <li>{@code {date}} — display date (yyyy-MM-dd)</li>
     *   <li>{@code {hhmm}} — UTC time (leading-zero-stripped)</li>
     *   <li>{@code {s}} — "s" for SNAPSHOT, empty for release</li>
     * </ul>
     */
    @Parameter(property = "jpackage.appNamePattern",
               defaultValue = "Komet Desktop {date} {hhmm}{s}")
    private String appNamePattern;

    /**
     * Explicit build timestamp (ISO-8601 instant, e.g. {@code 2026-04-08T19:01:00Z}).
     * Defaults to current time.
     */
    @Parameter(property = "jpackage.buildTimestamp")
    private String buildTimestamp;

    @Override
    public void execute() throws MojoExecutionException {
        Instant instant;
        try {
            instant = (buildTimestamp != null && !buildTimestamp.isBlank())
                    ? Instant.parse(buildTimestamp)
                    : Instant.now();
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Invalid jpackage.buildTimestamp: " + buildTimestamp, e);
        }

        BuildProps props = computeProps(
                instant,
                project.getVersion(),
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                appNamePattern);

        Properties p = project.getProperties();
        p.setProperty("build.date", props.buildDate());
        p.setProperty("build.year", props.buildYear());
        p.setProperty("build.month", props.buildMonth());
        p.setProperty("build.monthday", props.buildMonthday());
        p.setProperty("build.hhmm", props.buildHhmm());
        p.setProperty("build.display.date", props.buildDisplayDate());
        p.setProperty("jreleaser.platform", props.platform());
        p.setProperty("jreleaser.platform.work", props.platformWork());
        p.setProperty("jreleaser.platform.suffix", props.platformSuffix());
        p.setProperty("is.snapshot", String.valueOf(props.isSnapshot()));
        p.setProperty("build.qualifier", props.buildQualifier());
        p.setProperty("jpackage.app.version", props.jpackageAppVersion());
        p.setProperty("jpackage.app.name", props.jpackageAppName());
        p.setProperty("win.app.version", props.winAppVersion());
        p.setProperty("win.version.major", props.winVersionMajor());
        p.setProperty("win.version.minor", props.winVersionMinor());
        p.setProperty("win.version.build", props.winVersionBuild());
        p.setProperty("win.version.revision", props.winVersionRevision());

        getLog().info("");
        getLog().info("JPackage Build Properties");
        getLog().info("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        getLog().info("  Platform:          " + props.platform());
        getLog().info("  Is Snapshot:       " + props.isSnapshot());
        getLog().info("  Build Qualifier:   " + props.buildQualifier());
        getLog().info("  App Name:          " + props.jpackageAppName());
        getLog().info("  App Version:       " + props.jpackageAppVersion());
        getLog().info("  Windows Version:   " + props.winAppVersion());
        getLog().info("  Build Date:        " + props.buildDisplayDate());
        getLog().info("  Build HHmm:        " + props.buildHhmm());
        getLog().info("");
    }

    /**
     * All computed properties returned as a record for testability.
     */
    record BuildProps(
            String buildDate, String buildYear, String buildMonth,
            String buildMonthday, String buildHhmm, String buildDisplayDate,
            String platform, String platformWork, String platformSuffix,
            boolean isSnapshot, String buildQualifier,
            String jpackageAppVersion, String jpackageAppName,
            String winAppVersion, String winVersionMajor, String winVersionMinor,
            String winVersionBuild, String winVersionRevision
    ) {}

    /**
     * Pure computation of all properties — no Maven or system dependencies.
     *
     * @param buildInstant   the build timestamp
     * @param projectVersion the Maven project version (e.g. "3.0.0-SNAPSHOT")
     * @param osName         value of {@code os.name} system property
     * @param osArch         value of {@code os.arch} system property
     * @param appNamePattern pattern with {date}, {hhmm}, {s} placeholders
     * @return all computed properties
     */
    static BuildProps computeProps(Instant buildInstant,
                                   String projectVersion,
                                   String osName,
                                   String osArch,
                                   String appNamePattern) {
        ZonedDateTime utc = buildInstant.atZone(ZoneOffset.UTC);

        // Timestamp properties
        String buildDate = utc.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String buildYear = utc.format(DateTimeFormatter.ofPattern("yy"));
        String buildMonth = String.valueOf(utc.getMonthValue());
        String buildDisplayDate = utc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        // monthday and hhmm as integers (no leading zeros)
        int monthday = utc.getMonthValue() * 100 + utc.getDayOfMonth();
        int hhmm = utc.getHour() * 100 + utc.getMinute();
        if (hhmm == 0) hhmm = 1; // midnight: 0000 -> 1

        String buildMonthday = String.valueOf(monthday);
        String buildHhmm = String.valueOf(hhmm);

        // Platform detection
        String osLower = osName.toLowerCase(Locale.ROOT);
        String archLower = osArch.toLowerCase(Locale.ROOT);

        String platform;
        String platformSuffix;
        if (osLower.contains("mac") || osLower.contains("darwin")) {
            if (archLower.equals("aarch64") || archLower.equals("arm64")) {
                platform = "osx-aarch_64";
                platformSuffix = "osx-aarch64";
            } else {
                platform = "osx-x86_64";
                platformSuffix = "osx-x86_64";
            }
        } else if (osLower.contains("linux")) {
            platform = "linux-x86_64";
            platformSuffix = "linux-x86_64";
        } else if (osLower.contains("windows")) {
            platform = "windows-x86_64";
            platformSuffix = "windows-x86_64";
        } else {
            platform = "unknown";
            platformSuffix = "unknown";
        }
        String platformWork = "work-" + platform;
        boolean isWindows = osLower.contains("windows");

        // Derived properties
        boolean isSnapshot = projectVersion != null && projectVersion.endsWith("-SNAPSHOT");
        String buildQualifier = isSnapshot ? "777" : "0";

        // JPackage version: Windows MSI requires Minor <= 255
        String jpackageAppVersion = isWindows
                ? buildYear + "." + buildMonth + "." + buildHhmm
                : buildYear + "." + buildMonthday + "." + buildHhmm;

        // App name from pattern
        String snapshotSuffix = isSnapshot ? "s" : "";
        String jpackageAppName = appNamePattern
                .replace("{date}", buildDisplayDate)
                .replace("{hhmm}", buildHhmm)
                .replace("{s}", snapshotSuffix);

        // Windows version components
        String winVersionMajor = buildYear;
        String winVersionMinor = buildMonthday;
        String winVersionBuild = buildHhmm;
        String winVersionRevision = buildQualifier;
        String winAppVersion = winVersionMajor + "." + winVersionMinor + "."
                + winVersionBuild + "." + winVersionRevision;

        return new BuildProps(
                buildDate, buildYear, buildMonth, buildMonthday, buildHhmm,
                buildDisplayDate, platform, platformWork, platformSuffix,
                isSnapshot, buildQualifier, jpackageAppVersion, jpackageAppName,
                winAppVersion, winVersionMajor, winVersionMinor, winVersionBuild,
                winVersionRevision);
    }
}

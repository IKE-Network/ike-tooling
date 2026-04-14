package network.ike.plugin;

import org.apache.maven.api.Project;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ProjectManager;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

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
      defaultPhase = "initialize",
      projectRequired = true)
public class JpackagePropsMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /** Creates this goal instance. */
    public JpackagePropsMojo() {}

    /** The current project (injected by Maven 4). */
    @org.apache.maven.api.di.Inject
    private Project project;

    /** Project manager for setting properties on the project. */
    @org.apache.maven.api.di.Inject
    private ProjectManager projectManager;

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
    public void execute() throws MojoException {
        Instant instant;
        try {
            instant = (buildTimestamp != null && !buildTimestamp.isBlank())
                    ? Instant.parse(buildTimestamp)
                    : Instant.now();
        } catch (Exception e) {
            throw new MojoException(
                    "Invalid jpackage.buildTimestamp: " + buildTimestamp, e);
        }

        BuildProps props = computeProps(
                instant,
                project.getVersion(),
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                appNamePattern);

        Map<String, String> p = projectManager.getProperties(project);
        p.put("build.date", props.buildDate());
        p.put("build.year", props.buildYear());
        p.put("build.month", props.buildMonth());
        p.put("build.monthday", props.buildMonthday());
        p.put("build.hhmm", props.buildHhmm());
        p.put("build.display.date", props.buildDisplayDate());
        p.put("jreleaser.platform", props.platform());
        p.put("jreleaser.platform.work", props.platformWork());
        p.put("jreleaser.platform.suffix", props.platformSuffix());
        p.put("is.snapshot", String.valueOf(props.isSnapshot()));
        p.put("build.qualifier", props.buildQualifier());
        p.put("jpackage.app.version", props.jpackageAppVersion());
        p.put("jpackage.app.name", props.jpackageAppName());
        p.put("win.app.version", props.winAppVersion());
        p.put("win.version.major", props.winVersionMajor());
        p.put("win.version.minor", props.winVersionMinor());
        p.put("win.version.build", props.winVersionBuild());
        p.put("win.version.revision", props.winVersionRevision());

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

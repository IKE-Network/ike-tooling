package network.ike.plugin;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ProjectManager;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


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
@Mojo(name = IkeGoal.NAME_JPACKAGE_PROPS,
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

    /** The current Maven session. */
    @org.apache.maven.api.di.Inject
    private Session session;

    /** The current project (injected by Maven 4). */
    @org.apache.maven.api.di.Inject
    private Project project;

    /**
     * Application name pattern. Placeholders:
     * <ul>
     *   <li>{@code {date}} — display date (yyyy-MM-dd)</li>
     *   <li>{@code {hhmm}} — UTC time (leading-zero-stripped)</li>
     *   <li>{@code {version}} — the released version for a release build,
     *       the build-clock version for a development build</li>
     *   <li>{@code {s}} — "s" for SNAPSHOT, empty for release</li>
     * </ul>
     */
    @Parameter(property = "jpackage.appNamePattern",
               defaultValue = "Komet Desktop {date} {hhmm}{s}")
    private String appNamePattern;

    /**
     * Application name pattern for a released build. A development build
     * is identified by when it was built; a release is identified by its
     * version, and naming it after a timestamp leaves an installer nobody
     * can match to a release (IKE-Network/ike-issues#996). Same
     * placeholders as {@link #appNamePattern}.
     */
    @Parameter(property = "jpackage.releaseAppNamePattern",
               defaultValue = "Komet Desktop {version}")
    private String releaseAppNamePattern;

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
                appNamePattern,
                releaseAppNamePattern);

        ProjectManager pm = session.getService(ProjectManager.class);
        pm.setProperty(project, "build.date", props.buildDate());
        pm.setProperty(project, "build.year", props.buildYear());
        pm.setProperty(project, "build.month", props.buildMonth());
        pm.setProperty(project, "build.monthday", props.buildMonthday());
        pm.setProperty(project, "build.hhmm", props.buildHhmm());
        pm.setProperty(project, "build.display.date", props.buildDisplayDate());
        pm.setProperty(project, "jreleaser.platform", props.platform());
        pm.setProperty(project, "jreleaser.platform.work", props.platformWork());
        pm.setProperty(project, "jreleaser.platform.suffix", props.platformSuffix());
        pm.setProperty(project, "is.snapshot", String.valueOf(props.isSnapshot()));
        pm.setProperty(project, "build.qualifier", props.buildQualifier());
        pm.setProperty(project, "jpackage.app.version", props.jpackageAppVersion());
        pm.setProperty(project, "jpackage.app.name", props.jpackageAppName());
        pm.setProperty(project, "win.app.version", props.winAppVersion());
        pm.setProperty(project, "win.version.major", props.winVersionMajor());
        pm.setProperty(project, "win.version.minor", props.winVersionMinor());
        pm.setProperty(project, "win.version.build", props.winVersionBuild());
        pm.setProperty(project, "win.version.revision", props.winVersionRevision());

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
     * Reduce a released Maven version to something jpackage and the Windows
     * installer will both accept: up to three dot-separated numbers, each
     * within the MSI limits (major and minor 0-255, build 0-65535). A
     * qualifier such as {@code -r1} is dropped, a single-segment version
     * like {@code 1} becomes {@code 1.0.0}, and anything with no leading
     * number at all yields {@code null} so the caller falls back to the
     * build-clock scheme rather than emitting an installer jpackage would
     * reject.
     *
     * @param projectVersion the released Maven version
     * @return an installer-safe version, or {@code null} when none can be
     *         derived
     */
    static String msiSafeVersion(String projectVersion) {
        if (projectVersion == null || projectVersion.isBlank()) return null;
        String[] raw = projectVersion.trim().split("[.\\-+_]");
        List<Integer> numbers = new ArrayList<>();
        for (String part : raw) {
            if (!part.matches("\\d+")) break;
            numbers.add(Integer.parseInt(part));
            if (numbers.size() == 3) break;
        }
        if (numbers.isEmpty()) return null;
        while (numbers.size() < 3) numbers.add(0);
        int major = Math.min(numbers.get(0), 255);
        int minor = Math.min(numbers.get(1), 255);
        int build = Math.min(numbers.get(2), 65535);
        return major + "." + minor + "." + build;
    }

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
        return computeProps(buildInstant, projectVersion, osName, osArch,
                appNamePattern, appNamePattern);
    }

    /**
     * Pure computation, choosing the name pattern by build kind.
     *
     * @param buildInstant           the build timestamp
     * @param projectVersion         the Maven project version
     * @param osName                 value of {@code os.name}
     * @param osArch                 value of {@code os.arch}
     * @param appNamePattern         pattern for a development build
     * @param releaseAppNamePattern  pattern for a released build
     * @return all computed properties
     */
    static BuildProps computeProps(Instant buildInstant,
                                   String projectVersion,
                                   String osName,
                                   String osArch,
                                   String appNamePattern,
                                   String releaseAppNamePattern) {
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

        // JPackage version. A development build has no meaningful version
        // of its own, so it is stamped with the build clock; a released
        // build has exactly one right answer — the version it was released
        // as — and stamping it with a timestamp instead leaves an installer
        // nobody can match to a release (IKE-Network/ike-issues#996).
        String releaseAppVersion = isSnapshot ? null
                : msiSafeVersion(projectVersion);
        String jpackageAppVersion = releaseAppVersion != null
                ? releaseAppVersion
                : isWindows
                    // Windows MSI requires Minor <= 255
                    ? buildYear + "." + buildMonth + "." + buildHhmm
                    : buildYear + "." + buildMonthday + "." + buildHhmm;

        // App name from pattern
        String snapshotSuffix = isSnapshot ? "s" : "";
        String namePattern = releaseAppVersion != null
                ? releaseAppNamePattern : appNamePattern;
        String jpackageAppName = namePattern
                .replace("{date}", buildDisplayDate)
                .replace("{hhmm}", buildHhmm)
                .replace("{version}", releaseAppVersion != null
                        ? releaseAppVersion : jpackageAppVersion)
                .replace("{s}", snapshotSuffix);

        // Windows version components
        String winVersionMajor = buildYear;
        String winVersionMinor = buildMonthday;
        String winVersionBuild = buildHhmm;
        String winVersionRevision = buildQualifier;
        if (releaseAppVersion != null) {
            String[] parts = releaseAppVersion.split("\\.");
            winVersionMajor = parts[0];
            winVersionMinor = parts.length > 1 ? parts[1] : "0";
            winVersionBuild = parts.length > 2 ? parts[2] : "0";
            winVersionRevision = "0";
        }
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

package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scan POM files for {@code -SNAPSHOT} references that would leak into
 * a released artifact via Maven 4's consumer POM flattener.
 *
 * <p>Maven 4 resolves properties and promotes {@code <pluginManagement>}
 * entries into {@code <plugins>} when it writes the <em>consumer POM</em>
 * — the POM consumers download from the repository. If a
 * {@code <pluginManagement>} entry references a property (e.g.
 * {@code <version>${ike-tooling.version}</version>}) whose value ends in
 * {@code -SNAPSHOT}, the flattener writes the literal SNAPSHOT string
 * into the released artifact. Downstream consumers then see
 * {@code <version>117-SNAPSHOT</version>} in the released POM and their
 * builds fail with "artifact not found" — even though the release
 * passed locally.
 *
 * <p>This scanner runs two layers of check at release time:
 *
 * <ul>
 *   <li><strong>Source properties scan</strong> via
 *       {@link #scanSourceProperties(File)} — reads the root
 *       {@code <properties>} block and fails if any value ends in
 *       {@code -SNAPSHOT}. Catches the bug at its source before any
 *       release mutation runs.</li>
 *   <li><strong>Post-mutation version scan</strong> via
 *       {@link #scanForSnapshotVersions(List)} — after
 *       {@link ReleaseSupport#replaceProjectVersionRefs(File, String,
 *       org.apache.maven.api.plugin.Log)} has rewritten
 *       {@code ${project.version}} to a literal, scans every POM for
 *       any remaining {@code <version>...-SNAPSHOT</version>}. Defense
 *       in depth for literal SNAPSHOT versions that slipped past the
 *       property scan.</li>
 * </ul>
 */
public final class SnapshotScanner {

    /** Suffix that marks a SNAPSHOT version. */
    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    /**
     * Matches the root {@code <properties>...</properties>} block. The
     * {@code DOTALL} flag lets {@code .} cross newlines so the block
     * can span multiple lines.
     */
    private static final Pattern PROPERTIES_BLOCK = Pattern.compile(
            "<properties>(.*?)</properties>", Pattern.DOTALL);

    /**
     * Matches a single property element inside a {@code <properties>}
     * block. Captures the element name and the text value. Does not
     * match self-closing or commented elements (those won't hold a
     * SNAPSHOT value anyway).
     */
    private static final Pattern PROPERTY_ELEMENT = Pattern.compile(
            "<([A-Za-z][A-Za-z0-9._-]*)>([^<]+)</\\1>");

    /**
     * Matches any {@code <version>...</version>} element in a POM.
     * Used by the post-mutation scan to catch literal SNAPSHOT versions.
     */
    private static final Pattern VERSION_ELEMENT = Pattern.compile(
            "<version>([^<]+)</version>");

    private SnapshotScanner() {}

    /**
     * A single SNAPSHOT reference that would leak into a released POM.
     *
     * @param pomFile  the POM file containing the reference
     * @param location descriptor of the element location
     *                 (e.g. {@code "<ike-tooling.version>"} or
     *                 {@code "<version>"} for a literal version)
     * @param value    the SNAPSHOT-ending value found
     */
    public record Violation(File pomFile, String location, String value) {

        /**
         * Format this violation as a single indented bullet for an
         * aggregated error message.
         *
         * @param gitRoot the repository root used to relativize the
         *                POM path; may be {@code null} for absolute paths
         * @return a single-line bullet formatted for log output
         */
        public String toBullet(File gitRoot) {
            String path = (gitRoot != null)
                    ? gitRoot.toPath().relativize(pomFile.toPath()).toString()
                    : pomFile.getPath();
            return "    • " + path + ": " + location + " = " + value;
        }
    }

    /**
     * Scan the root {@code <properties>} block of a POM for any value
     * ending in {@code -SNAPSHOT}.
     *
     * <p>This is the primary gate that catches the original
     * {@code <ike-tooling.version>112-SNAPSHOT</ike-tooling.version>}
     * class of bug before any release mutation runs.
     *
     * @param pomFile the POM file to scan
     * @return violations found in the properties block; empty if clean
     * @throws MojoException if the file cannot be read
     */
    public static List<Violation> scanSourceProperties(File pomFile) {
        String content;
        try {
            content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to read " + pomFile + ": " + e.getMessage(), e);
        }

        Matcher blockMatcher = PROPERTIES_BLOCK.matcher(content);
        if (!blockMatcher.find()) return List.of();

        String propertiesBlock = blockMatcher.group(1);
        List<Violation> violations = new ArrayList<>();
        Matcher propMatcher = PROPERTY_ELEMENT.matcher(propertiesBlock);
        while (propMatcher.find()) {
            String name = propMatcher.group(1);
            String value = propMatcher.group(2).trim();
            if (value.endsWith(SNAPSHOT_SUFFIX)) {
                violations.add(new Violation(
                        pomFile, "<" + name + ">", value));
            }
        }
        return violations;
    }

    /**
     * Scan a list of POM files for any literal
     * {@code <version>...-SNAPSHOT</version>} element.
     *
     * <p>Intended for use <em>after</em>
     * {@link ReleaseSupport#replaceProjectVersionRefs(File, String,
     * org.apache.maven.api.plugin.Log)} has resolved
     * {@code ${project.version}} to a literal. Any remaining SNAPSHOT
     * version in a {@code <version>} element is a baked-in reference
     * that would leak through the consumer POM.
     *
     * @param pomFiles POM files to scan
     * @return violations found across all POMs; empty if clean
     * @throws MojoException if any file cannot be read
     */
    public static List<Violation> scanForSnapshotVersions(List<File> pomFiles) {
        List<Violation> violations = new ArrayList<>();
        for (File pom : pomFiles) {
            String content;
            try {
                content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to read " + pom + ": " + e.getMessage(), e);
            }
            Matcher m = VERSION_ELEMENT.matcher(content);
            while (m.find()) {
                String value = m.group(1).trim();
                if (value.endsWith(SNAPSHOT_SUFFIX)) {
                    violations.add(new Violation(pom, "<version>", value));
                }
            }
        }
        return violations;
    }

    /**
     * Format a list of violations as an aggregated multi-line message
     * suitable for {@code MojoException} or preflight output.
     *
     * @param violations the violations to format (non-empty)
     * @param gitRoot    repo root for relative path display; may be null
     * @param headline   opening sentence (e.g. "Cannot release — ...")
     * @param remedyHint closing instruction shown after the bullets
     * @return a formatted multi-line error body
     */
    public static String formatViolations(List<Violation> violations,
                                           File gitRoot,
                                           String headline,
                                           String remedyHint) {
        StringBuilder sb = new StringBuilder();
        sb.append(headline).append("\n");
        for (Violation v : violations) {
            sb.append(v.toBullet(gitRoot)).append("\n");
        }
        sb.append(remedyHint);
        return sb.toString();
    }
}

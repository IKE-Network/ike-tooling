package network.ike.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a Maven subproject root to determine the complete set of
 * published artifacts (groupId:artifactId pairs).
 *
 * <p>This is the "published artifact set" from the handoff design:
 * given a subproject root directory, recursively walk the POM hierarchy
 * (root POM plus all subprojects/modules) and collect every
 * groupId:artifactId pair that the subproject publishes.
 *
 * <p>POM parsing uses simple regex matching (consistent with the
 * {@code ReleaseSupport} pattern) rather than a full XML parser.
 * The {@code <parent>} block is stripped before extracting the
 * project's own groupId and artifactId; if no groupId is declared
 * outside the parent block, the parent's groupId is inherited.
 */
public final class PublishedArtifactSet {

    private PublishedArtifactSet() {}

    /**
     * A published Maven artifact coordinate.
     *
     * @param groupId    the Maven groupId
     * @param artifactId the Maven artifactId
     */
    public record Artifact(String groupId, String artifactId) {}

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("<version>([^<]+)</version>");
    private static final Pattern GROUP_ID_PATTERN =
            Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern ARTIFACT_ID_PATTERN =
            Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern SUBPROJECTS_PATTERN =
            Pattern.compile("<subproject>([^<]+)</subproject>");
    private static final Pattern MODULES_PATTERN =
            Pattern.compile("<module>([^<]+)</module>");
    private static final Pattern PARENT_BLOCK =
            Pattern.compile("(?s)<parent>.*?</parent>");
    /** Self-closing {@code <parent/>} — Maven 4.1.0's inferred parent (carries no groupId). */
    private static final Pattern PARENT_SELF_CLOSING =
            Pattern.compile("<parent\\s*/>");
    /**
     * Start of the first POM body section that may itself contain a {@code <groupId>}
     * — a dependency, managed dependency, or plugin. The project's own coordinates are
     * schema-ordered before these, so own-coordinate extraction stops here so a
     * dependency's groupId is never mistaken for the project's (ike-issues#719).
     */
    private static final Pattern BODY_SECTION = Pattern.compile(
            "<(dependencies|dependencyManagement|build|reporting|profiles|distributionManagement)\\b");

    /**
     * Scan a subproject root and return the complete set of published
     * artifacts (groupId:artifactId pairs).
     *
     * <p>Reads the root pom.xml, extracts its coordinates, then
     * recursively descends into each subproject (or module) directory
     * to collect all published artifacts.
     *
     * @param subprojectRoot the root directory of the Maven subproject
     * @return the set of all published artifacts
     * @throws IOException if a POM file cannot be read
     */
    public static Set<Artifact> scan(Path subprojectRoot) throws IOException {
        Set<Artifact> artifacts = new LinkedHashSet<>();
        Path rootPom = subprojectRoot.resolve("pom.xml");

        if (!Files.exists(rootPom)) {
            return artifacts;
        }

        scanPom(subprojectRoot, rootPom, null, artifacts);
        return artifacts;
    }

    /**
     * Check whether a groupId:artifactId pair is in the published set.
     *
     * @param artifacts  the set from {@link #scan(Path)}
     * @param groupId    the groupId to check
     * @param artifactId the artifactId to check
     * @return true if the pair is in the set
     */
    public static boolean matches(Set<Artifact> artifacts,
                                  String groupId, String artifactId) {
        return artifacts.contains(new Artifact(groupId, artifactId));
    }

    /**
     * Parse a single POM, add its artifact to the set, then recurse
     * into any declared subprojects or modules.
     *
     * @param subprojectRoot  the subproject root (for resolving relative paths)
     * @param pomPath        the POM file to parse
     * @param inheritGroupId the parent groupId to inherit if not declared
     * @param artifacts      accumulator for discovered artifacts
     */
    private static void scanPom(Path subprojectRoot, Path pomPath,
                                String inheritGroupId,
                                Set<Artifact> artifacts) throws IOException {
        String content = Files.readString(pomPath, StandardCharsets.UTF_8);

        // groupId declared inside a paired <parent>…</parent> block, if any (used
        // for inheritance). A self-closing <parent/> — Maven 4.1.0's inferred
        // parent — carries none, so the groupId then comes from inheritGroupId.
        String parentGroupId = null;
        Matcher parentMatcher = PARENT_BLOCK.matcher(content);
        if (parentMatcher.find()) {
            Matcher gm = GROUP_ID_PATTERN.matcher(parentMatcher.group());
            if (gm.find()) {
                parentGroupId = gm.group(1).trim();
            }
        }

        // Strip the parent block (paired or self-closing) so the parent's own
        // groupId is never read as the project's.
        String stripped = PARENT_BLOCK.matcher(content).replaceFirst("");
        stripped = PARENT_SELF_CLOSING.matcher(stripped).replaceFirst("");

        // The project's own <groupId>/<artifactId> are schema-ordered BEFORE any
        // body section that can also carry a <groupId> (dependencies, managed
        // dependencies, plugins). Restrict extraction to that header so a
        // dependency's groupId is never mistaken for the project's — the bug that
        // dropped komet's inter-subproject edges (ike-issues#719). A module that
        // declares no own groupId (the Maven-4.1.0 norm under <parent/>) then
        // correctly yields null here and inherits below.
        String header = stripped.substring(0, bodySectionStart(stripped));

        // Inherit groupId: prefer own, then the parent block, then the reactor
        // parent passed down the recursion.
        String groupId = firstCapture(GROUP_ID_PATTERN, header);
        if (groupId == null) {
            groupId = parentGroupId;
        }
        if (groupId == null) {
            groupId = inheritGroupId;
        }

        String artifactId = firstCapture(ARTIFACT_ID_PATTERN, header);

        if (groupId != null && artifactId != null) {
            artifacts.add(new Artifact(groupId, artifactId));
        }

        // The groupId to pass down for inheritance
        String effectiveGroupId = groupId;

        // Find subprojects (POM 4.1.0) or modules (POM 4.0.0)
        Path pomDir = pomPath.getParent();

        // Scan <subproject> entries first (newer model)
        Matcher subMatcher = SUBPROJECTS_PATTERN.matcher(content);
        while (subMatcher.find()) {
            String subproject = subMatcher.group(1).trim();
            Path subPom = pomDir.resolve(subproject).resolve("pom.xml");
            if (Files.exists(subPom)) {
                scanPom(subprojectRoot, subPom, effectiveGroupId, artifacts);
            }
        }

        // Scan <module> entries (classic model)
        Matcher modMatcher = MODULES_PATTERN.matcher(content);
        while (modMatcher.find()) {
            String module = modMatcher.group(1).trim();
            Path modPom = pomDir.resolve(module).resolve("pom.xml");
            if (Files.exists(modPom)) {
                scanPom(subprojectRoot, modPom, effectiveGroupId, artifacts);
            }
        }
    }

    /** First capture group of {@code pattern} in {@code text}, trimmed, or null. */
    private static String firstCapture(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Index where the first {@code <groupId>}-bearing body section starts, or the
     * full length when there is none — bounding own-coordinate extraction so a
     * dependency/plugin groupId is never read as the project's.
     */
    private static int bodySectionStart(String content) {
        Matcher m = BODY_SECTION.matcher(content);
        return m.find() ? m.start() : content.length();
    }
}

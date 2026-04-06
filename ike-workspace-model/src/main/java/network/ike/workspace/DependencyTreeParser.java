package network.ike.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse the text output of {@code mvn dependency:tree} into structured
 * records.
 *
 * <p>The parser handles the tree-drawing characters used by Maven's
 * dependency plugin ({@code +- }, {@code \- }, {@code |  }, spaces)
 * and extracts the GAV coordinate, type, scope, and nesting depth
 * from each line.
 *
 * <p>Example input:
 * <pre>{@code
 * dev.ikm.tinkar:tinkar-core:pom:1.127.0-SNAPSHOT
 * +- dev.ikm.tinkar:collection:jar:1.127.0-SNAPSHOT:compile
 * |  +- org.eclipse.collections:eclipse-collections-api:jar:11.1.0:compile
 * |  \- org.eclipse.collections:eclipse-collections:jar:11.1.0:compile
 * \- dev.ikm.tinkar:common:jar:1.127.0-SNAPSHOT:compile
 *    +- io.activej:activej-common:jar:6.0-rc2:compile
 *    \- org.slf4j:slf4j-api:jar:2.0.16:compile
 * }</pre>
 */
public final class DependencyTreeParser {

    // Matches: groupId:artifactId:type:version[:classifier]:scope
    // or the root: groupId:artifactId:type:version
    private static final Pattern GAV_PATTERN = Pattern.compile(
            "([^:]+):([^:]+):([^:]+):([^:]+?)(?::([^:]+))?(?::([^:]+))?$");

    private DependencyTreeParser() {}

    /**
     * A single resolved dependency from a dependency tree.
     *
     * @param groupId    the Maven groupId
     * @param artifactId the Maven artifactId
     * @param type       the artifact type (jar, pom, etc.)
     * @param version    the resolved version
     * @param scope      the dependency scope (compile, runtime, test, etc.),
     *                   or empty string for the root artifact
     * @param depth      nesting depth (0 = root project)
     */
    public record ResolvedDependency(
            String groupId, String artifactId, String type,
            String version, String scope, int depth) {}

    /**
     * Parse dependency tree text output into a list of resolved
     * dependencies.
     *
     * <p>Skips blank lines and lines that don't match the expected
     * Maven coordinate format (e.g. informational headers).
     *
     * @param treeOutput the raw text from {@code mvn dependency:tree}
     * @return ordered list of resolved dependencies
     */
    public static List<ResolvedDependency> parse(String treeOutput) {
        List<ResolvedDependency> result = new ArrayList<>();
        for (String line : treeOutput.lines().toList()) {
            if (line.isBlank()) continue;

            int depth = measureDepth(line);
            String coordinate = stripTreeChars(line);
            if (coordinate.isEmpty()) continue;

            Matcher m = GAV_PATTERN.matcher(coordinate);
            if (!m.matches()) continue;

            String groupId = m.group(1);
            String artifactId = m.group(2);
            String type = m.group(3);
            String versionOrClassifier = m.group(4);
            String group5 = m.group(5);
            String group6 = m.group(6);

            // Maven coordinate formats:
            //   g:a:type:version                     (root, no scope)
            //   g:a:type:version:scope               (normal dep)
            //   g:a:type:classifier:version:scope    (classified dep)
            String version;
            String scope;
            if (group6 != null) {
                // g:a:type:classifier:version:scope
                version = group5;
                scope = group6;
            } else if (group5 != null) {
                // g:a:type:version:scope
                version = versionOrClassifier;
                scope = group5;
            } else {
                // g:a:type:version (root)
                version = versionOrClassifier;
                scope = "";
            }

            result.add(new ResolvedDependency(
                    groupId, artifactId, type, version, scope, depth));
        }
        return result;
    }

    /**
     * Measure the nesting depth from tree-drawing characters.
     * Each level is 3 characters wide in Maven's output.
     */
    static int measureDepth(String line) {
        int i = 0;
        int depth = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '|' || c == '+' || c == '\\' || c == ' ') {
                i++;
            } else if (c == '-') {
                i++;
                depth++;
                // Skip trailing space after '-'
                if (i < line.length() && line.charAt(i) == ' ') i++;
                break;
            } else {
                break;
            }
        }
        // Count leading tree segments: each "|  " or "   " is one level
        if (depth > 0) {
            // The prefix before "+- " or "\- " consists of 3-char segments
            String prefix = line.substring(0, Math.max(0, line.indexOf(depth > 0 ? '+' : '|')));
            // Simpler: count by position of the +/\ character
            int markerPos = findMarkerPosition(line);
            if (markerPos >= 0) {
                depth = (markerPos / 3) + 1;
            }
        }
        return depth;
    }

    private static int findMarkerPosition(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '+' || c == '\\') return i;
        }
        return -1;
    }

    /**
     * Strip tree-drawing characters from a dependency line,
     * returning just the Maven coordinate string.
     */
    static String stripTreeChars(String line) {
        // Remove all leading tree chars: |, +, \, -, space
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '|' || c == '+' || c == '\\' || c == '-' || c == ' ') {
                i++;
            } else {
                break;
            }
        }
        return line.substring(i).trim();
    }
}

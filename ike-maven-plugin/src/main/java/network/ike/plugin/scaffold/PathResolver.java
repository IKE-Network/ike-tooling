package network.ike.plugin.scaffold;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Expands manifest {@code dest} strings to absolute filesystem paths.
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@link ScaffoldScope#USER} entries start with {@code "~/"} and
 *       resolve against the caller-supplied user home.</li>
 *   <li>{@link ScaffoldScope#PROJECT} entries may optionally start
 *       with {@code "{project.root}/"} (the literal token, not a
 *       property reference); both forms resolve against the
 *       project-root path passed in.</li>
 * </ul>
 */
public final class PathResolver {

    private final Path userHome;
    private final Path projectRoot;

    /**
     * @param userHome    absolute path to the user's home directory;
     *                    required for USER-scope entries
     * @param projectRoot absolute path to the current project's root;
     *                    required for PROJECT-scope entries. May be
     *                    {@code null} when only USER-scope resolution
     *                    is expected (e.g. fresh-machine bootstrap).
     */
    public PathResolver(Path userHome, Path projectRoot) {
        this.userHome = Objects.requireNonNull(userHome, "userHome");
        this.projectRoot = projectRoot;
    }

    /**
     * Resolve a manifest entry to an absolute path.
     *
     * @param entry the manifest entry
     * @return absolute, normalised path
     * @throws ScaffoldException if the entry's scope does not match
     *                           the available roots or the dest form
     *                           is inconsistent with its scope
     */
    public Path resolve(ManifestEntry entry) {
        String dest = entry.dest();
        if (entry.scope() == ScaffoldScope.USER) {
            if (!dest.startsWith("~/")) {
                throw new ScaffoldException(
                        "USER-scope entry '" + dest
                                + "' must start with '~/'");
            }
            return userHome.resolve(dest.substring(2)).normalize();
        }
        // PROJECT
        if (dest.startsWith("~/")) {
            throw new ScaffoldException(
                    "PROJECT-scope entry '" + dest
                            + "' must not start with '~/'");
        }
        if (projectRoot == null) {
            throw new ScaffoldException(
                    "PROJECT-scope entry '" + dest
                            + "' needs a projectRoot but none was "
                            + "configured");
        }
        String rel = dest.startsWith("{project.root}/")
                ? dest.substring("{project.root}/".length())
                : dest;
        return projectRoot.resolve(rel).normalize();
    }
}

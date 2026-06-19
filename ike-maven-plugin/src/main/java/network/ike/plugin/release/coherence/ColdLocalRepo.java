package network.ike.plugin.release.coherence;

import org.apache.maven.api.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * A throwaway, empty Maven local repository and a {@link Session} bound
 * to it — the mechanism for <em>cold</em>, cache-less resolution
 * (IKE-Network/ike-issues#705).
 *
 * <p>Resolving against the normal local repository confirms nothing
 * about what consumers can fetch: the module's own {@code install}
 * trivially populated it, and it caches upstream metadata under a daily
 * update policy that can read stale (the failure that shipped the
 * incoherent ike-platform v110 on 2026-06-18). Pointing the session at
 * a fresh empty directory forces every resolution to hit the configured
 * remotes — with the session's credentials, proxies, and mirrors
 * preserved — so a successful resolve means the artifact (or fresh
 * metadata) is genuinely there.
 *
 * <p>Use in try-with-resources; {@link #close()} removes the temp
 * directory (best-effort).
 */
public final class ColdLocalRepo implements AutoCloseable {

    private final Path dir;

    /** The session whose local repository is the fresh empty directory. */
    public final Session session;

    /**
     * Creates a fresh empty local repository and a session bound to it.
     *
     * @param base the active session to derive remotes/credentials from
     * @throws IOException if the temp directory cannot be created
     */
    public ColdLocalRepo(Session base) throws IOException {
        this.dir = Files.createTempDirectory("ike-cold-m2-");
        this.session = base.withLocalRepository(base.createLocalRepository(dir));
    }

    /** Removes the throwaway local repository (best-effort). */
    @Override
    public void close() {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}

package network.ike.plugin.scaffold;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Find scaffold lockfile entries the current manifest no longer ships.
 *
 * <p>When a scaffold strategy is retired its template disappears from
 * {@code scaffold-manifest.yaml}. But a {@code dest} a prior
 * {@code scaffold-publish} already wrote stays in
 * {@code .ike/scaffold.lock} and on disk — {@link ScaffoldPlanner}
 * only iterates manifest entries, so an entry with no manifest
 * counterpart is silently ignored. This scanner flags those orphans
 * so {@code scaffold-publish} can clean them up.
 *
 * <p>Read-only: like {@link ScaffoldPlanner} the scanner reads disk to
 * decide a {@link OrphanEntry.Disposition} but never writes.
 */
public final class OrphanScanner {

    private OrphanScanner() {}

    /**
     * Scan one scope for orphaned lockfile entries.
     *
     * @param manifest the current scaffold manifest
     * @param lockfile the lockfile for this scope (project or user)
     * @param scope    the scope being scanned
     * @param resolver resolver for {@code dest} → absolute path
     * @return orphan entries in lockfile order; empty when none
     */
    public static List<OrphanEntry> scan(
            ScaffoldManifest manifest,
            ScaffoldLockfile lockfile,
            ScaffoldScope scope,
            PathResolver resolver) {

        Set<String> manifestDests = new HashSet<>();
        for (ManifestEntry e : manifest.entriesInScope(scope)) {
            manifestDests.add(e.dest());
        }

        List<OrphanEntry> orphans = new ArrayList<>();
        for (Map.Entry<String, LockfileEntry> le
                : lockfile.files().entrySet()) {
            String dest = le.getKey();
            if (manifestDests.contains(dest)) {
                continue; // still shipped — planned normally
            }
            if (!inScope(dest, scope)) {
                continue; // belongs to the other scope's lockfile
            }
            orphans.add(classify(dest, le.getValue(), scope, resolver));
        }
        return orphans;
    }

    /**
     * Whether a {@code dest} string belongs to the given scope. USER
     * entries carry the {@code ~/} prefix; PROJECT entries never do.
     */
    private static boolean inScope(String dest, ScaffoldScope scope) {
        boolean userForm = dest.startsWith("~/");
        return scope == ScaffoldScope.USER ? userForm : !userForm;
    }

    private static OrphanEntry classify(
            String dest,
            LockfileEntry prior,
            ScaffoldScope scope,
            PathResolver resolver) {

        Path resolved = resolver.resolveDest(dest, scope);
        ScaffoldTier tier = prior.tier();

        if (!Files.exists(resolved)) {
            return new OrphanEntry(dest, resolved, tier,
                    OrphanEntry.Disposition.ALREADY_ABSENT,
                    "file already absent — stale lockfile entry");
        }

        // Shared / user-owned tiers: removing the whole file would
        // destroy content the scaffold never owned. Mirror the
        // conservative stance ScaffoldReverter takes.
        if (tier == ScaffoldTier.TRACKED_BLOCK) {
            return new OrphanEntry(dest, resolved, tier,
                    OrphanEntry.Disposition.SKIP_USER_EDITED,
                    "tracked-block orphan — remove the managed block "
                            + "manually");
        }
        if (tier == ScaffoldTier.MODEL_MANAGED) {
            return new OrphanEntry(dest, resolved, tier,
                    OrphanEntry.Disposition.SKIP_USER_EDITED,
                    "model-managed orphan — remove managed elements "
                            + "manually");
        }

        String expected = prior.appliedSha() != null
                ? prior.appliedSha()
                : prior.templateSha();
        String current = Sha256.ofFile(resolved);
        if (expected != null && expected.equals(current)) {
            return new OrphanEntry(dest, resolved, tier,
                    OrphanEntry.Disposition.REMOVE,
                    "no longer shipped by the scaffold — will be "
                            + "deleted");
        }
        return new OrphanEntry(dest, resolved, tier,
                OrphanEntry.Disposition.SKIP_USER_EDITED,
                "no longer shipped, but edited since publish "
                        + "(expected " + expected + ", on disk "
                        + current + ") — leaving file as-is");
    }
}

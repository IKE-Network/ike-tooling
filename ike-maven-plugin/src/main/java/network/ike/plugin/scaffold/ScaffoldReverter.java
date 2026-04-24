package network.ike.plugin.scaffold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Undo a previous scaffold publish.
 *
 * <p>Revert semantics by tier:
 * <ul>
 *   <li>{@link ScaffoldTier#TOOL_OWNED}: delete the file if it still
 *       matches the applied hash, else warn and skip (unexpected
 *       divergence).</li>
 *   <li>{@link ScaffoldTier#TRACKED}: delete the file if it still
 *       matches {@code appliedSha}, else skip (user-edited).</li>
 *   <li>{@link ScaffoldTier#TRACKED_BLOCK}: not yet implemented —
 *       the reverter reports a skip with a message. A proper impl
 *       would remove just the managed block and leave the rest of
 *       the file.</li>
 *   <li>{@link ScaffoldTier#MODEL_MANAGED}: not yet implemented —
 *       proper impl would remove exactly the managed elements.</li>
 * </ul>
 *
 * <p>This class is deliberately conservative: anything the user might
 * have touched is left alone and reported, not destroyed.
 */
public final class ScaffoldReverter {

    private final Clock clock;

    /** Construct with {@link Clock#systemUTC()}. */
    public ScaffoldReverter() {
        this(Clock.systemUTC());
    }

    /**
     * @param clock clock for revert timestamps
     */
    public ScaffoldReverter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Revert every entry in {@code currentLockfile} that lives in
     * the given scope.
     *
     * @param currentLockfile the current lockfile
     * @param manifest        the manifest (used to look up each
     *                        entry's scope and tier)
     * @param scope           the scope to revert
     * @param pathResolver    path resolver
     * @return a {@link RevertResult} with the new lockfile (with
     *         reverted entries removed) and a per-entry report
     */
    public RevertResult revert(
            ScaffoldLockfile currentLockfile,
            ScaffoldManifest manifest,
            ScaffoldScope scope,
            PathResolver pathResolver) {
        List<Outcome> outcomes = new ArrayList<>();
        Map<String, LockfileEntry> remaining =
                new LinkedHashMap<>(currentLockfile.files());

        for (ManifestEntry entry : manifest.entriesInScope(scope)) {
            LockfileEntry prior = remaining.get(entry.dest());
            if (prior == null) {
                continue; // nothing to revert
            }
            Path dest = pathResolver.resolve(entry);
            Outcome o = revertOne(entry, dest, prior);
            outcomes.add(o);
            if (o.removedFromLockfile()) {
                remaining.remove(entry.dest());
            }
        }

        ScaffoldLockfile updated = new ScaffoldLockfile(
                ScaffoldLockfile.CURRENT_SCHEMA,
                manifest.standardsVersion(),
                Instant.now(clock),
                remaining);
        return new RevertResult(updated, outcomes);
    }

    private static Outcome revertOne(
            ManifestEntry entry, Path dest, LockfileEntry prior) {
        return switch (entry.tier()) {
            case TOOL_OWNED -> revertWholeFile(
                    entry, dest, prior, "tool-owned");
            case TRACKED -> revertWholeFile(
                    entry, dest, prior, "tracked");
            case TRACKED_BLOCK -> new Outcome(
                    entry.dest(),
                    Outcome.Kind.SKIPPED,
                    "tracked-block revert not yet implemented; "
                            + "remove the block between markers "
                            + "manually",
                    false);
            case MODEL_MANAGED -> new Outcome(
                    entry.dest(),
                    Outcome.Kind.SKIPPED,
                    "model-managed revert not yet implemented; "
                            + "remove managed elements manually",
                    false);
        };
    }

    private static Outcome revertWholeFile(
            ManifestEntry entry,
            Path dest,
            LockfileEntry prior,
            String tierLabel) {
        if (!Files.exists(dest)) {
            return new Outcome(
                    entry.dest(),
                    Outcome.Kind.REMOVED_FROM_LOCKFILE,
                    "file already absent",
                    true);
        }
        String currentSha = Sha256.ofFile(dest);
        String expected = prior.appliedSha() != null
                ? prior.appliedSha()
                : prior.templateSha();
        if (!expected.equals(currentSha)) {
            return new Outcome(
                    entry.dest(),
                    Outcome.Kind.SKIPPED,
                    tierLabel + " file edited since publish "
                            + "(expected " + expected
                            + ", on disk " + currentSha + "); "
                            + "leaving file as-is",
                    false);
        }
        try {
            Files.delete(dest);
        } catch (IOException e) {
            throw new ScaffoldException(
                    "cannot delete " + dest, e);
        }
        return new Outcome(
                entry.dest(),
                Outcome.Kind.DELETED,
                "deleted",
                true);
    }

    /**
     * Per-entry result of a revert.
     *
     * @param dest                  the manifest dest string
     * @param kind                  what happened to the entry
     * @param message               human-readable detail
     * @param removedFromLockfile   whether the lockfile entry was
     *                              dropped
     */
    public record Outcome(
            String dest, Kind kind, String message,
            boolean removedFromLockfile) {

        /** Kinds of outcome. */
        public enum Kind {
            /** File was deleted. */
            DELETED,
            /** File was already gone; lockfile entry dropped. */
            REMOVED_FROM_LOCKFILE,
            /** File was left alone (user-edited, or not supported). */
            SKIPPED
        }
    }

    /**
     * Aggregate revert result.
     *
     * @param updatedLockfile the new lockfile (with reverted entries
     *                        removed)
     * @param outcomes        per-entry outcomes
     */
    public record RevertResult(
            ScaffoldLockfile updatedLockfile,
            List<Outcome> outcomes) {

        /** Canonical constructor with defensive copying. */
        public RevertResult {
            outcomes = outcomes == null
                    ? List.of()
                    : List.copyOf(outcomes);
        }
    }
}

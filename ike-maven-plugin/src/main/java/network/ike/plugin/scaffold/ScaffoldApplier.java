package network.ike.plugin.scaffold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Execute a {@link ScaffoldPlan}: write out Write-actions to disk and
 * compose the updated {@link ScaffoldLockfile}.
 *
 * <p>Write actions are executed in order; the applier carefully
 * creates parent directories and uses
 * {@link StandardCopyOption#REPLACE_EXISTING} so existing files are
 * atomically replaced.
 *
 * <p>{@link TierAction.Skip} actions are recorded in the returned
 * lockfile as-is (the existing entry stays put). {@link TierAction.UpToDate}
 * and {@link TierAction.UserManaged} actions refresh the
 * {@code standards-version} on model-managed elements but are
 * otherwise no-ops.
 */
public final class ScaffoldApplier {

    private final Clock clock;

    /** Construct with {@link Clock#systemUTC()}. */
    public ScaffoldApplier() {
        this(Clock.systemUTC());
    }

    /**
     * Construct with an explicit clock. Tests supply a fixed clock so
     * the {@code generated-at} timestamp recorded in the updated
     * lockfile is deterministic.
     *
     * @param clock clock used for timestamps written into the lockfile
     */
    public ScaffoldApplier(Clock clock) {
        this.clock = clock;
    }

    /**
     * Carry out a plan.
     *
     * @param plan            the plan to execute
     * @param currentLockfile the current lockfile (so entries outside
     *                        the plan's scope are preserved verbatim)
     * @return the updated lockfile
     * @throws ScaffoldException if any Write fails
     */
    public ScaffoldLockfile apply(
            ScaffoldPlan plan,
            ScaffoldLockfile currentLockfile) {
        Map<String, LockfileEntry> files =
                new LinkedHashMap<>(currentLockfile.files());
        for (PlannedEntry pe : plan.entries()) {
            LockfileEntry updated = applyOne(pe);
            if (updated != null) {
                files.put(pe.manifest().dest(), updated);
            }
            // Skip actions retain whatever the lockfile already had.
        }
        return new ScaffoldLockfile(
                ScaffoldLockfile.CURRENT_SCHEMA,
                plan.manifestStandardsVersion(),
                Instant.now(clock),
                files);
    }

    /**
     * Remove orphaned scaffold files and drop their lockfile entries.
     *
     * <p>Run after {@link #apply} on the lockfile that {@code apply}
     * returned. For each {@link OrphanEntry.Disposition#REMOVE} orphan
     * the on-disk file is deleted; for {@code REMOVE} and
     * {@link OrphanEntry.Disposition#ALREADY_ABSENT} orphans the stale
     * lockfile entry is dropped. {@link OrphanEntry.Disposition#SKIP_USER_EDITED}
     * orphans are left entirely alone — file and lockfile entry both
     * stay, so the orphan keeps surfacing until the operator resolves
     * it.
     *
     * @param orphans  orphans found by {@link OrphanScanner}
     * @param lockfile the lockfile to prune (typically the result of
     *                 {@link #apply})
     * @return the lockfile with removed/absent orphan entries dropped
     * @throws ScaffoldException if a file deletion fails
     */
    public ScaffoldLockfile removeOrphans(
            List<OrphanEntry> orphans,
            ScaffoldLockfile lockfile) {
        ScaffoldLockfile result = lockfile;
        for (OrphanEntry orphan : orphans) {
            switch (orphan.disposition()) {
                case REMOVE -> {
                    deleteFile(orphan.resolvedDest());
                    result = result.withoutEntry(orphan.dest());
                }
                case ALREADY_ABSENT ->
                        result = result.withoutEntry(orphan.dest());
                case SKIP_USER_EDITED -> {
                    // Leave the file and the lockfile entry in place.
                }
            }
        }
        return result;
    }

    private static void deleteFile(Path dest) {
        try {
            Files.delete(dest);
        } catch (IOException e) {
            throw new ScaffoldException(
                    "cannot delete orphaned scaffold file " + dest, e);
        }
    }

    private LockfileEntry applyOne(PlannedEntry pe) {
        TierAction action = pe.action();
        if (action instanceof TierAction.Write w) {
            writeBytes(w.resolvedDest(), w.newContent());
            return lockfileEntryFor(pe, w.templateSha(), w.appliedSha());
        }
        if (action instanceof TierAction.UpToDate u) {
            // Refresh standards-version in the lockfile entry.
            return lockfileEntryFor(pe, u.templateSha(), u.appliedSha());
        }
        if (action instanceof TierAction.UserManaged m) {
            // No write; refresh lockfile provenance like UpToDate.
            return lockfileEntryFor(pe, m.templateSha(), m.appliedSha());
        }
        // Skip: leave lockfile unchanged.
        return null;
    }

    private static LockfileEntry lockfileEntryFor(
            PlannedEntry pe,
            String templateSha,
            String appliedSha) {
        ScaffoldTier tier = pe.manifest().tier();
        return switch (tier) {
            case TOOL_OWNED ->
                    LockfileEntry.toolOwned(templateSha);
            case TRACKED, TRACKED_BLOCK ->
                    LockfileEntry.tracked(
                            tier, templateSha, appliedSha);
            case MODEL_MANAGED ->
                    LockfileEntry.modelManaged(pe.managedElements());
        };
    }

    private static void writeBytes(Path dest, byte[] content) {
        try {
            Path parent = dest.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(
                    parent == null ? dest.toAbsolutePath().getParent()
                            : parent,
                    dest.getFileName().toString(), ".tmp");
            try {
                Files.write(tmp, content);
                Files.move(tmp, dest,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // ignore — best-effort cleanup
                }
                throw e;
            }
        } catch (IOException e) {
            throw new ScaffoldException(
                    "cannot write " + dest, e);
        }
    }
}

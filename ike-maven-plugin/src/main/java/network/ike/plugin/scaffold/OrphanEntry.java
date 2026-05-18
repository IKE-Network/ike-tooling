package network.ike.plugin.scaffold;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A scaffold file recorded in {@code .ike/scaffold.lock} whose
 * {@code dest} the current manifest no longer ships — the file was
 * installed by a {@code scaffold-publish} run under a scaffold
 * strategy that has since been retired.
 *
 * <p>{@link ScaffoldPlanner} only iterates manifest entries, so a
 * lockfile entry with no manifest counterpart would otherwise stay on
 * disk and in the lockfile forever. {@link OrphanScanner} finds these;
 * {@code ike:scaffold-draft} reports them and {@code ike:scaffold-publish}
 * removes them.
 *
 * @param dest         the lockfile/manifest {@code dest} string
 * @param resolvedDest absolute on-disk path the {@code dest} expands to
 * @param tier         the ownership tier recorded in the lockfile
 * @param disposition  what {@code scaffold-publish} will do with it
 * @param reason       human-readable detail for draft/publish output
 */
public record OrphanEntry(
        String dest,
        Path resolvedDest,
        ScaffoldTier tier,
        Disposition disposition,
        String reason) {

    /** Canonical constructor with null guards. */
    public OrphanEntry {
        Objects.requireNonNull(dest, "dest");
        Objects.requireNonNull(resolvedDest, "resolvedDest");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(reason, "reason");
    }

    /** What {@code scaffold-publish} will do with an orphan. */
    public enum Disposition {
        /**
         * On-disk content still matches the lockfile hash — publish
         * deletes the file and drops the lockfile entry.
         */
        REMOVE,
        /**
         * File diverged from the lockfile hash, or its tier is shared
         * ({@code tracked-block}) or user-owned ({@code model-managed}).
         * Publish leaves the file untouched and keeps the lockfile
         * entry so the orphan keeps surfacing until resolved.
         */
        SKIP_USER_EDITED,
        /**
         * File is already gone — publish just drops the stale lockfile
         * entry.
         */
        ALREADY_ABSENT
    }
}

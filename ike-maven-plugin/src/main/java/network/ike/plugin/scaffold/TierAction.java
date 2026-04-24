package network.ike.plugin.scaffold;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A plan-time decision about what to do with a single scaffold
 * entry. Produced by a {@link TierHandler} from a (manifest entry,
 * current disk state, template bytes, lockfile entry) tuple and
 * consumed by the scaffold applier.
 *
 * <p>The sealed hierarchy gives the applier exhaustive pattern
 * matching and keeps the planner pure — no tier handler touches
 * disk during planning.
 */
public sealed interface TierAction
        permits TierAction.Write,
                TierAction.Skip,
                TierAction.UpToDate {

    /** The manifest entry this action relates to. */
    ManifestEntry entry();

    /**
     * The absolute destination path on disk, with any {@code ~/}
     * or {@code {project.root}/} placeholders already expanded.
     */
    Path resolvedDest();

    /**
     * Human-readable summary rendered in {@code scaffold-draft}
     * output. One line per entry; may include counts or diff hints.
     */
    String reason();

    // ── Subtypes ────────────────────────────────────────────────────

    /**
     * Publish should write {@code newContent} to {@link #resolvedDest()}.
     *
     * @param entry        the manifest entry
     * @param resolvedDest absolute destination path
     * @param newContent   bytes to write (full file content — for
     *                     tracked-block this is the fully-rendered
     *                     combined file, not just the managed block)
     * @param appliedSha   hash of {@code newContent} (for the lockfile
     *                     update; for tracked-block this is the hash
     *                     of just the managed block)
     * @param templateSha  hash of the unbounded template source (used
     *                     for drift telemetry in the new lockfile
     *                     entry)
     * @param kind         whether the file existed before this write
     * @param reason       draft-output summary
     */
    record Write(
            ManifestEntry entry,
            Path resolvedDest,
            byte[] newContent,
            String appliedSha,
            String templateSha,
            Kind kind,
            String reason) implements TierAction {

        /** Compact constructor validating required fields. */
        public Write {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(resolvedDest, "resolvedDest");
            Objects.requireNonNull(newContent, "newContent");
            Objects.requireNonNull(appliedSha, "appliedSha");
            Objects.requireNonNull(templateSha, "templateSha");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(reason, "reason");
            newContent = newContent.clone();
        }

        /** Defensive copy of the content bytes. */
        @Override
        public byte[] newContent() {
            return newContent.clone();
        }

        /** Category of write, for draft output and ordering. */
        public enum Kind {
            /** Target did not exist before this write. */
            INSTALL,
            /** Target existed and was safe to update. */
            UPDATE,
            /** Target is being restored by {@code scaffold-revert}. */
            REVERT
        }
    }

    /**
     * Publish must not touch {@link #resolvedDest()} because the user
     * has diverged from the last-applied version. Draft output carries
     * a textual diff so the user can decide.
     *
     * @param entry        the manifest entry
     * @param resolvedDest absolute destination path
     * @param reason       short summary (e.g. "user-edited; +3/-1")
     * @param diff         multi-line textual diff for draft output;
     *                     may be empty for tiers that don't render
     *                     diffs
     */
    record Skip(
            ManifestEntry entry,
            Path resolvedDest,
            String reason,
            String diff) implements TierAction {

        /** Compact constructor validating required fields. */
        public Skip {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(resolvedDest, "resolvedDest");
            Objects.requireNonNull(reason, "reason");
            diff = diff == null ? "" : diff;
        }
    }

    /**
     * Nothing to write — target already matches the current template.
     * The scaffold applier may still refresh the lockfile entry so
     * telemetry stays current with the new {@code standards-version}.
     *
     * @param entry        the manifest entry
     * @param resolvedDest absolute destination path
     * @param templateSha  hash of the current template (for lockfile
     *                     metadata refresh)
     * @param appliedSha   hash currently on disk — typically equal to
     *                     {@code templateSha} for tool-owned and
     *                     tracked, or the hash of the managed block
     *                     for tracked-block
     * @param reason       draft-output summary
     */
    record UpToDate(
            ManifestEntry entry,
            Path resolvedDest,
            String templateSha,
            String appliedSha,
            String reason) implements TierAction {

        /** Compact constructor validating required fields. */
        public UpToDate {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(resolvedDest, "resolvedDest");
            Objects.requireNonNull(templateSha, "templateSha");
            Objects.requireNonNull(appliedSha, "appliedSha");
            Objects.requireNonNull(reason, "reason");
        }
    }
}

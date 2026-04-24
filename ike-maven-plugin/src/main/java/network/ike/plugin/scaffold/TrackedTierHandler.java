package network.ike.plugin.scaffold;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Tier handler for {@link ScaffoldTier#TRACKED}.
 *
 * <p>Policy: checksum-guarded whole-file management. Publish refreshes
 * the file only when the on-disk content matches the lockfile's
 * {@code applied-sha} from the last publish. If the user has edited
 * the file, publish skips it and draft output surfaces a textual diff
 * so the user can merge manually.
 *
 * <p>Produces:
 * <ul>
 *   <li>{@link TierAction.UpToDate} when bytes equal the template;</li>
 *   <li>{@link TierAction.Write} with {@code INSTALL} when the file is
 *       absent and no prior entry exists;</li>
 *   <li>{@link TierAction.Write} with {@code UPDATE} when the file
 *       matches the prior {@code applied-sha} (safe refresh);</li>
 *   <li>{@link TierAction.Skip} when the file diverges from both the
 *       prior {@code applied-sha} and the new template.</li>
 * </ul>
 */
public final class TrackedTierHandler implements TierHandler {

    /**
     * Construct a stateless tracked tier handler. Instances are safe
     * to share across planning calls; all per-invocation state lives
     * on method parameters.
     */
    public TrackedTierHandler() {
    }

    @Override
    public ScaffoldTier tier() {
        return ScaffoldTier.TRACKED;
    }

    @Override
    public TierAction plan(
            ManifestEntry entry,
            Path resolvedDest,
            byte[] currentContent,
            byte[] templateContent,
            LockfileEntry priorEntry) {
        if (templateContent == null) {
            throw new ScaffoldException(
                    "tracked entry '" + entry.dest()
                            + "' has no template content");
        }

        String templateSha = Sha256.of(templateContent);

        // Absent on disk: install, unless we had a prior entry and the
        // user has deliberately removed the file — but in that case we
        // still install (publish is idempotent) and let the user delete
        // again if they really meant it.
        if (currentContent == null) {
            return new TierAction.Write(
                    entry, resolvedDest, templateContent,
                    templateSha, templateSha,
                    TierAction.Write.Kind.INSTALL, "install");
        }

        // Already matches the template — nothing to do.
        if (Arrays.equals(currentContent, templateContent)) {
            return new TierAction.UpToDate(
                    entry, resolvedDest, templateSha, templateSha,
                    "up to date");
        }

        String currentSha = Sha256.of(currentContent);

        // First ever publish (no prior entry): treat the existing file
        // as user content and skip.
        if (priorEntry == null) {
            return skipDiverged(
                    entry, resolvedDest, currentContent, templateContent,
                    "no prior lockfile entry");
        }

        // Safe refresh: disk still matches what we wrote last time.
        if (priorEntry.appliedSha() != null
                && priorEntry.appliedSha().equals(currentSha)) {
            LineDiff.Counts c =
                    LineDiff.counts(str(currentContent),
                            str(templateContent));
            return new TierAction.Write(
                    entry, resolvedDest, templateContent,
                    templateSha, templateSha,
                    TierAction.Write.Kind.UPDATE,
                    "refresh (" + c.shortForm() + ")");
        }

        // User has edited the file since last publish — skip with diff.
        return skipDiverged(
                entry, resolvedDest, currentContent, templateContent,
                "user-edited");
    }

    private static TierAction.Skip skipDiverged(
            ManifestEntry entry,
            Path resolvedDest,
            byte[] currentContent,
            byte[] templateContent,
            String why) {
        String from = str(currentContent);
        String to = str(templateContent);
        LineDiff.Counts c = LineDiff.counts(from, to);
        String diff = LineDiff.unified(from, to);
        return new TierAction.Skip(
                entry, resolvedDest,
                why + "; " + c.shortForm(),
                diff);
    }

    private static String str(byte[] bytes) {
        return bytes == null
                ? ""
                : new String(bytes, StandardCharsets.UTF_8);
    }
}

package network.ike.plugin.scaffold;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Tier handler for {@link ScaffoldTier#TOOL_OWNED}.
 *
 * <p>Policy: publish always overwrites. The file is intended to be
 * reproduced verbatim from the template; any user edits are discarded.
 * Divergence is surfaced in draft output as telemetry but never blocks
 * publish.
 *
 * <p>Produces:
 * <ul>
 *   <li>{@link TierAction.UpToDate} when the on-disk bytes already
 *       match the template;</li>
 *   <li>{@link TierAction.Write} with
 *       {@link TierAction.Write.Kind#INSTALL INSTALL} when the file is
 *       absent;</li>
 *   <li>{@link TierAction.Write} with
 *       {@link TierAction.Write.Kind#UPDATE UPDATE} otherwise.</li>
 * </ul>
 */
public final class ToolOwnedTierHandler implements TierHandler {

    @Override
    public ScaffoldTier tier() {
        return ScaffoldTier.TOOL_OWNED;
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
                    "tool-owned entry '" + entry.dest()
                            + "' has no template content");
        }

        String templateSha = Sha256.of(templateContent);

        if (currentContent != null
                && Arrays.equals(currentContent, templateContent)) {
            return new TierAction.UpToDate(
                    entry, resolvedDest, templateSha, templateSha,
                    "up to date");
        }

        TierAction.Write.Kind kind = currentContent == null
                ? TierAction.Write.Kind.INSTALL
                : TierAction.Write.Kind.UPDATE;
        String reason = switch (kind) {
            case INSTALL -> "install";
            case UPDATE -> currentContent != null
                    && priorEntry != null
                    && !Sha256.of(currentContent)
                            .equals(priorEntry.appliedSha() != null
                                    ? priorEntry.appliedSha()
                                    : priorEntry.templateSha())
                    ? "overwrite (user-edited; tool-owned)"
                    : "refresh";
            case REVERT -> "revert";
        };
        return new TierAction.Write(
                entry, resolvedDest, templateContent,
                templateSha, templateSha, kind, reason);
    }
}

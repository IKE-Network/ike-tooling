package network.ike.plugin.scaffold;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tier handler for {@link ScaffoldTier#TRACKED_BLOCK}.
 *
 * <p>Policy: same checksum-guarded refresh as
 * {@link ScaffoldTier#TRACKED}, but only a bounded region inside the
 * destination file is managed. The region is delimited by the
 * {@code block-begin} and {@code block-end} line markers declared on
 * the manifest entry. Content outside the markers belongs to the user
 * and is never touched.
 *
 * <p>The {@code source} file in the scaffold zip contains only the
 * block content (no markers). When publish installs a brand-new file,
 * the markers are added around the template content.
 *
 * <p>The {@code appliedSha} / {@code templateSha} recorded in the
 * lockfile for this tier are hashes of the block <em>content</em>, not
 * the whole file. That way edits to unmanaged regions do not look like
 * drift on the managed block.
 *
 * <h2>Whitelist-style {@code .gitignore} awareness</h2>
 *
 * <p>Workspaces that ignore everything by default and whitelist a
 * curated set of files (signalled by a bare {@code *} or {@code **}
 * line in the destination) need a different block payload — the
 * blacklist patterns shipped in {@code source} would either silently
 * have no effect or, worse, cause newly tracked scaffold files to be
 * ignored. When the manifest entry sets
 * {@code whitelist-block-content} (an inline string in extras) the
 * handler substitutes that content for the {@code source} bytes when
 * whitelist mode is detected on disk. When whitelist mode is detected
 * but no {@code whitelist-block-content} is supplied, the entry is
 * reported as user-managed (no write) so the publish run does not
 * pollute the user's whitelist.
 */
public final class TrackedBlockTierHandler implements TierHandler {

    /**
     * Construct a stateless tracked-block tier handler. Instances are
     * safe to share across planning calls; all per-invocation state
     * lives on method parameters.
     */
    public TrackedBlockTierHandler() {
    }

    @Override
    public ScaffoldTier tier() {
        return ScaffoldTier.TRACKED_BLOCK;
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
                    "tracked-block entry '" + entry.dest()
                            + "' has no template content");
        }
        String beginMarker = stringExtra(entry, "block-begin");
        String endMarker = stringExtra(entry, "block-end");
        if (beginMarker == null || beginMarker.isBlank()) {
            throw new ScaffoldException(
                    "tracked-block entry '" + entry.dest()
                            + "' missing 'block-begin'");
        }
        if (endMarker == null || endMarker.isBlank()) {
            throw new ScaffoldException(
                    "tracked-block entry '" + entry.dest()
                            + "' missing 'block-end'");
        }

        String currentStr = str(currentContent);

        // Whitelist-mode awareness: when the destination is a
        // whitelist-style ignore file (catch-all `*`/`**` line
        // present), the blacklist patterns shipped in `source` would
        // either be no-ops or silently mask scaffold-tracked files.
        // Substitute `whitelist-block-content` from extras when
        // supplied; otherwise leave the file alone and report as
        // user-managed.
        boolean whitelistMode = currentContent != null
                && isWhitelistIgnoreFile(currentStr);
        String whitelistContent =
                stringExtra(entry, "whitelist-block-content");
        if (whitelistMode && (whitelistContent == null
                || whitelistContent.isBlank())) {
            return new TierAction.Skip(
                    entry, resolvedDest,
                    "whitelist-style ignore file; no "
                            + "'whitelist-block-content' supplied — "
                            + "leaving file alone",
                    "");
        }

        String templateBlock = whitelistMode
                ? whitelistContent
                : str(templateContent);
        String templateBlockSha = Sha256.of(templateBlock);

        // Case 1: file does not exist yet — create it with just the
        // managed block.
        if (currentContent == null) {
            String newFile = renderBlock(
                    beginMarker, templateBlock, endMarker);
            byte[] out = newFile.getBytes(StandardCharsets.UTF_8);
            return new TierAction.Write(
                    entry, resolvedDest, out,
                    templateBlockSha, templateBlockSha,
                    TierAction.Write.Kind.INSTALL,
                    "install new file with managed block");
        }
        BlockSlice slice =
                locate(currentStr, beginMarker, endMarker, entry);

        // Case 2: block absent in an existing file — append one.
        if (slice == null) {
            String newFile = currentStr
                    + (currentStr.isEmpty() || currentStr.endsWith("\n")
                            ? ""
                            : "\n")
                    + renderBlock(
                            beginMarker, templateBlock, endMarker);
            byte[] out = newFile.getBytes(StandardCharsets.UTF_8);
            return new TierAction.Write(
                    entry, resolvedDest, out,
                    templateBlockSha, templateBlockSha,
                    TierAction.Write.Kind.INSTALL,
                    "append managed block");
        }

        String currentBlock = slice.content();
        String currentBlockSha = Sha256.of(currentBlock);

        // Case 3: block already matches the template.
        if (currentBlock.equals(templateBlock)) {
            return new TierAction.UpToDate(
                    entry, resolvedDest,
                    templateBlockSha, currentBlockSha,
                    "up to date");
        }

        // Case 4: safe refresh — block still matches what we last wrote.
        if (priorEntry != null
                && priorEntry.appliedSha() != null
                && priorEntry.appliedSha().equals(currentBlockSha)) {
            String newFile = slice.before()
                    + renderBlock(
                            beginMarker, templateBlock, endMarker)
                    + slice.after();
            byte[] out = newFile.getBytes(StandardCharsets.UTF_8);
            LineDiff.Counts c = LineDiff.counts(
                    currentBlock, templateBlock);
            return new TierAction.Write(
                    entry, resolvedDest, out,
                    templateBlockSha, templateBlockSha,
                    TierAction.Write.Kind.UPDATE,
                    "refresh block (" + c.shortForm() + ")");
        }

        // Case 5: user edited the block — skip with a block-level diff.
        LineDiff.Counts c = LineDiff.counts(
                currentBlock, templateBlock);
        String diff = LineDiff.unified(currentBlock, templateBlock);
        return new TierAction.Skip(
                entry, resolvedDest,
                (priorEntry == null
                        ? "no prior lockfile entry"
                        : "user-edited block")
                        + "; " + c.shortForm(),
                diff);
    }

    // ── helpers ────────────────────────────────────────────────────

    /**
     * Detect whether an ignore-style file uses the whitelist
     * convention: a top-level catch-all (a bare {@code *} or
     * {@code **} line) that ignores everything by default, with
     * {@code !pattern} entries to selectively whitelist files.
     *
     * <p>Comment and blank lines are ignored. The check is intentionally
     * narrow — only a bare {@code *}/{@code **} (no surrounding text)
     * counts. Files with patterns like {@code *.iml} or {@code build/*}
     * are blacklist-mode and remain unaffected.
     *
     * @param content the file content as text; may be empty but not null
     * @return {@code true} if the file matches the whitelist convention
     */
    static boolean isWhitelistIgnoreFile(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        for (String raw : content.split("\n")) {
            String t = stripLineEnding(raw).trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.equals("*") || t.equals("**")) {
                return true;
            }
        }
        return false;
    }

    /** A parsed managed-block slice within a larger file. */
    private record BlockSlice(
            String before, String content, String after) {
    }

    private static BlockSlice locate(
            String text, String begin, String end, ManifestEntry entry) {
        List<String> lines = splitPreservingLineEndings(text);
        int beginIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            String trimmedLine = stripLineEnding(rawLine);
            if (beginIdx < 0 && trimmedLine.equals(begin)) {
                beginIdx = i;
            } else if (beginIdx >= 0 && trimmedLine.equals(end)) {
                endIdx = i;
                break;
            }
        }
        if (beginIdx < 0 && endIdx < 0) {
            return null;
        }
        if (beginIdx >= 0 && endIdx < 0) {
            throw new ScaffoldException(
                    "tracked-block entry '" + entry.dest()
                            + "': found '" + begin
                            + "' but no matching '" + end + "'");
        }
        // Check for duplicate begin markers after the first block.
        for (int i = endIdx + 1; i < lines.size(); i++) {
            if (stripLineEnding(lines.get(i)).equals(begin)) {
                throw new ScaffoldException(
                        "tracked-block entry '" + entry.dest()
                                + "': multiple '" + begin
                                + "' markers found");
            }
        }
        // `before` and `after` exclude the marker lines themselves —
        // the caller will re-emit markers via renderBlock().
        StringBuilder before = new StringBuilder();
        for (int i = 0; i < beginIdx; i++) {
            before.append(lines.get(i));
        }
        StringBuilder content = new StringBuilder();
        for (int i = beginIdx + 1; i < endIdx; i++) {
            content.append(lines.get(i));
        }
        StringBuilder after = new StringBuilder();
        for (int i = endIdx + 1; i < lines.size(); i++) {
            after.append(lines.get(i));
        }
        return new BlockSlice(
                before.toString(),
                content.toString(),
                after.toString());
    }

    private static String renderBlock(
            String begin, String content, String end) {
        StringBuilder sb = new StringBuilder();
        sb.append(begin).append('\n');
        sb.append(content);
        if (!content.isEmpty() && !content.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append(end).append('\n');
        return sb.toString();
    }

    /**
     * Split like {@code text.lines()} but preserve the newline at the
     * end of each produced line, so joining the pieces reproduces the
     * original string byte-for-byte.
     */
    private static List<String> splitPreservingLineEndings(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        int start = 0;
        for (int k = 0; k < s.length(); k++) {
            if (s.charAt(k) == '\n') {
                out.add(s.substring(start, k + 1));
                start = k + 1;
            }
        }
        if (start < s.length()) {
            out.add(s.substring(start));
        }
        return out;
    }

    private static String stripLineEnding(String s) {
        if (s.endsWith("\r\n")) {
            return s.substring(0, s.length() - 2);
        }
        if (s.endsWith("\n")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String stringExtra(
            ManifestEntry entry, String key) {
        Object v = entry.extras().get(key);
        return v == null ? null : v.toString();
    }

    private static String str(byte[] bytes) {
        return bytes == null
                ? ""
                : new String(bytes, StandardCharsets.UTF_8);
    }
}

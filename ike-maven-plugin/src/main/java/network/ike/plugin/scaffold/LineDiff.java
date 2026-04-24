package network.ike.plugin.scaffold;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal LCS-based line differ used by tracked and tracked-block
 * tier handlers to build human-readable diff output for
 * {@code scaffold-draft}.
 *
 * <p>This is not a full unified-diff implementation — we only emit the
 * prefixed lines ({@code ' '}, {@code '-'}, {@code '+'}) without hunk
 * headers, which is plenty for draft output where the file path is
 * already printed separately.
 *
 * <p>Both inputs are treated as UTF-8 text and split on LF. A trailing
 * newline is normalised away so {@code "a\n"} and {@code "a"} produce
 * identical line lists — callers that care about trailing-newline
 * differences should compare raw bytes.
 */
public final class LineDiff {

    private LineDiff() {}

    /**
     * Count of {@code '+'} and {@code '-'} lines between two texts.
     *
     * @param from the baseline text
     * @param to   the new text
     * @return a {@link Counts} record
     */
    public static Counts counts(String from, String to) {
        List<String> a = lines(from);
        List<String> b = lines(to);
        int[][] dp = lcsTable(a, b);
        int added = 0;
        int removed = 0;
        int i = 0;
        int j = 0;
        int m = a.size();
        int n = b.size();
        while (i < m && j < n) {
            if (a.get(i).equals(b.get(j))) {
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                removed++;
                i++;
            } else {
                added++;
                j++;
            }
        }
        removed += m - i;
        added += n - j;
        return new Counts(added, removed);
    }

    /**
     * A line-prefixed diff: every line in the union starts with one of
     * {@code ' '}, {@code '-'}, or {@code '+'}. Empty output means the
     * texts are identical.
     *
     * @param from the baseline text
     * @param to   the new text
     * @return a newline-terminated, prefixed diff
     */
    public static String unified(String from, String to) {
        List<String> a = lines(from);
        List<String> b = lines(to);
        int[][] dp = lcsTable(a, b);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int j = 0;
        int m = a.size();
        int n = b.size();
        while (i < m && j < n) {
            if (a.get(i).equals(b.get(j))) {
                sb.append(' ').append(a.get(i)).append('\n');
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                sb.append('-').append(a.get(i)).append('\n');
                i++;
            } else {
                sb.append('+').append(b.get(j)).append('\n');
                j++;
            }
        }
        while (i < m) {
            sb.append('-').append(a.get(i++)).append('\n');
        }
        while (j < n) {
            sb.append('+').append(b.get(j++)).append('\n');
        }
        return sb.toString();
    }

    private static int[][] lcsTable(List<String> a, List<String> b) {
        int m = a.size();
        int n = b.size();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (a.get(i).equals(b.get(j))) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] =
                            Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        return dp;
    }

    private static List<String> lines(String s) {
        if (s == null || s.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int k = 0; k < s.length(); k++) {
            if (s.charAt(k) == '\n') {
                out.add(s.substring(start, k));
                start = k + 1;
            }
        }
        if (start < s.length()) {
            out.add(s.substring(start));
        }
        return out;
    }

    /**
     * Added/removed line counts.
     *
     * @param added   number of {@code '+'} lines
     * @param removed number of {@code '-'} lines
     */
    public record Counts(int added, int removed) {

        /**
         * Compact {@code "+N/-M"} summary suitable for one-line output.
         *
         * @return the summary string
         */
        public String shortForm() {
            return "+" + added + "/-" + removed;
        }
    }
}

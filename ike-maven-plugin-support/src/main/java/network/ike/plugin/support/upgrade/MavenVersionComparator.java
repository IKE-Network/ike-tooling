package network.ike.plugin.support.upgrade;

import java.util.Comparator;

/**
 * Comparator that orders Maven version strings the same way Maven 4's
 * {@code DefaultArtifactVersion} does — broadly: split on
 * {@code .}/{@code -}/{@code _}/digit-letter transitions, compare
 * numeric segments numerically, alpha segments lexicographically with
 * a few well-known qualifier ranks ({@code alpha < beta < milestone <
 * rc < snapshot < (release) < sp}).
 *
 * <p>This is a pragmatic re-implementation, not a full clone of
 * Maven's {@code ComparableVersion}. It handles the kinds of versions
 * IKE actually publishes (single-segment integer like {@code "127"};
 * dotted like {@code "1.59.0"}; SNAPSHOT-suffixed like
 * {@code "128-SNAPSHOT"}) plus the common third-party patterns we
 * regularly upgrade against (e.g., JUnit
 * {@code "5.11.4"}, asciidoctorj {@code "3.0.1"}). Using a hand-rolled
 * comparator avoids forcing every caller of
 * {@link CandidateVersionResolver} to depend on {@code maven-artifact}.
 */
public final class MavenVersionComparator implements Comparator<String> {

    /** Shared instance. */
    public static final MavenVersionComparator INSTANCE =
            new MavenVersionComparator();

    private MavenVersionComparator() {}

    @Override
    public int compare(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        String[] aSegs = split(a);
        String[] bSegs = split(b);
        int len = Math.max(aSegs.length, bSegs.length);
        for (int i = 0; i < len; i++) {
            String aSeg = i < aSegs.length ? aSegs[i] : "";
            String bSeg = i < bSegs.length ? bSegs[i] : "";
            int c = compareSegment(aSeg, bSeg);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /**
     * Split a version string into comparable segments. Splits on
     * {@code .}, {@code -}, and {@code _}; also inserts a split at
     * each digit/letter transition so {@code "1rc1"} becomes
     * {@code ["1","rc","1"]}.
     *
     * @param v version string
     * @return segments in order
     */
    static String[] split(String v) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean lastDigit = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '.' || c == '-' || c == '_') {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                lastDigit = false;
                continue;
            }
            boolean isDigit = Character.isDigit(c);
            if (current.length() > 0 && isDigit != lastDigit) {
                out.add(current.toString());
                current.setLength(0);
            }
            current.append(c);
            lastDigit = isDigit;
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out.toArray(new String[0]);
    }

    private static int compareSegment(String a, String b) {
        boolean aDigit = isAllDigits(a);
        boolean bDigit = isAllDigits(b);
        if (aDigit && bDigit) {
            // Numeric segments compare numerically. Strip leading
            // zeros to avoid Long overflow on absurd inputs; we don't
            // expect huge segments anyway.
            return compareNumeric(a, b);
        }
        if (aDigit) {
            // Numeric beats alpha (1.0 > 1.0-alpha)
            return 1;
        }
        if (bDigit) {
            return -1;
        }
        return Integer.compare(qualifierRank(a), qualifierRank(b));
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int compareNumeric(String a, String b) {
        // Strip leading zeros; compare by length then char-by-char.
        String an = a.replaceFirst("^0+(?!$)", "");
        String bn = b.replaceFirst("^0+(?!$)", "");
        if (an.length() != bn.length()) {
            return Integer.compare(an.length(), bn.length());
        }
        return an.compareTo(bn);
    }

    /**
     * Rank known qualifiers so {@code alpha < beta < milestone < rc <
     * snapshot < (anything else, including release names) < sp}. This
     * matches the practical ordering Maven uses for these qualifiers.
     *
     * @param qualifier qualifier string (case-insensitive)
     * @return ordering rank (lower comes first)
     */
    private static int qualifierRank(String qualifier) {
        String q = qualifier.toLowerCase(java.util.Locale.ROOT);
        return switch (q) {
            case "a", "alpha" -> 1;
            case "b", "beta" -> 2;
            case "m", "milestone" -> 3;
            case "rc", "cr" -> 4;
            case "snapshot" -> 5;
            case "ga", "final", "release", "" -> 6;
            case "sp" -> 7;
            default -> 6; // unknown alpha tags compare equal to release
        };
    }
}

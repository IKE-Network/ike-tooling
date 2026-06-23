package network.ike.plugin.scaffold;

import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Filesystem mode a scaffold-written file should carry on disk.
 *
 * <p>Declared per manifest entry via the optional {@code mode} key and
 * applied by {@link ScaffoldApplier} on POSIX filesystems. Three named
 * modes cover every scaffold file the manifest ships:
 *
 * <ul>
 *   <li>{@link #DEFAULT} (0644) — ordinary tracked / tool-owned files;
 *       the implicit mode when {@code mode} is absent.</li>
 *   <li>{@link #EXECUTABLE} (0755) — scripts that must run, e.g.
 *       {@code mvnw}. Without this the applier would publish them
 *       non-executable (every write goes through a 0600 temp file) and
 *       {@code ./mvnw} would fail.</li>
 *   <li>{@link #PRIVATE} (0600) — owner-only, intentionally
 *       non-executable files, e.g. the parked VCS-bridge git hooks.</li>
 * </ul>
 *
 * <p>The string values are the {@code mode:} tokens authors write in
 * {@code scaffold-manifest.yaml}; {@link #fromManifest(Object)} parses
 * them once at apply time, turning the YAML string into this enum so
 * the rest of the applier reasons over a type rather than a raw mode.
 */
public enum FileMode {

    /** 0644 — owner read/write, group/other read. The implicit default. */
    DEFAULT("default", 0644),

    /** 0755 — adds the executable bit for all; for runnable scripts. */
    EXECUTABLE("executable", 0755),

    /** 0600 — owner read/write only; non-executable and owner-private. */
    PRIVATE("private", 0600);

    private final String manifestValue;
    private final int octal;

    FileMode(String manifestValue, int octal) {
        this.manifestValue = manifestValue;
        this.octal = octal;
    }

    /**
     * The token used in a manifest entry's {@code mode} field.
     *
     * @return the lowercase manifest token (e.g. {@code "executable"})
     */
    public String manifestValue() {
        return manifestValue;
    }

    /**
     * The POSIX mode bits this mode maps to.
     *
     * @return octal permission bits (e.g. {@code 0755})
     */
    public int octal() {
        return octal;
    }

    /**
     * Whether this mode was explicitly requested (anything other than
     * {@link #DEFAULT}). Explicit modes are enforced on every write;
     * {@code DEFAULT} preserves a pre-existing file's permissions on
     * update so the applier never loosens, say, a locked-down
     * {@code ~/.m2/settings.xml}.
     *
     * @return {@code true} for {@link #EXECUTABLE} and {@link #PRIVATE}
     */
    public boolean isExplicit() {
        return this != DEFAULT;
    }

    /**
     * Resolve a manifest {@code mode} value to a {@link FileMode}.
     *
     * @param raw the raw YAML value of the entry's {@code mode} key;
     *            {@code null} or blank yields {@link #DEFAULT}
     * @return the matching mode
     * @throws ScaffoldException if {@code raw} is a non-blank value that
     *                           names no known mode
     */
    public static FileMode fromManifest(Object raw) {
        if (raw == null) {
            return DEFAULT;
        }
        String token = raw.toString().trim();
        if (token.isEmpty()) {
            return DEFAULT;
        }
        for (FileMode mode : values()) {
            if (mode.manifestValue.equalsIgnoreCase(token)) {
                return mode;
            }
        }
        throw new ScaffoldException(
                "unknown file mode '" + token
                        + "' (expected one of: default, executable, "
                        + "private)");
    }

    /**
     * Expand this mode's octal bits into a {@link PosixFilePermission}
     * set suitable for {@link java.nio.file.Files#setPosixFilePermissions}.
     *
     * @return a fresh, mutable permission set
     */
    public Set<PosixFilePermission> toPosixPermissions() {
        Set<PosixFilePermission> perms =
                EnumSet.noneOf(PosixFilePermission.class);
        if ((octal & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
        if ((octal & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
        if ((octal & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
        if ((octal & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
        if ((octal & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
        if ((octal & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
        if ((octal & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
        if ((octal & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
        if ((octal & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
        return perms;
    }
}

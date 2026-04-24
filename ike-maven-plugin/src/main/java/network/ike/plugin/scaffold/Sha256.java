package network.ike.plugin.scaffold;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hash utility for scaffold checksums.
 *
 * <p>Produces values in the canonical lockfile form
 * {@code "sha256:" + lowercase-hex-digest}. All scaffold lockfile and
 * manifest hashes must flow through this class so the algorithm label
 * stays consistent.
 *
 * <p>Hashes are computed over the raw byte content of the file or
 * string. Consumers that need line-ending normalisation must do so
 * before calling this class.
 */
public final class Sha256 {

    /** Prefix distinguishing this algorithm in a lockfile value. */
    public static final String PREFIX = "sha256:";

    private Sha256() {}

    /**
     * Hash a byte array.
     *
     * @param data the bytes to hash; {@code null} is treated as empty
     * @return {@code "sha256:" + hex-digest}
     */
    public static String of(byte[] data) {
        MessageDigest md = newDigest();
        md.update(data == null ? new byte[0] : data);
        return format(md.digest());
    }

    /**
     * Hash a string using UTF-8 encoding.
     *
     * @param text the string to hash; {@code null} is treated as empty
     * @return {@code "sha256:" + hex-digest}
     */
    public static String of(String text) {
        return of(text == null
                ? new byte[0]
                : text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hash the bytes of a file, streaming so large files don't load
     * into memory all at once.
     *
     * @param path existing readable file
     * @return {@code "sha256:" + hex-digest}
     * @throws ScaffoldException if the file cannot be read
     */
    public static String ofFile(Path path) {
        MessageDigest md = newDigest();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return format(md.digest());
        } catch (IOException e) {
            throw new ScaffoldException(
                    "Cannot hash file " + path, e);
        }
    }

    /**
     * Compare a known hash to the current hash of a file.
     *
     * @param path     existing readable file
     * @param expected hash string in the form
     *                 {@code "sha256:" + hex-digest}; {@code null}
     *                 returns {@code false}
     * @return {@code true} iff the file's current hash equals
     *         {@code expected}
     */
    public static boolean matches(Path path, String expected) {
        if (expected == null) {
            return false;
        }
        return ofFile(path).equals(expected);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 not available", e);
        }
    }

    private static String format(byte[] digest) {
        StringBuilder sb = new StringBuilder(
                PREFIX.length() + digest.length * 2);
        sb.append(PREFIX);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Sign native libraries ({@code .dylib}, {@code .jnilib}) inside a
 * jlink runtime image so the resulting installer passes Apple notarization.
 *
 * <p>Apple requires every executable binary in a notarized bundle to be
 * signed with a Developer ID certificate and include a secure timestamp.
 * JARs containing native libraries (JNA, RocksDB, etc.) ship with
 * unsigned or ad-hoc-signed binaries that Apple rejects.
 *
 * <p>This goal walks the runtime image directory, finds native libraries
 * both loose and inside JARs, and signs each one with {@code codesign}.
 * For JARs, the native entries are extracted, signed, and repacked.
 *
 * <p>Bind this goal after jlink image assembly and dependency staging
 * but before jpackage creates the installer:
 * <pre>{@code
 * <execution>
 *     <id>codesign-natives</id>
 *     <phase>package[5]</phase>
 *     <goals><goal>codesign-natives</goal></goals>
 *     <configuration>
 *         <runtimeImageDir>${project.build.directory}/jreleaser-jlink/assemble/komet-standard/jlink/...</runtimeImageDir>
 *         <signingIdentity>Developer ID Application: Your Name (TEAMID)</signingIdentity>
 *     </configuration>
 * </execution>
 * }</pre>
 *
 * <p>On non-macOS platforms the goal skips silently.
 *
 * @see <a href="https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution">
 *      Apple: Notarizing macOS Software Before Distribution</a>
 */
@Mojo(name = "codesign-natives",
      defaultPhase = "package",
      projectRequired = true)
public class CodesignNativesMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /** Creates this goal instance. */
    public CodesignNativesMojo() {}

    /**
     * Root directory of the jlink runtime image to scan.
     * All {@code .jar}, {@code .dylib}, and {@code .jnilib} files
     * under this tree are inspected.
     */
    @Parameter(property = "codesign.runtimeImageDir")
    private File runtimeImageDir;

    /**
     * The {@code codesign} signing identity. Typically a
     * "Developer ID Application" certificate name including the team ID,
     * e.g., {@code "Developer ID Application: Jane Doe (ABCDE12345)"}.
     *
     * <p>Required on macOS; ignored on other platforms.
     * Not marked {@code required=true} so the goal can skip gracefully
     * on non-macOS without Maven failing parameter validation first.
     */
    @Parameter(property = "codesign.identity")
    private String signingIdentity;

    /**
     * Skip native codesigning entirely.
     */
    @Parameter(property = "codesign.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Keychain password for unlocking the signing keychain before codesign.
     * Read from {@code CODESIGN_KEYCHAIN_PASSWORD} environment variable
     * if not set via Maven property. When provided, the login keychain
     * is unlocked automatically — no interactive prompt.
     */
    @Parameter(property = "codesign.keychainPassword",
               defaultValue = "${env.CODESIGN_KEYCHAIN_PASSWORD}")
    private String keychainPassword;

    @Override
    public void execute() throws MojoException {
        if (skip) {
            getLog().info("Native codesigning skipped (codesign.skip=true)");
            return;
        }

        if (!ReleaseSupport.isMacOS()) {
            getLog().info("Native codesigning skipped \u2014 not running on macOS");
            return;
        }

        if (runtimeImageDir == null || !runtimeImageDir.isDirectory()) {
            getLog().warn("Runtime image directory does not exist: " + runtimeImageDir
                    + " \u2014 skipping native codesigning");
            return;
        }

        if (signingIdentity == null || signingIdentity.isBlank()) {
            getLog().info("Native codesigning skipped \u2014 no signing identity provided");
            getLog().info("  To sign, pass -Dcodesign.identity=\"Developer ID Application: ...\"");
            return;
        }

        unlockKeychainIfNeeded();

        getLog().info("");
        getLog().info("Native Library Codesigning");
        getLog().info("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        getLog().info("  Runtime image:   " + runtimeImageDir);
        getLog().info("  Identity:        " + signingIdentity);

        // Phase 1: Discover native files and JARs containing natives
        List<Path> looseNatives = new ArrayList<>();
        List<Path> jarsWithNatives = new ArrayList<>();
        scanTree(runtimeImageDir.toPath(), looseNatives, jarsWithNatives);

        getLog().info("  Loose natives:   " + looseNatives.size());
        getLog().info("  JARs to repack:  " + jarsWithNatives.size());

        int signedCount = 0;

        // Phase 2: Sign loose native files directly
        for (Path nativeFile : looseNatives) {
            codesign(nativeFile);
            signedCount++;
        }

        // Phase 3: Extract, sign, and repack JARs with embedded natives
        for (Path jarPath : jarsWithNatives) {
            signedCount += processJar(jarPath);
        }

        getLog().info("");
        getLog().info("Codesigning complete \u2014 " + signedCount + " native(s) signed");
        getLog().info("");
    }

    /**
     * Walk the directory tree, collecting loose native files and JARs
     * that contain native entries.
     */
    private void scanTree(Path root, List<Path> looseNatives,
                          List<Path> jarsWithNatives)
            throws MojoException {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (isNativeFile(name)) {
                        looseNatives.add(file);
                    } else if (name.endsWith(".jar")) {
                        try {
                            if (jarContainsNatives(file)) {
                                jarsWithNatives.add(file);
                            }
                        } catch (IOException e) {
                            getLog().warn("Could not inspect JAR: " + file + " \u2014 " + e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to scan runtime image: " + root, e);
        }
    }

    /**
     * Check if a JAR contains any native library entries.
     */
    static boolean jarContainsNatives(Path jarPath) throws IOException {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().toLowerCase(Locale.ROOT);
                if (isNativeFile(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Extract native entries from a JAR, sign them, and repack the JAR.
     *
     * @return the number of native files signed in this JAR
     */
    private int processJar(Path jarPath) throws MojoException {
        String jarName = jarPath.getFileName().toString();
        getLog().info("Processing JAR: " + jarName);

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("codesign-jar-");
        } catch (IOException e) {
            throw new MojoException("Failed to create temp directory", e);
        }

        try {
            // Step 1: Extract native entries and record their info
            List<String> nativeEntries = new ArrayList<>();
            try (ZipFile zip = new ZipFile(jarPath.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    if (!isNativeFile(entry.getName().toLowerCase(Locale.ROOT))) continue;

                    nativeEntries.add(entry.getName());
                    Path extractTarget = tempDir.resolve(entry.getName());
                    Files.createDirectories(extractTarget.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, extractTarget, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException e) {
                throw new MojoException(
                        "Failed to extract natives from " + jarPath, e);
            }

            if (nativeEntries.isEmpty()) {
                return 0;
            }

            // Step 2: Sign each extracted native
            for (String entryName : nativeEntries) {
                Path extracted = tempDir.resolve(entryName);
                codesign(extracted);
            }

            // Step 3: Repack the JAR with signed natives replacing originals
            Path repackedJar = tempDir.resolve("repacked.jar");
            repackJar(jarPath, repackedJar, tempDir, nativeEntries);

            // Step 4: Atomically replace the original JAR
            try {
                Files.move(repackedJar, jarPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // ATOMIC_MOVE may not be supported across filesystems
                try {
                    Files.move(repackedJar, jarPath,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e2) {
                    throw new MojoException(
                            "Failed to replace JAR: " + jarPath, e2);
                }
            }

            getLog().info("  Signed " + nativeEntries.size()
                    + " native(s) in " + jarName);
            return nativeEntries.size();

        } finally {
            // Clean up temp directory
            deleteRecursively(tempDir);
        }
    }

    /**
     * Repack a JAR, substituting signed native entries from the temp directory.
     * Preserves entry order, compression method, and extra fields.
     */
    private void repackJar(Path originalJar, Path outputJar,
                           Path signedDir, List<String> nativeEntries)
            throws MojoException {
        try (ZipFile original = new ZipFile(originalJar.toFile());
             OutputStream fos = Files.newOutputStream(outputJar);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            Enumeration<? extends ZipEntry> entries = original.entries();
            while (entries.hasMoreElements()) {
                ZipEntry oldEntry = entries.nextElement();
                ZipEntry newEntry = new ZipEntry(oldEntry.getName());

                // Preserve compression method
                newEntry.setMethod(oldEntry.getMethod());
                if (oldEntry.getMethod() == ZipEntry.STORED) {
                    // STORED entries need explicit size and CRC
                    if (nativeEntries.contains(oldEntry.getName())) {
                        // Will be set from the signed file
                        Path signedFile = signedDir.resolve(oldEntry.getName());
                        long size = Files.size(signedFile);
                        newEntry.setSize(size);
                        newEntry.setCompressedSize(size);
                        newEntry.setCrc(computeCrc(signedFile));
                    } else {
                        newEntry.setSize(oldEntry.getSize());
                        newEntry.setCompressedSize(oldEntry.getCompressedSize());
                        newEntry.setCrc(oldEntry.getCrc());
                    }
                }
                if (oldEntry.getExtra() != null) {
                    newEntry.setExtra(oldEntry.getExtra());
                }
                if (oldEntry.getComment() != null) {
                    newEntry.setComment(oldEntry.getComment());
                }
                newEntry.setTime(oldEntry.getTime());

                zos.putNextEntry(newEntry);

                if (!oldEntry.isDirectory()) {
                    if (nativeEntries.contains(oldEntry.getName())) {
                        // Substitute with signed version
                        Path signedFile = signedDir.resolve(oldEntry.getName());
                        Files.copy(signedFile, zos);
                    } else {
                        // Copy original bytes
                        try (InputStream in = original.getInputStream(oldEntry)) {
                            in.transferTo(zos);
                        }
                    }
                }

                zos.closeEntry();
            }

        } catch (IOException e) {
            throw new MojoException(
                    "Failed to repack JAR: " + originalJar, e);
        }
    }

    /**
     * Unlock the login keychain if a password is available.
     * This prevents interactive prompts during codesign on dev machines
     * and is required for CI where no GUI is available.
     */
    private void unlockKeychainIfNeeded() throws MojoException {
        if (keychainPassword == null || keychainPassword.isBlank()) {
            return;
        }
        getLog().info("  Unlocking keychain for codesign...");
        ReleaseSupport.exec(new java.io.File("."), getLog(),
                "security", "unlock-keychain",
                "-p", keychainPassword,
                System.getProperty("user.home")
                        + "/Library/Keychains/login.keychain-db");
        // Also set the partition list so codesign can access the key
        ReleaseSupport.exec(new java.io.File("."), getLog(),
                "security", "set-key-partition-list",
                "-S", "apple-tool:,apple:,codesign:",
                "-s", "-k", keychainPassword,
                System.getProperty("user.home")
                        + "/Library/Keychains/login.keychain-db");
    }

    /**
     * Sign a single native file with {@code codesign}.
     * Captures and logs stdout/stderr so that signing errors
     * (keychain access, certificate issues) are visible (#134).
     */
    private void codesign(Path file) throws MojoException {
        try {
            ReleaseSupport.execCaptureAndLog(
                    file.getParent().toFile(), getLog(),
                    "codesign", "--force", "--timestamp",
                    "--options", "runtime",
                    "--sign", signingIdentity,
                    file.toString());
        } catch (MojoException e) {
            // Re-throw with the file path for context — the captured
            // output was already logged by execCaptureAndLog
            throw new MojoException(
                    "codesign failed for " + file.getFileName()
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * Check if a filename represents a native library.
     */
    static boolean isNativeFile(String name) {
        return name.endsWith(".dylib") || name.endsWith(".jnilib");
    }

    /**
     * Compute CRC-32 for a file (needed for STORED zip entries).
     */
    private static long computeCrc(Path file) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                crc.update(buf, 0, n);
            }
        }
        return crc.getValue();
    }

    /**
     * Recursively delete a directory tree.
     */
    private static void deleteRecursively(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc)
                        throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException _) {
            // Best-effort cleanup
        }
    }
}

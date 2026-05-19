package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Re-sign the {@code .app} bundle inside a jpackage-produced {@code .pkg}
 * installer to add macOS entitlements required by the JVM.
 *
 * <p>This workaround exists because of <a
 * href="https://bugs.openjdk.org/browse/JDK-8358723">JDK-8358723</a>:
 * {@code jpackage --mac-sign} in older JDKs signs the main launcher and
 * nested runtime binaries without entitlements, so the JVM's JIT
 * entitlements ({@code com.apple.security.cs.allow-jit}, etc.) are
 * missing.  Without them the JVM crashes immediately on Apple Silicon
 * with {@code EXC_BREAKPOINT} in {@code pthread_jit_write_protect_np}.
 *
 * <p>The fix for JDK-8358723 is backported to <b>JDK 25.0.2+</b> via
 * <a href="https://bugs.openjdk.org/browse/JDK-8369477">JDK-8369477</a>
 * (OpenJDK 25.0.2 Jan 2026 CPU; Oracle JDK 25.0.3 Apr 2026 CPU) and is
 * present in JDK 26 mainline.  On those JDKs jpackage signs correctly,
 * and re-signing on top produces a signature variant macOS 26.4's notary
 * rejects.  This goal therefore <b>auto-skips on JDK 25.0.2 or newer</b>.
 *
 * <p>This goal post-processes the {@code .pkg} (only on JDK &lt; 25.0.2):
 * <ol>
 *   <li>Expands the {@code .pkg} with {@code pkgutil --expand}</li>
 *   <li>Extracts the Payload (gzip + cpio archive)</li>
 *   <li>Re-signs the main executable and {@code .app} bundle with entitlements</li>
 *   <li>Repacks the Payload and regenerates the BOM</li>
 *   <li>Flattens the {@code .pkg} with {@code pkgutil --flatten}</li>
 *   <li>Signs the {@code .pkg} with {@code productsign}</li>
 * </ol>
 *
 * <p>Bind this goal after jpackage but before notarization:
 * <pre>{@code
 * <execution>
 *     <id>codesign-pkg</id>
 *     <phase>verify[0.5]</phase>
 *     <goals><goal>codesign-pkg</goal></goals>
 *     <configuration>
 *         <entitlementsFile>${project.basedir}/src/main/resources/installer/resourceDir_unix/default.plist</entitlementsFile>
 *     </configuration>
 * </execution>
 * }</pre>
 *
 * <p>On non-macOS platforms the goal skips silently.  Set
 * {@code -Dcodesign.pkg.forceWorkaround=true} to run the re-sign on
 * JDK 25.0.2+ (debugging only).
 */
@Mojo(name = IkeGoal.NAME_CODESIGN_PKG,
      defaultPhase = "verify",
      projectRequired = true)
public class CodesignPkgMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /** Creates this goal instance. */
    public CodesignPkgMojo() {}

    /**
     * Directory containing the {@code .pkg} files produced by jpackage.
     */
    @Parameter(property = "codesign.pkgDir",
               defaultValue = "${project.build.directory}/jreleaser/assemble/komet/jpackage")
    private File pkgDir;

    /**
     * The {@code codesign} signing identity for the application.
     * Typically {@code "Developer ID Application: Name (TEAMID)"}.
     *
     * <p>The installer signing identity is derived automatically by
     * replacing "Application" with "Installer".
     */
    @Parameter(property = "codesign.identity")
    private String signingIdentity;

    /**
     * Path to the entitlements plist file.
     */
    @Parameter(property = "codesign.entitlements")
    private File entitlementsFile;

    /**
     * Skip this goal entirely.
     */
    @Parameter(property = "codesign.pkg.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Treat a signing identity that is absent from the keychain as a
     * hard failure rather than a graceful skip.
     *
     * <p>Off by default so a routine build on a machine without the
     * configured Developer ID certificate still succeeds — it logs a
     * warning and skips signing. Release builds set this to {@code true}
     * so a missing identity fails the build loudly instead of silently
     * shipping an unsigned installer.
     */
    @Parameter(property = "codesign.requireIdentity", defaultValue = "false")
    private boolean requireIdentity;

    /**
     * Force the entitlements workaround even on JDK 25.0.2+ where
     * jpackage itself signs with entitlements (JDK-8369477 / JDK-8358723).
     * Normally the goal auto-skips on fixed JDKs; this override is for
     * debugging or temporary compatibility with downstream tooling.
     */
    @Parameter(property = "codesign.pkg.forceWorkaround", defaultValue = "false")
    private boolean forceWorkaround;

    /**
     * Keychain password for unlocking the signing keychain before codesign.
     * Read from {@code CODESIGN_KEYCHAIN_PASSWORD} environment variable
     * if not set via Maven property.
     */
    @Parameter(property = "codesign.keychainPassword",
               defaultValue = "${env.CODESIGN_KEYCHAIN_PASSWORD}")
    private String keychainPassword;

    @Override
    public void execute() throws MojoException {
        if (skip) {
            getLog().info("Package codesigning skipped (codesign.pkg.skip=true)");
            return;
        }

        if (!forceWorkaround && jpackageHasEntitlementsFix(Runtime.version())) {
            getLog().info("Package codesigning skipped — running JDK "
                    + Runtime.version()
                    + " includes the JDK-8358723 entitlements fix"
                    + " (JDK-8369477 backport). jpackage signs correctly;"
                    + " re-signing here would produce a signature macOS 26.4+"
                    + " notarization rejects."
                    + " Set -Dcodesign.pkg.forceWorkaround=true to override.");
            return;
        }

        if (!ReleaseSupport.isMacOS()) {
            getLog().info("Package codesigning skipped — not running on macOS");
            return;
        }

        if (pkgDir == null || !pkgDir.isDirectory()) {
            getLog().warn("Package directory does not exist: " + pkgDir
                    + " — skipping package codesigning");
            return;
        }

        if (signingIdentity == null || signingIdentity.isBlank()) {
            getLog().info("Package codesigning skipped — no signing identity provided");
            return;
        }

        if (entitlementsFile == null || !entitlementsFile.isFile()) {
            getLog().warn("Entitlements file not found: " + entitlementsFile
                    + " — skipping package codesigning");
            return;
        }

        unlockKeychainIfNeeded();

        if (!CodesignSupport.identityInKeychain(signingIdentity, getLog())) {
            String msg = "Signing identity not found in keychain: \""
                    + signingIdentity + "\"";
            if (requireIdentity) {
                throw new MojoException(msg
                        + " — codesign.requireIdentity=true. Install the"
                        + " Developer ID certificate on this machine, or"
                        + " unset the strict flag for an unsigned build.");
            }
            getLog().warn(msg + " — skipping package codesigning.");
            getLog().warn("  Pass -Dcodesign.requireIdentity=true to make"
                    + " a missing identity a hard failure (release builds).");
            return;
        }

        List<Path> pkgFiles = findPkgFiles();
        if (pkgFiles.isEmpty()) {
            getLog().warn("No .pkg files found in " + pkgDir);
            return;
        }

        getLog().info("");
        getLog().info("Package Entitlement Codesigning");
        getLog().info("═══════════════════════════════════════════════════");
        getLog().info("  Package dir:     " + pkgDir);
        getLog().info("  Identity:        " + signingIdentity);
        getLog().info("  Entitlements:    " + entitlementsFile);
        getLog().info("  Packages found:  " + pkgFiles.size());

        for (Path pkg : pkgFiles) {
            processPackage(pkg);
        }

        getLog().info("");
        getLog().info("Package codesigning complete");
        getLog().info("");
    }

    /**
     * Unlock the login keychain if a password is available.
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
        ReleaseSupport.exec(new java.io.File("."), getLog(),
                "security", "set-key-partition-list",
                "-S", "apple-tool:,apple:,codesign:",
                "-s", "-k", keychainPassword,
                System.getProperty("user.home")
                        + "/Library/Keychains/login.keychain-db");
    }

    /**
     * Process a single {@code .pkg} file: expand, re-sign app with
     * entitlements, repack, and re-sign the package.
     */
    private void processPackage(Path pkg) throws MojoException {
        String pkgName = pkg.getFileName().toString();
        getLog().info("Processing: " + pkgName);

        Path workDir = null;
        Path expandedDir = null;
        Path payloadDir = null;

        try {
            // Step 1: Expand the .pkg
            // pkgutil --expand requires the target to NOT exist, so create
            // a parent temp dir and use a child path that doesn't exist yet
            workDir = Files.createTempDirectory("codesign-pkg-work-");
            expandedDir = workDir.resolve("expanded");
            getLog().info("  Expanding .pkg...");
            ReleaseSupport.exec(pkg.getParent().toFile(), getLog(),
                    "pkgutil", "--expand", pkg.toString(), expandedDir.toString());

            // Step 2: Find the inner component .pkg directory
            Path componentPkg = findComponentPkg(expandedDir);
            if (componentPkg == null) {
                throw new MojoException(
                        "No component .pkg found inside expanded package");
            }
            getLog().info("  Component: " + componentPkg.getFileName());

            Path payload = componentPkg.resolve("Payload");
            if (!Files.isRegularFile(payload)) {
                throw new MojoException(
                        "No Payload found in " + componentPkg);
            }

            // Step 3: Extract the Payload (gzip + cpio)
            payloadDir = Files.createTempDirectory("codesign-pkg-payload-");
            getLog().info("  Extracting Payload...");
            extractPayload(payload, payloadDir);

            // Step 4: Find the .app bundle and its main executable
            Path appBundle = findAppBundle(payloadDir);
            if (appBundle == null) {
                throw new MojoException(
                        "No .app bundle found in extracted Payload");
            }
            getLog().info("  App bundle: " + appBundle.getFileName());

            Path macosDir = appBundle.resolve("Contents/MacOS");
            Path mainExec = findMainExecutable(macosDir);
            if (mainExec == null) {
                throw new MojoException(
                        "No executable found in " + macosDir);
            }
            getLog().info("  Main executable: " + mainExec.getFileName());

            // Step 5: Re-sign the main executable with entitlements
            getLog().info("  Signing executable with entitlements...");
            codesignWithEntitlements(mainExec);

            // Step 6: Re-sign the .app bundle
            getLog().info("  Signing .app bundle...");
            codesignWithEntitlements(appBundle);

            // Step 7: Repack the Payload
            getLog().info("  Repacking Payload...");
            repackPayload(payloadDir, payload);

            // Step 8: Regenerate BOM
            Path bom = componentPkg.resolve("Bom");
            getLog().info("  Regenerating BOM...");
            ReleaseSupport.exec(payloadDir.toFile(), getLog(),
                    "mkbom", payloadDir.toString(), bom.toString());

            // Step 9: Flatten back to .pkg
            Path flattenedPkg = pkg.getParent().resolve(pkgName + ".tmp");
            getLog().info("  Flattening .pkg...");
            ReleaseSupport.exec(pkg.getParent().toFile(), getLog(),
                    "pkgutil", "--flatten", expandedDir.toString(),
                    flattenedPkg.toString());

            // Step 10: Sign the .pkg with productsign
            String installerIdentity = deriveInstallerIdentity(signingIdentity);
            Path signedPkg = pkg.getParent().resolve(pkgName + ".signed");
            getLog().info("  Signing .pkg with: " + installerIdentity);
            ReleaseSupport.exec(pkg.getParent().toFile(), getLog(),
                    "productsign",
                    "--sign", installerIdentity,
                    "--timestamp",
                    flattenedPkg.toString(),
                    signedPkg.toString());

            // Step 11: Replace the original
            Files.delete(flattenedPkg);
            Files.move(signedPkg, pkg, StandardCopyOption.REPLACE_EXISTING);

            getLog().info("  Done — entitlements applied to " + pkgName);

        } catch (IOException e) {
            throw new MojoException(
                    "Failed to process package: " + pkgName, e);
        } finally {
            if (payloadDir != null) deleteRecursively(payloadDir);
            if (workDir != null) deleteRecursively(workDir);
        }
    }

    /**
     * Find the inner component {@code .pkg} directory inside an expanded
     * package.  This is the directory containing Payload, Bom, and
     * PackageInfo.
     */
    private Path findComponentPkg(Path expandedDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                expandedDir,
                entry -> entry.getFileName().toString()
                        .toLowerCase(Locale.ROOT).endsWith(".pkg"))) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)
                        && Files.isRegularFile(entry.resolve("Payload"))) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Find the {@code .app} bundle directory inside an extracted Payload.
     */
    private Path findAppBundle(Path payloadDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                payloadDir,
                entry -> entry.getFileName().toString()
                        .toLowerCase(Locale.ROOT).endsWith(".app"))) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Find the main executable inside a {@code Contents/MacOS} directory.
     * Returns the first regular file found.
     */
    private Path findMainExecutable(Path macosDir) throws IOException {
        if (!Files.isDirectory(macosDir)) return null;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(macosDir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && Files.isExecutable(entry)) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Extract a gzip-compressed cpio Payload archive.
     */
    private void extractPayload(Path payload, Path targetDir)
            throws MojoException {
        // gunzip -dc Payload | cpio -id
        // We use a shell pipeline for this
        ReleaseSupport.exec(targetDir.toFile(), getLog(),
                "sh", "-c",
                "gunzip -dc " + shellQuote(payload.toString())
                        + " | cpio -id 2>/dev/null");
    }

    /**
     * Repack extracted files into a gzip-compressed cpio Payload.
     */
    private void repackPayload(Path sourceDir, Path payloadFile)
            throws MojoException {
        // find . -print | cpio -o --format odc | gzip -c > Payload
        ReleaseSupport.exec(sourceDir.toFile(), getLog(),
                "sh", "-c",
                "find . -print | cpio -o --format odc 2>/dev/null"
                        + " | gzip -c > " + shellQuote(payloadFile.toString()));
    }

    /**
     * Sign a file or bundle with entitlements.
     */
    private void codesignWithEntitlements(Path target)
            throws MojoException {
        ReleaseSupport.exec(target.getParent().toFile(), getLog(),
                "codesign", "--force", "--timestamp",
                "--options", "runtime",
                "--entitlements", entitlementsFile.getAbsolutePath(),
                "--sign", signingIdentity,
                target.toString());
    }

    /**
     * Derive the "Developer ID Installer" identity from the
     * "Developer ID Application" identity.
     */
    static String deriveInstallerIdentity(String applicationIdentity) {
        return applicationIdentity.replace(
                "Developer ID Application",
                "Developer ID Installer");
    }

    /**
     * True when the JDK described by {@code v} contains the JDK-8358723
     * entitlements fix — JDK 26 mainline, or JDK 25.0.2+ via the
     * JDK-8369477 backport.
     *
     * @param v the JDK version to test (typically {@link Runtime#version()})
     * @return {@code true} if jpackage on this JDK signs with entitlements
     *         and the re-sign workaround should be skipped
     */
    static boolean jpackageHasEntitlementsFix(Runtime.Version v) {
        int feature = v.feature();
        if (feature >= 26) return true;
        if (feature == 25 && v.update() >= 2) return true;
        return false;
    }

    /**
     * Find {@code .pkg} files in the package directory.
     */
    private List<Path> findPkgFiles() throws MojoException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                pkgDir.toPath(),
                entry -> entry.getFileName().toString()
                        .toLowerCase(Locale.ROOT).endsWith(".pkg"))) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    result.add(entry);
                }
            }
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to scan for .pkg files in " + pkgDir, e);
        }
        return result;
    }

    /**
     * Quote a string for use in a shell command.
     */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Recursively delete a directory tree.
     */
    private static void deleteRecursively(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file,
                        BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d,
                        IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException _) {
            // Best-effort cleanup
        }
    }
}

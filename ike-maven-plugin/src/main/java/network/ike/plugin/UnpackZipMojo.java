package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Download a zip archive from a URL and unpack it.
 *
 * <p>Uses {@link java.util.zip.ZipInputStream} directly, bypassing
 * Plexus Archiver. This avoids the 1800+ case-sensitivity warnings
 * that Plexus emits on macOS when a zip contains entries that differ
 * only by case (e.g., the DocBook XSL distribution).
 *
 * <p>The downloaded zip is cached in a local directory so subsequent
 * builds skip the download when the cached file already exists.
 *
 * <p>This goal replaces both {@code download-maven-plugin:wget} and
 * the {@code exec-maven-plugin} call to system {@code unzip} in the
 * docbook-xsl build.
 *
 * <p>Usage:
 * <pre>{@code
 * <execution>
 *     <id>download-and-unpack-docbook-xsl</id>
 *     <phase>generate-resources</phase>
 *     <goals><goal>unpack-zip</goal></goals>
 *     <configuration>
 *         <url>https://github.com/.../docbook-xsl-1.79.2.zip</url>
 *         <outputDirectory>${project.build.directory}</outputDirectory>
 *     </configuration>
 * </execution>
 * }</pre>
 *
 * @since 100
 */
@Mojo(name = "unpack-zip",
      defaultPhase = "generate-resources")
public class UnpackZipMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /** Access the Maven logger. @return the logger instance */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /**
     * URL of the zip archive to download.
     */
    @Parameter(property = "ike.unpack.url", required = true)
    String url;

    /**
     * Directory to unpack into. The zip's internal directory structure
     * is preserved beneath this directory.
     */
    @Parameter(property = "ike.unpack.outputDirectory", required = true)
    String outputDirectory;

    /**
     * Local cache directory for downloaded archives. If the zip file
     * already exists here, the download is skipped.
     *
     * <p>Defaults to {@code ~/.m2/repository/.cache/ike-maven-plugin/}.
     */
    @Parameter(property = "ike.unpack.cacheDirectory",
               defaultValue = "${settings.localRepository}/.cache/ike-maven-plugin")
    String cacheDirectory;

    /**
     * Skip execution.
     */
    @Parameter(property = "ike.unpack.skip", defaultValue = "false")
    boolean skip;

    /**
     * HTTP connect/read timeout in seconds.
     */
    @Parameter(property = "ike.unpack.timeout", defaultValue = "60")
    int timeoutSeconds;

    /** Creates this goal instance. */
    public UnpackZipMojo() {}

    @Override
    public void execute() throws MojoException {
        if (skip) {
            getLog().debug("unpack-zip: skipped");
            return;
        }

        try {
            Path zipFile = download();
            unpack(zipFile);
        } catch (IOException e) {
            throw new MojoException(
                    "unpack-zip failed for " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoException(
                    "unpack-zip interrupted for " + url, e);
        }
    }

    /**
     * Download the zip to the cache directory, skipping if already present.
     *
     * @return path to the cached zip file
     * @throws IOException if download or I/O fails
     * @throws InterruptedException if the download is interrupted
     */
    private Path download() throws IOException, InterruptedException {
        Path cacheDir = Path.of(cacheDirectory);
        Files.createDirectories(cacheDir);

        // Derive filename from URL (last path segment)
        String filename = url.substring(url.lastIndexOf('/') + 1);
        Path cached = cacheDir.resolve(filename);

        if (Files.isRegularFile(cached)) {
            getLog().info("unpack-zip: using cached " + cached);
            return cached;
        }

        getLog().info("unpack-zip: downloading " + url);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();

        // Download to a temp file, then move atomically to avoid
        // partial files in the cache on failure
        Path temp = Files.createTempFile(cacheDir, "download-", ".tmp");
        try {
            HttpResponse<Path> response = client.send(request,
                    HttpResponse.BodyHandlers.ofFile(temp));

            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode()
                        + " downloading " + url);
            }

            Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING);
            getLog().info("unpack-zip: cached " + filename
                    + " (" + Files.size(cached) / 1024 + " KB)");
            return cached;
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /**
     * Unpack a zip file to the output directory using
     * {@link ZipInputStream}.
     *
     * <p>Unlike Plexus Archiver, this does not warn on
     * case-insensitive filesystem collisions — it silently overwrites,
     * matching the behavior of system {@code unzip -o}.
     *
     * @param zipFile the zip archive to unpack
     * @throws IOException if unpacking fails
     */
    private void unpack(Path zipFile) throws IOException {
        Path outDir = Path.of(outputDirectory);
        Files.createDirectories(outDir);

        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(
                Files.newInputStream(zipFile))) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = outDir.resolve(entry.getName()).normalize();

                // Guard against zip-slip
                if (!target.startsWith(outDir)) {
                    throw new IOException(
                            "Zip entry outside target directory: "
                                    + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target,
                            StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
                zis.closeEntry();
            }
        }

        getLog().info("unpack-zip: extracted " + count + " files to "
                + outDir);
    }
}

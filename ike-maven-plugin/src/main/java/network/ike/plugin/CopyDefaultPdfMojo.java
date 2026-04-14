package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Copy the selected renderer's PDF to the default {@code pdf/} directory.
 *
 * <p>After multiple PDF renderers produce output in their own subdirectories
 * (e.g., {@code pdf-prince/}, {@code pdf-prawn/}), this goal copies the
 * preferred renderer's PDF to {@code pdf/} as the canonical output.
 *
 * <p>Replaces the {@code cp} exec-maven-plugin call with a cross-platform
 * Java goal. Gracefully skips if the source PDF does not exist (e.g., when
 * the renderer failed or was not enabled).
 *
 * <p>Usage:
 * <pre>{@code
 * <execution>
 *     <id>copy-default-pdf</id>
 *     <phase>verify</phase>
 *     <goals><goal>copy-default-pdf</goal></goals>
 * </execution>
 * }</pre>
 */
@Mojo(name = "copy-default-pdf",
      defaultPhase = "verify")
public class CopyDefaultPdfMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /** Access the Maven logger. @return the logger */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /** Root output directory (e.g., target/ike-doc). */
    @Parameter(property = "ike.doc.output.directory",
               defaultValue = "${project.build.directory}/ike-doc",
               required = true)
    File outputDirectory;

    /** Which renderer to use as the default PDF source. */
    @Parameter(property = "ike.pdf.default", defaultValue = "prince")
    String defaultRenderer;

    /** Output document name (without extension). */
    @Parameter(property = "ike.document.name",
               defaultValue = "${project.artifactId}")
    String documentName;

    /** Skip execution. */
    @Parameter(property = "ike.skip.pdf-default", defaultValue = "false")
    boolean skip;

    /** Creates this goal instance. */
    public CopyDefaultPdfMojo() {}

    @Override
    public void execute() throws MojoException {
        if (skip) {
            getLog().debug("copy-default-pdf: skipped");
            return;
        }

        Path sourceDir = outputDirectory.toPath()
                .resolve("pdf-" + defaultRenderer);
        Path destDir = outputDirectory.toPath().resolve("pdf");

        if (!Files.isDirectory(sourceDir)) {
            getLog().warn("copy-default-pdf: source directory not found, skipping — "
                    + sourceDir);
            return;
        }

        try {
            // Copy all PDFs from the renderer directory (supports multi-document builds)
            boolean copied = false;
            try (var stream = Files.list(sourceDir)) {
                for (Path source : stream
                        .filter(p -> p.toString().endsWith(".pdf"))
                        .toList()) {
                    Files.createDirectories(destDir);
                    Path dest = destDir.resolve(source.getFileName());
                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                    getLog().info("copy-default-pdf: " + source.getFileName()
                            + " → pdf/ (from " + defaultRenderer + ")");
                    copied = true;
                }
            }

            if (!copied) {
                getLog().warn("copy-default-pdf: no PDFs found in " + sourceDir);
            }
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to copy default PDF", e);
        }
    }
}

package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rename a file within a directory.
 *
 * <p>Replaces {@code exec-maven-plugin} calls to {@code mv} in the
 * documentation pipeline. Performs an atomic move when possible,
 * falling back to copy-and-delete on cross-filesystem boundaries.
 *
 * <p>Gracefully skips if the source file does not exist (e.g., when
 * the renderer was not enabled or produced no output).
 *
 * <p>Usage:
 * <pre>{@code
 * <execution>
 *     <id>rename-html-single</id>
 *     <phase>verify</phase>
 *     <goals><goal>rename</goal></goals>
 *     <configuration>
 *         <source>${asciidoc.output.directory}/html-single/source.html</source>
 *         <target>${asciidoc.output.directory}/html-single/target.html</target>
 *         <skip>${ike.skip.html-single}</skip>
 *     </configuration>
 * </execution>
 * }</pre>
 *
 * @since 99
 */
@Mojo(name = "rename",
      defaultPhase = "verify")
public class RenameMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /**
     * Source file path — the file to rename.
     */
    @Parameter(property = "ike.rename.source", required = true)
    String source;

    /**
     * Target file path — the new name/location.
     */
    @Parameter(property = "ike.rename.target", required = true)
    String target;

    /**
     * Skip execution.
     */
    @Parameter(property = "ike.rename.skip", defaultValue = "false")
    boolean skip;

    /** Creates this goal instance. */
    public RenameMojo() {}

    @Override
    public void execute() throws MojoException {
        if (skip) {
            getLog().debug("rename: skipped");
            return;
        }

        Path sourcePath = Path.of(source);
        Path targetPath = Path.of(target);

        if (!Files.exists(sourcePath)) {
            getLog().info("rename: source not found, skipping — " + sourcePath);
            return;
        }

        try {
            Files.createDirectories(targetPath.getParent());
            Files.move(sourcePath, targetPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            getLog().info("rename: " + sourcePath.getFileName()
                    + " → " + targetPath.getFileName());
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to rename " + sourcePath + " → " + targetPath, e);
        }
    }
}

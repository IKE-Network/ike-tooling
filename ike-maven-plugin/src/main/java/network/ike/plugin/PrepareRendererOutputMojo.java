package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;

/**
 * Create output directories for external PDF renderers.
 *
 * <p>Replaces the per-renderer {@code mkdir -p} exec-maven-plugin calls
 * with a single cross-platform Java goal. Creates directories only for
 * renderers that are not skipped.
 *
 * <p>Usage:
 * <pre>{@code
 * <execution>
 *     <id>prepare-renderer-output</id>
 *     <phase>prepare-package</phase>
 *     <goals><goal>prepare-renderer-output</goal></goals>
 * </execution>
 * }</pre>
 */
@Mojo(name = "prepare-renderer-output",
      defaultPhase = "prepare-package")
public class PrepareRendererOutputMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /** Root output directory (e.g., target/ike-doc). */
    @Parameter(property = "ike.doc.output.directory",
               defaultValue = "${project.build.directory}/ike-doc",
               required = true)
    File outputDirectory;

    /** Skip Prince output directory. */
    @Parameter(property = "ike.skip.prince", defaultValue = "false")
    boolean skipPrince;

    /** Skip Antenna House output directory. */
    @Parameter(property = "ike.skip.ah", defaultValue = "true")
    boolean skipAh;

    /** Skip WeasyPrint output directory. */
    @Parameter(property = "ike.skip.weasyprint", defaultValue = "true")
    boolean skipWeasyprint;

    /** Skip XEP output directory. */
    @Parameter(property = "ike.skip.xep", defaultValue = "true")
    boolean skipXep;

    /** Skip FOP output directory. */
    @Parameter(property = "ike.skip.fop", defaultValue = "true")
    boolean skipFop;

    /** Skip default PDF output directory. */
    @Parameter(property = "ike.skip.pdf-default", defaultValue = "false")
    boolean skipPdfDefault;

    /** Creates this goal instance. */
    public PrepareRendererOutputMojo() {}

    @Override
    public void execute() throws MojoException {
        int created = 0;
        created += mkdirIf(!skipPrince, "pdf-prince");
        created += mkdirIf(!skipAh, "pdf-ah");
        created += mkdirIf(!skipWeasyprint, "pdf-weasyprint");
        created += mkdirIf(!skipXep, "pdf-xep");
        created += mkdirIf(!skipFop, "pdf-fop");
        created += mkdirIf(!skipPdfDefault, "pdf");

        if (created > 0) {
            getLog().info("prepare-renderer-output: created " + created
                    + " directories under " + outputDirectory);
        } else {
            getLog().debug("prepare-renderer-output: no directories needed");
        }
    }

    private int mkdirIf(boolean enabled, String subdir) {
        if (!enabled) return 0;
        File dir = new File(outputDirectory, subdir);
        if (!dir.exists()) {
            dir.mkdirs();
            return 1;
        }
        return 0;
    }
}

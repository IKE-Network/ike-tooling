package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Overlay the IKE green stylesheet onto a generated Javadoc apidocs tree.
 *
 * <p>Java 25's stock Javadoc stylesheet exposes a {@code :root} block of
 * CSS custom properties — colours, fonts, spacing. This mojo writes a
 * companion stylesheet {@code resource-files/ike-theme.css} that
 * overrides only those custom properties to the
 * {@code sentry-green} palette used by the project Maven site, then
 * injects a second {@code <link rel="stylesheet">} tag into every HTML
 * page so the override cascades over the stock stylesheet.
 *
 * <p>Same pattern as {@link InjectBreadcrumbMojo} for JaCoCo reports:
 * a single in-source CSS string is the source of truth, written into
 * the apidocs output after the javadoc tool has produced its files,
 * and the existing default stylesheet is left untouched (resilient to
 * Java version updates).
 *
 * <p>Usage in the IKE release flow:
 * <pre>
 * mvn site                                  # produces target/site/apidocs/
 * mvn ike:inject-javadoc-theme              # writes ike-theme.css and patches HTML
 * mvn site:stage                            # collects target/site → target/staging
 * </pre>
 *
 * <p>IKE-Network/ike-issues#518.
 */
@Mojo(name = IkeGoal.NAME_INJECT_JAVADOC_THEME,
      defaultPhase = "site")
public class InjectJavadocThemeMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /** Directory containing generated Javadoc HTML reports. */
    @Parameter(property = "targetDir",
               defaultValue = "${project.build.directory}/site/apidocs")
    File targetDir;

    /** Creates this goal instance. */
    public InjectJavadocThemeMojo() {}

    @Override
    public void execute() throws MojoException {
        if (!targetDir.isDirectory()) {
            getLog().info("inject-javadoc-theme: directory does not exist, "
                    + "skipping — " + targetDir);
            return;
        }

        try {
            writeThemeCss(targetDir.toPath());
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to write Javadoc theme CSS in " + targetDir, e);
        }

        int patched;
        try {
            patched = processDirectory(targetDir.toPath());
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to inject Javadoc theme into " + targetDir, e);
        }

        if (patched > 0) {
            getLog().info("inject-javadoc-theme: patched " + patched
                    + " HTML file(s) in " + targetDir);
        } else {
            getLog().info("inject-javadoc-theme: no stylesheet link to "
                    + "patch in " + targetDir);
        }
    }

    /**
     * Write the IKE theme CSS into the Javadoc {@code resource-files}
     * directory. That directory holds the stock {@code stylesheet.css}
     * the javadoc tool generates; placing {@code ike-theme.css} as a
     * sibling lets the {@code <link>} URLs that pages already use
     * (varied by depth — {@code resource-files/}, {@code ../resource-files/},
     * etc.) point at our override with the same relative-path machinery.
     *
     * @param apidocsDir root of the generated apidocs tree
     * @throws IOException on I/O failure
     */
    private void writeThemeCss(Path apidocsDir) throws IOException {
        String css = generateThemeCss();
        Path resources = apidocsDir.resolve("resource-files");
        if (Files.isDirectory(resources)) {
            Files.writeString(resources.resolve("ike-theme.css"), css);
        }
    }

    /**
     * Recursively process all HTML files in {@code dir}, injecting
     * a stylesheet link to {@code ike-theme.css} after the existing
     * link to {@code stylesheet.css}.
     *
     * @param dir directory to walk
     * @return number of files that were modified
     * @throws IOException on I/O failure
     */
    private int processDirectory(Path dir) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    count += processDirectory(entry);
                } else if (entry.toString().endsWith(".html")) {
                    String html = Files.readString(entry);
                    String patched = injectThemeCssLink(html);
                    if (!html.equals(patched)) {
                        Files.writeString(entry, patched);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // ── Pure testable functions ──────────────────────────────────────

    /**
     * Pattern matching the stock Javadoc stylesheet link. The capture
     * group holds the relative-path prefix (varies by page depth —
     * empty for {@code apidocs/index.html}, {@code ../} for one level
     * down, etc.) so the injected ike-theme.css link uses the same
     * prefix.
     */
    private static final Pattern STYLESHEET_LINK = Pattern.compile(
            "(<link rel=\"stylesheet\" type=\"text/css\" "
                    + "href=\")([^\"]*?)resource-files/stylesheet\\.css\">");

    /**
     * Inject a stylesheet link to {@code resource-files/ike-theme.css}
     * after the existing link to {@code resource-files/stylesheet.css}.
     * The injected URL inherits the same relative-path prefix as the
     * stock stylesheet, so it resolves correctly from any page depth.
     *
     * @param html the HTML content
     * @return HTML with the theme link injected, or unchanged if no
     *         stock stylesheet link is present
     */
    public static String injectThemeCssLink(String html) {
        Matcher m = STYLESHEET_LINK.matcher(html);
        if (!m.find()) {
            return html;
        }
        // Already injected — avoid duplicating on re-run.
        if (html.contains("resource-files/ike-theme.css")) {
            return html;
        }
        String prefix = m.group(2);
        String injection = m.group()
                + "<link rel=\"stylesheet\" type=\"text/css\" "
                + "href=\"" + prefix + "resource-files/ike-theme.css\">";
        return m.replaceFirst(Matcher.quoteReplacement(injection));
    }

    /**
     * Generate the IKE theme override CSS for Javadoc.
     *
     * <p>Overrides only the {@code :root} custom properties exposed by
     * the stock Java 25 stylesheet — the cascade does the rest. Palette
     * constants come from {@link IkePalette} so this theme,
     * {@link InjectBreadcrumbMojo}'s, and {@code ike-base-parent}'s
     * {@code site.css} all share one source.
     *
     * @return CSS content as a string
     */
    public static String generateThemeCss() {
        return """
                /* IKE Theme Override for Javadoc Reports                  */
                /* Overrides the Java 25 default stylesheet's :root        */
                /* custom properties to the sentry-green palette.          */
                /* Palette source: network.ike.plugin.IkePalette.          */

                :root {
                    --navbar-background-color: %VERDANT%;
                    --navbar-text-color: #ffffff;

                    --subnav-background-color: %CLOUD%;
                    --subnav-link-color: %SEA%;
                    --member-heading-background-color: var(--subnav-background-color);

                    --selected-background-color: %ACCENT%;
                    --selected-text-color: %TWILIGHT%;
                    --selected-link-color: %SEA%;

                    --table-header-color: %CLOUD%;
                    --title-color: %TWILIGHT%;

                    --link-color: %SEA%;
                    --link-color-active: %ACCENT%;

                    --toc-highlight-color: var(--subnav-background-color);
                    --toc-hover-color: %CLOUD%;
                }
                """
                .replace("%VERDANT%", IkePalette.VERDANT)
                .replace("%TWILIGHT%", IkePalette.TWILIGHT)
                .replace("%CLOUD%", IkePalette.CLOUD)
                .replace("%SEA%", IkePalette.SEA)
                .replace("%ACCENT%", IkePalette.ACCENT);
    }
}

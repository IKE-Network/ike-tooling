package network.ike.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function tests for {@link InjectJavadocThemeMojo}. The mojo's
 * filesystem side effects are exercised indirectly by the release-flow
 * integration tests; the unit-test focus here is on the HTML patcher,
 * which is the load-bearing string manipulation.
 */
class InjectJavadocThemeMojoTest {

    @Test
    void injectThemeCssLink_appends_after_top_level_stylesheet_link() {
        String html = """
                <html><head>
                <link rel="stylesheet" type="text/css" href="resource-files/jquery-ui.min.css">
                <link rel="stylesheet" type="text/css" href="resource-files/stylesheet.css">
                </head><body></body></html>
                """;

        String out = InjectJavadocThemeMojo.injectThemeCssLink(html);

        assertThat(out)
                .contains("<link rel=\"stylesheet\" type=\"text/css\""
                        + " href=\"resource-files/stylesheet.css\">"
                        + "<link rel=\"stylesheet\" type=\"text/css\""
                        + " href=\"resource-files/ike-theme.css\">");
    }

    @Test
    void injectThemeCssLink_preserves_relative_path_prefix() {
        // A class page deep in the tree references the stylesheet via
        // a ../../../ prefix; the injected link must use the same prefix.
        String html = """
                <link rel="stylesheet" type="text/css" href="../../../../../resource-files/stylesheet.css">
                """;

        String out = InjectJavadocThemeMojo.injectThemeCssLink(html);

        assertThat(out).contains(
                "href=\"../../../../../resource-files/ike-theme.css\">");
    }

    @Test
    void injectThemeCssLink_is_idempotent() {
        // Re-running on an already-patched HTML must not duplicate the link.
        String html = """
                <link rel="stylesheet" type="text/css" href="resource-files/stylesheet.css">
                <link rel="stylesheet" type="text/css" href="resource-files/ike-theme.css">
                """;

        assertThat(InjectJavadocThemeMojo.injectThemeCssLink(html))
                .isEqualTo(html);
    }

    @Test
    void injectThemeCssLink_no_op_when_stylesheet_link_absent() {
        // Some Javadoc support pages (legal/, etc.) have no stylesheet
        // link. The patcher must leave them alone.
        String html = "<html><head><title>x</title></head><body></body></html>";

        assertThat(InjectJavadocThemeMojo.injectThemeCssLink(html))
                .isEqualTo(html);
    }

    @Test
    void generateThemeCss_overrides_navbar_subnav_and_link_variables() {
        String css = InjectJavadocThemeMojo.generateThemeCss();

        assertThat(css)
                .contains("--navbar-background-color: #4A7D84")
                .contains("--subnav-background-color: #E6EBE7")
                .contains("--link-color: #3A6065")
                .contains("--selected-background-color: #FFA351")
                .contains("--title-color: #273B36");
    }
}

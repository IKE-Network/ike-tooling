package network.ike.plugin.release.prep;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ReleasePrep#siteLintFindings} — the release-time
 * preflight check that catches the {@code <url>} / {@code <links>}
 * drift class surfaced by IKE-Network/ike-issues#521 (bannerRight
 * "GitHub" link collapsing to {@code ./} when project {@code <url>}
 * matches the GitHub repo).
 */
class ReleasePrepSiteLintTest {

    @TempDir
    Path tempDir;

    @Test
    void clean_pom_and_site_descriptor_yield_no_findings() throws Exception {
        File gitRoot = newGitRoot()
                .withPomUrl("https://ike.network/ike-base-parent/")
                .withSiteDescriptor(bannerRightOnly())
                .build();

        assertThat(ReleasePrep.siteLintFindings(gitRoot, "ike-base-parent"))
                .isEmpty();
    }

    @Test
    void github_url_in_pom_is_flagged() throws Exception {
        // The exact drift that broke ike-version-management-extension
        // pre-#521: <url> set to the GitHub repo, so site-plugin
        // relativizes the matching bannerRight href to ./.
        File gitRoot = newGitRoot()
                .withPomUrl("https://github.com/IKE-Network/ike-docs")
                .withSiteDescriptor(bannerRightOnly())
                .build();

        List<String> findings = ReleasePrep.siteLintFindings(
                gitRoot, "ike-docs");

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0))
                .contains("pom.xml <url>")
                .contains("https://github.com/IKE-Network/ike-docs")
                .contains("expected 'https://ike.network/ike-docs/'")
                .contains("./");
    }

    @Test
    void duplicate_github_links_are_flagged() throws Exception {
        File gitRoot = newGitRoot()
                .withPomUrl("https://ike.network/ike-tooling/")
                .withSiteDescriptor(bannerRightPlusLinksGitHub())
                .build();

        List<String> findings = ReleasePrep.siteLintFindings(
                gitRoot, "ike-tooling");

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0))
                .contains("GitHub in both")
                .contains("<bannerRight>")
                .contains("<links>")
                .contains("canonical placement");
    }

    @Test
    void both_drifts_are_reported_together() throws Exception {
        // Worst-case: both rules trip. Each should appear as a
        // separate finding so the maintainer sees both at once.
        File gitRoot = newGitRoot()
                .withPomUrl("https://github.com/IKE-Network/ike-docs")
                .withSiteDescriptor(bannerRightPlusLinksGitHub())
                .build();

        List<String> findings = ReleasePrep.siteLintFindings(
                gitRoot, "ike-docs");

        assertThat(findings).hasSize(2);
    }

    @Test
    void absent_site_descriptor_is_tolerated() throws Exception {
        // A project with no src/site/site.xml (a code-only library
        // that hasn't authored a site descriptor) must not crash the
        // lint — and the pom-only rule still runs.
        File gitRoot = newGitRoot()
                .withPomUrl("https://ike.network/ike-base-parent/")
                .build();   // no site descriptor

        assertThat(ReleasePrep.siteLintFindings(gitRoot, "ike-base-parent"))
                .isEmpty();
    }

    @Test
    void absent_pom_is_tolerated() throws Exception {
        File gitRoot = newGitRoot()
                .withSiteDescriptor(bannerRightOnly())
                .build();   // no pom

        assertThat(ReleasePrep.siteLintFindings(gitRoot, "anything"))
                .isEmpty();
    }

    @Test
    void blank_projectId_skips_url_check() throws Exception {
        // The pom <url> rule needs a projectId to know the expected
        // value. If the caller passes blank, skip the check rather
        // than crash.
        File gitRoot = newGitRoot()
                .withPomUrl("https://example.com/whatever")
                .build();

        assertThat(ReleasePrep.siteLintFindings(gitRoot, ""))
                .isEmpty();
    }

    // ── fixtures ─────────────────────────────────────────────────────

    private GitRootBuilder newGitRoot() {
        return new GitRootBuilder(tempDir);
    }

    /**
     * Minimal site descriptor with GitHub in bannerRight only — the
     * fixed configuration after #521 cleanup.
     */
    private static String bannerRightOnly() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <site>
                    <bannerRight name="GitHub &lt;i class='fa-brands fa-github'&gt;&lt;/i&gt;"
                        href="https://github.com/IKE-Network/example"/>
                    <body>
                        <breadcrumbs/>
                        <menu name="Documentation"/>
                    </body>
                </site>
                """;
    }

    /**
     * Site descriptor with GitHub in BOTH bannerRight and a top-bar
     * <links> item — the pre-#521 drift pattern.
     */
    private static String bannerRightPlusLinksGitHub() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <site>
                    <bannerRight name="GitHub &lt;i class='fa-brands fa-github'&gt;&lt;/i&gt;"
                        href="https://github.com/IKE-Network/example"/>
                    <body>
                        <breadcrumbs/>
                        <links>
                            <item name="GitHub" href="https://github.com/IKE-Network/example"/>
                        </links>
                        <menu name="Documentation"/>
                    </body>
                </site>
                """;
    }

    /** Tiny pom + site builder for the lint test fixtures. */
    private static class GitRootBuilder {
        private final Path root;
        private String pomUrl;
        private String siteDescriptor;

        GitRootBuilder(Path root) {
            this.root = root;
        }

        GitRootBuilder withPomUrl(String url) {
            this.pomUrl = url;
            return this;
        }

        GitRootBuilder withSiteDescriptor(String descriptor) {
            this.siteDescriptor = descriptor;
            return this;
        }

        File build() throws Exception {
            if (pomUrl != null) {
                String pom = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <project>
                            <description>x</description>
                            <url>%s</url>
                            <inceptionYear>2026</inceptionYear>
                        </project>
                        """.formatted(pomUrl);
                Files.writeString(root.resolve("pom.xml"), pom);
            }
            if (siteDescriptor != null) {
                Path siteXml = root.resolve("src/site/site.xml");
                Files.createDirectories(siteXml.getParent());
                Files.writeString(siteXml, siteDescriptor);
            }
            return root.toFile();
        }
    }
}

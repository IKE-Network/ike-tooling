package network.ike.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Snapshot test for the canonical IKE palette. The constants here
 * must stay in lockstep with the {@code body.sentry-green} block in
 * {@code ike-base-parent/src/main/site-theme/css/site.css} — that
 * CSS file is the third palette consumer alongside the JaCoCo and
 * Javadoc theme mojos, and changes to {@link IkePalette} are a
 * coordinated cross-repo edit until that side is also driven from
 * here.
 */
class IkePaletteTest {

    @Test
    void canonical_colors_are_six_digit_hex_strings() {
        // Defensive: catch typos in the constants — every value must
        // be a valid #RRGGBB string. The mojos string-replace these
        // into CSS templates, and a malformed value would render
        // visibly broken (or worse, silently fall back to default).
        assertThat(IkePalette.VERDANT).matches("#[0-9A-Fa-f]{6}");
        assertThat(IkePalette.TWILIGHT).matches("#[0-9A-Fa-f]{6}");
        assertThat(IkePalette.MIST).matches("#[0-9A-Fa-f]{6}");
        assertThat(IkePalette.CLOUD).matches("#[0-9A-Fa-f]{6}");
        assertThat(IkePalette.SEA).matches("#[0-9A-Fa-f]{6}");
        assertThat(IkePalette.ACCENT).matches("#[0-9A-Fa-f]{6}");
        assertThat(IkePalette.VERDANT_LIGHT).matches("#[0-9A-Fa-f]{6}");
    }

    @Test
    void colors_match_sentry_green_palette() {
        // Pinned values — drift here means drift from
        // ike-base-parent's site.css and from the published live
        // sites. Update both sides together.
        assertThat(IkePalette.VERDANT).isEqualTo("#4A7D84");
        assertThat(IkePalette.TWILIGHT).isEqualTo("#273B36");
        assertThat(IkePalette.MIST).isEqualTo("#B7E4D2");
        assertThat(IkePalette.CLOUD).isEqualTo("#E6EBE7");
        assertThat(IkePalette.SEA).isEqualTo("#3A6065");
        assertThat(IkePalette.ACCENT).isEqualTo("#FFA351");
    }

    @Test
    void breadcrumb_theme_uses_palette_constants() {
        String css = InjectBreadcrumbMojo.generateThemeCss();

        assertThat(css)
                .as("JaCoCo theme references every palette color used")
                .contains(IkePalette.VERDANT)
                .contains(IkePalette.TWILIGHT)
                .contains(IkePalette.MIST)
                .contains(IkePalette.CLOUD)
                .contains(IkePalette.SEA);

        // No literal hex for the palette colors should remain in
        // the emitted CSS — every palette mention must come through
        // IkePalette. Catches a future regression where a developer
        // adds a new selector with a hardcoded color.
        assertThat(css)
                .as("no surrogate hex for primary palette colors")
                .doesNotContain("#6f42c1")
                .doesNotContain("#553098")
                .doesNotContain("#c9b3f5")
                .doesNotContain("#f3effc")
                .doesNotContain("#8057d4");
    }

    @Test
    void javadoc_theme_uses_palette_constants() {
        String css = InjectJavadocThemeMojo.generateThemeCss();

        assertThat(css)
                .contains(IkePalette.VERDANT)
                .contains(IkePalette.TWILIGHT)
                .contains(IkePalette.CLOUD)
                .contains(IkePalette.SEA)
                .contains(IkePalette.ACCENT);
    }
}

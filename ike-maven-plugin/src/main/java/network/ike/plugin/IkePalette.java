package network.ike.plugin;

/**
 * The IKE Network's canonical green palette, expressed as hex strings
 * for CSS consumption.
 *
 * <p>Source-of-truth for every IKE color override emitted from this
 * plugin — both {@link InjectBreadcrumbMojo}'s JaCoCo theme and
 * {@link InjectJavadocThemeMojo}'s Javadoc theme reference these
 * constants rather than carrying their own string literals.
 *
 * <p>Adjacent palette references that must stay in lockstep:
 * <ul>
 *   <li>{@code ike-base-parent/src/main/site-theme/css/site.css}
 *       — the {@code body.sentry-green} variable block read by the
 *       Sentry Maven skin at site render time.</li>
 * </ul>
 *
 * <p>The class is intentionally small and dependency-free. Promoting
 * to {@code ike-java-support} (as a value-types citizen) is the
 * eventual home, deferred until the cross-module sharing motivates
 * the release-cycle cost of a new {@code ike-java-support} version.
 *
 * <p>IKE-Network/ike-issues#518.
 */
public final class IkePalette {

    /** Banner / table header band. Growth, renewal. */
    public static final String VERDANT = "#4A7D84";

    /** Body text / dark accents. Matches banner in dark mode. */
    public static final String TWILIGHT = "#273B36";

    /** Gentle accent — reserved for highlight chrome. */
    public static final String MIST = "#B7E4D2";

    /** Soft off-white — table-header tint / subnav background. */
    public static final String CLOUD = "#E6EBE7";

    /** Link color. A darker Verdant. */
    public static final String SEA = "#3A6065";

    /** Warm orange — selected / active links / "you are here". */
    public static final String ACCENT = "#FFA351";

    /**
     * Lighter Verdant used for borders on table headers and other
     * intermediate-tone separators.
     */
    public static final String VERDANT_LIGHT = "#6E9499";

    private IkePalette() {}
}

package network.ike.plugin.release.coherence;

import network.ike.support.enums.ConstantBackedEnum;
import network.ike.support.enums.EnumDefinition;

/**
 * Where a release must confirm its own just-published artifact is
 * resolvable before it declares success — the demanded
 * <em>resolution scope</em> (IKE-Network/ike-issues#705).
 *
 * <p>The release-cascade is loosely coupled: a module asserts only
 * about <em>itself</em> ("I am not done until what I just published is
 * actually fetchable where I promised"); no module ever asks about its
 * upstreams. Cascade coherence then emerges, because the finish-trigger
 * fires the downstream only after the upstream build <em>succeeds</em>,
 * and "success" now includes the upstream's own self-resolution check.
 * An un-resolvable artifact fails its <em>own</em> build, the
 * finish-trigger does not fire, and the cascade stops — incoherence
 * becomes a red build on the responsible module, never a silently-wrong
 * downstream.
 *
 * <p>The three rungs name a <em>provided repository</em> in which the
 * module must confirm its artifact, ordered by audience reach:
 *
 * <ul>
 *   <li>{@link #LOCAL} — the local {@code .m2} cache. Verifies nothing:
 *       the module's own {@code install} trivially put the artifact
 *       there. An explicit opt-OUT, valid only for draft/dev runs and
 *       <strong>rejected for {@code -publish}</strong>.</li>
 *   <li>{@link #NEXUS} <em>(default)</em> — the shared, consumer-resolvable
 *       Nexus group ({@code ike-public}). The cross-agent / cascade
 *       guarantee: the source of truth a downstream's "resolve latest"
 *       actually reads.</li>
 *   <li>{@link #CENTRAL} — Maven Central. The public-availability gate;
 *       being eventually-consistent it is a long, bounded, non-blocking
 *       verify, never a fast hard gate.</li>
 * </ul>
 *
 * <p>The local-vs-TeamCity run axis is irrelevant to the scope — the
 * determinant is the artifact's audience (consumers resolve from the
 * shared repo), not where the build ran.
 *
 * <p>Lives in {@code ike-maven-plugin} rather than the shared
 * {@code ike-java-support} enum library (where {@link network.ike.support.enums.ReleasePolicy}
 * lives) because, unlike a release policy, a resolution scope is read
 * only by the release goal here — no consumer-side extension validates
 * it — so it needs no foundation re-release to evolve.
 *
 * <p>Each rung carries a {@code NAME_*} mirror constant — the
 * {@link ConstantBackedEnum} pattern — so the rung name is usable where
 * a compile-time {@code String} constant is required (e.g. the Mojo
 * {@code @Parameter} default).
 *
 * @since 1
 */
public enum ResolutionScope implements EnumDefinition {

    /** The local {@code .m2} cache — verifies nothing; draft/dev opt-out only. */
    LOCAL(ResolutionScope.NAME_LOCAL,
            "Confirm the artifact in the local .m2 cache. Verifies nothing — the module's "
                    + "own install trivially put it there. Draft/dev opt-out; rejected for -publish."),

    /** The shared, consumer-resolvable Nexus group ({@code ike-public}). */
    NEXUS(ResolutionScope.NAME_NEXUS,
            "Confirm the artifact in the shared, consumer-resolvable Nexus group (ike-public) "
                    + "— the cascade / cross-agent source of truth. The default for -publish."),

    /** Maven Central — the public-availability gate (eventual-consistency). */
    CENTRAL(ResolutionScope.NAME_CENTRAL,
            "Confirm the artifact on Maven Central — the public-availability gate. Eventually "
                    + "consistent, so a long bounded non-blocking verify, never a fast hard gate.");

    /** Mirror constant for {@link #LOCAL}. */
    public static final String NAME_LOCAL   = "local";
    /** Mirror constant for {@link #NEXUS}. */
    public static final String NAME_NEXUS   = "nexus";
    /** Mirror constant for {@link #CENTRAL}. */
    public static final String NAME_CENTRAL = "central";

    private final String literalName;
    private final String definition;

    ResolutionScope(String literalName, String definition) {
        this.literalName = literalName;
        this.definition = definition;
    }

    /**
     * {@inheritDoc}
     *
     * <p>For {@code ResolutionScope} the literal is the rung name as it
     * appears in the {@code ike.resolutionScope} property.
     */
    @Override
    public String literalName() {
        return literalName;
    }

    /**
     * {@inheritDoc}
     *
     * <p>For {@code ResolutionScope} the term and {@link #literalName()}
     * coincide.
     */
    @Override
    public String term() {
        return literalName;
    }

    /** {@inheritDoc} */
    @Override
    public String definition() {
        return definition;
    }

    /**
     * Whether this scope is strong enough to publish at — i.e. it
     * confirms the artifact in a <em>shared, consumer-resolvable</em>
     * repository rather than only the build's own local cache.
     *
     * <p>{@code -publish} demands {@code ≥ NEXUS}: a published artifact's
     * audience is consumers, who resolve from the shared repo, so a
     * release must prove the artifact is fetchable there. {@link #LOCAL}
     * is the lone opt-out and is rejected for publish.
     *
     * @return {@code true} for {@link #NEXUS} and {@link #CENTRAL};
     *         {@code false} for {@link #LOCAL}
     */
    public boolean satisfiesPublishMinimum() {
        return this != LOCAL;
    }

    static {
        ConstantBackedEnum.verify(ResolutionScope.class);
    }
}

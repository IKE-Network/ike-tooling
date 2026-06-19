package network.ike.plugin.release.coherence;

import network.ike.plugin.PomRewriter;
import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.support.version.CandidateVersionResolver;
import network.ike.plugin.support.version.MavenVersionComparator;
import network.ike.plugin.support.version.SessionCandidateVersionResolver;
import network.ike.support.enums.ConstantBackedEnum;
import network.ike.support.enums.ReleasePolicy;
import network.ike.workspace.cascade.CascadeEdge;
import network.ike.workspace.cascade.EdgeKind;
import network.ike.workspace.cascade.ProjectCascade;
import network.ike.workspace.cascade.ProjectCascadeIo;
import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.Repository;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The release-coherence gate (IKE-Network/ike-issues#705): a module's
 * release does not complete until its own just-published artifact
 * resolves at the demanded {@link ResolutionScope}, and its own upstream
 * pins are confirmed current against fresh metadata.
 *
 * <p>Both checks assert only about <em>this</em> module — never about
 * its upstreams or which siblings are mid-cascade. Coherence emerges
 * because the TeamCity finish-trigger fires the downstream only on the
 * upstream's <em>success</em>, and "success" now includes these checks.
 * An un-resolvable artifact (or a pin that silently failed to catch up)
 * fails <em>this</em> build, the finish-trigger does not fire, and the
 * cascade stops — incoherence is a red build on the responsible module,
 * never a silently-wrong downstream.
 *
 * <p><strong>Cold resolution.</strong> Both checks run against a session
 * whose local repository is a fresh, empty temp directory. This is
 * essential: the module's own {@code .m2} trivially holds the artifact
 * it just {@code install}ed, and caches upstream metadata under a daily
 * update policy — so resolving against the normal local repo would
 * confirm nothing and could read stale metadata (the exact failure that
 * shipped the incoherent ike-platform v110 on 2026-06-18). An empty
 * local repo forces a real fetch from the demanded remote, with the
 * session's configured credentials preserved.
 */
public final class CoherenceVerifier {

    /** Nexus group id consumers resolve released IKE artifacts from. */
    private static final String NEXUS_PUBLIC_ID = "ike-public";
    /** Fallback URL for {@link ResolutionScope#NEXUS} if not configured in the session. */
    private static final String NEXUS_PUBLIC_URL =
            "https://nexus.tinkar.org/repository/ike-public/";
    /** Fallback URL for {@link ResolutionScope#CENTRAL} if not configured in the session. */
    private static final String CENTRAL_URL = "https://repo1.maven.org/maven2/";

    private static final Map<String, ReleasePolicy> RELEASE_POLICY_INDEX =
            ConstantBackedEnum.index(ReleasePolicy.class);

    private final Session session;
    private final Log log;

    /**
     * Creates a verifier bound to the active session and logger.
     *
     * @param session the active Maven session (provides the resolver
     *                services, configured remotes, and credentials)
     * @param log     the release logger
     */
    public CoherenceVerifier(Session session, Log log) {
        this.session = session;
        this.log = log;
    }

    /**
     * The headline gate: confirm the just-released artifact resolves
     * cold at the demanded scope, throwing if it does not.
     *
     * <p>Resolves the artifact's POM (every released artifact has one,
     * regardless of packaging) against a fresh, empty local repository,
     * so success means a cache-less consumer could genuinely fetch what
     * this build published.
     *
     * @param groupId    the released artifact's groupId
     * @param artifactId the released artifact's artifactId
     * @param version    the released version (no {@code -SNAPSHOT})
     * @param scope      the demanded resolution scope (must be {@code ≥ NEXUS} for publish)
     * @throws MojoException if the artifact does not resolve at the demanded scope
     */
    public void verifySelfResolves(String groupId, String artifactId,
            String version, ResolutionScope scope) throws MojoException {
        if (scope == ResolutionScope.LOCAL) {
            // Verifies nothing; -publish rejects LOCAL upstream of here.
            log.info("Coherence gate: scope=local — skipped (verifies nothing).");
            return;
        }
        RemoteRepository repo = demandedRepository(scope);
        String coords = groupId + ":" + artifactId + ":pom:" + version;

        try (ColdLocalRepo cold = new ColdLocalRepo(session)) {
            ArtifactCoordinates ac = cold.session.createArtifactCoordinates(coords);
            cold.session.resolveArtifact(ac, List.of(repo));
            log.info("✓ Coherence gate: " + groupId + ":" + artifactId + ":"
                    + version + " resolves cold from " + scope.literalName()
                    + " (" + repo.getUrl() + ").");
        } catch (IOException e) {
            throw new MojoException("Coherence gate: could not create a cold "
                    + "local repository for self-resolution: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new MojoException(
                    "Release coherence gate FAILED — " + groupId + ":"
                    + artifactId + ":" + version + " is NOT resolvable from the"
                    + " demanded scope '" + scope.literalName() + "' ("
                    + repo.getUrl() + ").\n"
                    + "  A cold, cache-less resolver could not fetch the artifact"
                    + " this build just deployed.\n"
                    + "  Halting before tag-push so the cascade does not fire a"
                    + " downstream against a missing upstream (#705).\n"
                    + "  Likely cause: the deploy did not propagate to the group"
                    + " repo, or the demanded scope is wrong.\n"
                    + "  Resolver error: " + e.getMessage(), e);
        }
    }

    /**
     * The post-release coherence assert: fail loudly if any of this
     * module's auto-aligned upstream pins did not catch up to the latest
     * released upstream, judged against <em>fresh</em> metadata.
     *
     * <p>This is the safety net for the stale-metadata failure mode: the
     * B8 alignment step already raises these pins, but if it read a stale
     * metadata cache it could silently leave a pin behind (shipping an
     * incoherent build). Re-resolving cold here catches that.
     *
     * <p>Only edges this module would <em>auto-align</em> are asserted —
     * {@link ReleasePolicy#INTEGRATE} and {@link ReleasePolicy#RELEASE}.
     * A {@code notify}/{@code verify}/{@code propose} edge is
     * intentionally hand-gated and legitimately sits behind latest, so
     * asserting it would false-fail. The policy-read mirrors
     * {@code ReleasePrep.alignUpstreamProperties} (B8); keep the two in
     * sync.
     *
     * <p>A no-op for a non-cascade member, the cascade head, or a module
     * whose pins are all current.
     *
     * @param gitRoot the release working tree (its committed {@code pom.xml} is read)
     * @param scope   the demanded scope whose repo supplies the fresh "latest released"
     * @throws MojoException if an auto-aligned pin is behind the latest released upstream
     */
    public void assertUpstreamPinsCurrent(File gitRoot, ResolutionScope scope)
            throws MojoException {
        if (scope == ResolutionScope.LOCAL) {
            return;
        }
        Optional<ProjectCascade> loaded = ProjectCascadeIo.load(
                gitRoot.toPath().resolve(ProjectCascadeIo.MANIFEST_RELATIVE_PATH));
        if (loaded.isEmpty() || loaded.get().upstream().isEmpty()) {
            return;
        }
        File pomFile = new File(gitRoot, "pom.xml");
        String content;
        try {
            content = Files.readString(pomFile.toPath());
        } catch (IOException e) {
            throw new MojoException("Coherence assert: could not read "
                    + pomFile + ": " + e.getMessage(), e);
        }

        List<String> behind = new ArrayList<>();
        try (ColdLocalRepo cold = new ColdLocalRepo(session)) {
            CandidateVersionResolver resolver =
                    new SessionCandidateVersionResolver(cold.session);

            for (CascadeEdge up : loaded.get().upstream()) {
                if (!autoAligned(pomFile, up)) {
                    continue;  // hand-gated policy — legitimately may sit behind
                }
                boolean parentEdge = up.kind() == EdgeKind.PARENT;
                String property = up.versionProperty();
                String current = parentEdge
                        ? PomRewriter.readParentVersion(content,
                                up.groupId(), up.artifactId()).orElse(null)
                        : ReleaseSupport.readPomProperty(pomFile, property);
                if (!parentEdge && current == null) {
                    property = up.versionPropertyLegacy();
                    current = ReleaseSupport.readPomProperty(pomFile, property);
                }
                if (current == null || current.contains("${")) {
                    // No local pin, or pinned elsewhere — not this module's
                    // pin to assert.
                    continue;
                }
                String latest;
                try {
                    List<String> candidates = resolver.resolveCandidates(
                            up.groupId(), up.artifactId(), null);
                    latest = candidates.isEmpty() ? null
                            : candidates.get(candidates.size() - 1);
                } catch (RuntimeException e) {
                    // Resolution failure here is itself a coherence problem.
                    behind.add(up.ga() + ": could not re-resolve latest — "
                            + e.getMessage());
                    continue;
                }
                if (latest != null && MavenVersionComparator.INSTANCE
                        .compare(latest, current) > 0) {
                    behind.add(up.ga() + ": pin " + current
                            + " is behind latest released " + latest);
                }
            }
        } catch (IOException e) {
            throw new MojoException("Coherence assert: could not create a cold "
                    + "local repository: " + e.getMessage(), e);
        }

        if (!behind.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                    "Release coherence assert FAILED — this module's upstream"
                    + " pin(s) did not catch up to the latest released"
                    + " upstream (judged against fresh metadata):\n");
            for (String b : behind) {
                msg.append("  ").append(b).append('\n');
            }
            msg.append("  The B8 alignment step likely read stale metadata."
                    + " Halting before tag-push so the cascade does not ship an"
                    + " incoherent build (#705). Re-run the release with a"
                    + " refreshed cache.");
            throw new MojoException(msg.toString());
        }
    }

    /**
     * Whether this module would auto-align the given upstream edge —
     * delegating to {@link #autoAligned(File, String, String)} with the
     * edge's typed and legacy policy-property names.
     */
    private boolean autoAligned(File pomFile, CascadeEdge up) {
        return autoAligned(pomFile, up.policyProperty(), up.policyPropertyLegacy());
    }

    /**
     * Whether an upstream edge declared by the given policy properties
     * would be <em>auto-aligned</em> — i.e. its effective policy is
     * {@code integrate} (the default) or {@code release}, the two rungs
     * {@code ReleasePrep} B8 bumps without a human gate.
     *
     * <p>A {@code notify}/{@code verify}/{@code propose} edge is
     * hand-gated and legitimately sits behind latest, so it must NOT be
     * asserted current. The read mirrors
     * {@code ReleasePrep.alignUpstreamProperties} (B8): typed-marker
     * property first, legacy form next, then the {@code integrate}
     * default for an absent/blank/unresolved-reference value.
     *
     * <p>Package-private and static so it is unit-testable against a real
     * temp POM with no Maven session (TESTING.md mock-last).
     *
     * @param pomFile             the POM to read the policy from
     * @param policyProperty      the typed-marker policy property name
     * @param policyPropertyLegacy the pre-#525 legacy policy property name
     * @return {@code true} for an {@code integrate}/{@code release} edge
     */
    static boolean autoAligned(File pomFile, String policyProperty,
            String policyPropertyLegacy) {
        String policyValue = ReleaseSupport.readPomProperty(pomFile, policyProperty);
        if (policyValue == null) {
            policyValue = ReleaseSupport.readPomProperty(pomFile, policyPropertyLegacy);
        }
        if (policyValue != null) {
            policyValue = policyValue.trim();
        }
        if (policyValue == null || policyValue.isEmpty() || policyValue.contains("${")) {
            policyValue = ReleasePolicy.INTEGRATE.literalName();
        }
        ReleasePolicy policy = RELEASE_POLICY_INDEX.get(policyValue);
        return policy == ReleasePolicy.INTEGRATE || policy == ReleasePolicy.RELEASE;
    }

    /**
     * The remote repository for the demanded scope — preferring the
     * session's own configured repo of that id (so credentials, proxy,
     * and mirror settings are honoured), falling back to a synthesized
     * one at the well-known URL.
     */
    private RemoteRepository demandedRepository(ResolutionScope scope) {
        String wantId = scope == ResolutionScope.CENTRAL
                ? Repository.CENTRAL_ID
                : NEXUS_PUBLIC_ID;
        for (RemoteRepository r : session.getRemoteRepositories()) {
            if (wantId.equals(r.getId())) {
                return r;
            }
        }
        String url = scope == ResolutionScope.CENTRAL ? CENTRAL_URL : NEXUS_PUBLIC_URL;
        return session.createRemoteRepository(wantId, url);
    }
}

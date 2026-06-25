package network.ike.plugin.release.prep;

import network.ike.plugin.CascadeBump;
import network.ike.plugin.PomRewriter;
import network.ike.plugin.ReleaseNotesSupport;
import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.SnapshotScanner;
import network.ike.plugin.release.ReleaseContext;
import network.ike.plugin.release.coherence.ColdLocalRepo;
import network.ike.support.enums.ConstantBackedEnum;
import network.ike.support.enums.ReleasePolicy;
import network.ike.plugin.scaffold.FoundationBaker;
import network.ike.plugin.scaffold.ScaffoldManifest;
import network.ike.plugin.scaffold.ScaffoldManifestIo;
import network.ike.plugin.support.version.CandidateVersionResolver;
import network.ike.plugin.support.version.MavenVersionComparator;
import network.ike.plugin.support.version.SessionCandidateVersionResolver;
import network.ike.workspace.cascade.CascadeEdge;
import network.ike.workspace.cascade.EdgeKind;
import network.ike.workspace.cascade.ProjectCascade;
import network.ike.workspace.cascade.ProjectCascadeIo;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.MojoException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The release prep phase — B1–B12 of the {@code ReleaseDraftMojo}
 * block audit minus the version/branch resolution that has to run
 * before {@link ReleaseContext} can be constructed.
 *
 * <p>Concretely:
 *
 * <ol>
 *   <li>B3 — require a clean worktree</li>
 *   <li>B4 — {@code gh auth status} / {@code gh repo view} preflight</li>
 *   <li>B5 — {@code preflightJavadoc} (warnings hard-fail in publish, log in draft)</li>
 *   <li>B6 — SNAPSHOT-in-properties scan</li>
 *   <li>B7 — bake the foundation snapshot if this release owns the scaffold manifest</li>
 *   <li>B8 — align upstream cascade {@code ${X.version}} pins to latest released versions</li>
 *   <li>B9 — resolve the reproducible-build timestamp from the current HEAD commit</li>
 *   <li>B11 — {@code preflightChecks} (gh CLI, milestone, release-cadence trailers, Maven wrapper)</li>
 *   <li>B12 — final pre-cut validation; rolled into the orchestrator's logAudit for now</li>
 * </ol>
 *
 * <p>The B10 draft-mode short-circuit lives in the orchestrator
 * ({@code ReleaseDraftMojo.runGoal()}) — it reads
 * {@link PrepOutcome#draftMode()} and dispatches to the draft renderer
 * instead of continuing into the publish-only phases. The draft-renderer
 * helpers ({@code reportCascade}, {@code buildReleaseReport}) extract
 * to their own classes in Commit 6.
 *
 * <p>Carved out of {@code ReleaseDraftMojo} during the Phase 4
 * Commit 5 (IKE-Network/ike-issues#489).
 */
public final class ReleasePrep {

    /**
     * Release-cadence commit subjects — the tool-generated bookkeeping
     * commits the release flow itself produces ({@code release: …},
     * {@code post-release: …}, the {@code merge: release …} commit,
     * {@code site: publish …}). They legitimately carry no issue
     * trailer and must be exempt from the trailer-compliance check,
     * or every release would fail its own preflight on the previous
     * cycle's bookkeeping (IKE-Network/ike-issues#428).
     */
    private static final Pattern RELEASE_CADENCE = Pattern.compile(
            "^(release: .+"
                    + "|post-release: .+"
                    + "|merge: release .+"
                    + "|site: publish .+)$");

    /**
     * {@link ReleasePolicy} indexed by literal rung name
     * ({@code notify}, {@code verify}, {@code propose},
     * {@code integrate}, {@code release}).
     */
    private static final Map<String, ReleasePolicy> RELEASE_POLICY_INDEX =
            ConstantBackedEnum.index(ReleasePolicy.class);

    private final ReleaseContext ctx;
    private final Session session;

    /**
     * Creates a new release prep phase bound to the given context.
     *
     * @param ctx     the per-invocation release context (built before
     *                {@code mvnw} is resolved — prep does not need the wrapper)
     * @param session the active Maven session, needed to construct the
     *                {@link SessionCandidateVersionResolver} for foundation-bake
     *                and upstream-property alignment
     */
    public ReleasePrep(ReleaseContext ctx, Session session) {
        this.ctx = ctx;
        this.session = session;
    }

    /**
     * Executes the release prep phase.
     *
     * @return a {@link PrepOutcome} carrying {@code projectId},
     *         {@code hasOrigin}, {@code releaseTimestamp}, and the
     *         {@code draftMode} dispatch flag
     * @throws MojoException on a worktree-state, preflight, javadoc,
     *                       SNAPSHOT, or upstream-alignment failure
     */
    public PrepOutcome execute() throws MojoException {
        File gitRoot = ctx.gitRoot();
        File rootPom = new File(gitRoot, "pom.xml");
        String projectId = ReleaseSupport.readPomArtifactId(rootPom);
        boolean publish = ctx.request().publish();
        boolean draft = !publish;

        // B3 — validate clean worktree (cheap check)
        ReleaseSupport.requireCleanWorktree(gitRoot);

        // B4 + B11 — preflight: verify external connectivity before any work.
        // Each check is non-destructive and idempotent; failures happen in
        // seconds, not after a 10-minute build.
        boolean hasOrigin = ReleaseSupport.hasRemote(gitRoot, "origin");
        if (publish) {
            preflightChecks(hasOrigin, projectId, ctx.request().releaseVersion());
        }

        // B5 — javadoc preflight (#168). Runs in both modes; publish hard-fails
        // on warnings, draft logs them so the user sees what would block release.
        preflightJavadoc(publish);

        // B6 — SNAPSHOT-in-properties preflight (#175, #177): Maven 4's
        // consumer POM flattener resolves properties and promotes
        // pluginManagement into plugins when writing the released
        // artifact. If a <properties> value ends in -SNAPSHOT it leaks
        // into the released POM as a literal, breaking downstream
        // builds. Catch before any mutation — publish hard-fails, draft warns.
        List<SnapshotScanner.Violation> propViolations =
                SnapshotScanner.scanSourceProperties(rootPom);
        if (!propViolations.isEmpty()) {
            String msg = SnapshotScanner.formatViolations(propViolations, gitRoot,
                    propViolations.size() + " SNAPSHOT property value(s) would"
                            + " leak into released POMs:",
                    "  These values are resolved by Maven 4's consumer POM\n"
                    + "  flattener and baked into released artifacts. Bump\n"
                    + "  each property to a released (non-SNAPSHOT) version\n"
                    + "  before re-running the release.");
            if (publish) {
                throw new MojoException(msg);
            }
            ctx.log().warn(msg);
        }

        // B7 — foundation bake (#414): when this release owns the scaffold
        // manifest, refresh foundation: pins to latest released versions.
        bakeFoundationSnapshot(draft);

        // B8 — upstream cascade alignment (#419): bump this repo's
        // ${X.version} pins to latest released upstreams so a single-repo
        // release never ships on a stale foundation. The applied upgrades
        // flow to the release notes so a cascade-only rebuild announces
        // what it was rebuilt against rather than "no changes" (#706).
        List<CascadeBump> foundationUpgrades = alignUpstreamProperties(draft);

        // B9 — derive reproducible-build timestamp from current HEAD commit.
        String releaseTimestamp = resolveCommitTimestamp();

        return new PrepOutcome(projectId, hasOrigin, releaseTimestamp, draft,
                foundationUpgrades);
    }

    /**
     * Returns the ISO-8601 UTC timestamp of the current HEAD commit.
     *
     * <p>Using the commit timestamp (not wall-clock time) for
     * {@code project.build.outputTimestamp} ensures that two independent
     * builds from the same tag produce identical byte-for-byte output.
     * Wall-clock time would differ between the developer build and the
     * verification build, defeating reproducibility.
     *
     * <p>Falls back to the current wall-clock time if git is unavailable.
     */
    private String resolveCommitTimestamp() {
        File gitRoot = ctx.gitRoot();
        try {
            // %cI = commit timestamp in strict ISO 8601 format
            String raw = ReleaseSupport.execCapture(gitRoot,
                    "git", "log", "-1", "--format=%cI", "HEAD");
            // Normalise to the yyyy-MM-dd'T'HH:mm:ss'Z' form Maven expects
            return DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(OffsetDateTime.parse(raw).toInstant());
        } catch (Exception e) {
            ctx.log().warn("Could not read HEAD commit timestamp; falling back to wall-clock: "
                    + e.getMessage());
            return DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now());
        }
    }

    /**
     * Release-prep foundation bake (IKE-Network/ike-issues#414).
     *
     * <p>When the release being cut owns the scaffold manifest — i.e.
     * this is the {@code ike-tooling} release — refresh the manifest's
     * {@code foundation:} block to the latest released {@code ike-parent},
     * {@code ike-docs}, and {@code ike-platform} versions, so the
     * scaffold zip {@code ike-tooling} ships always carries a current
     * compatibility snapshot with no manual edit. A no-op for every
     * other project's release (no scaffold manifest present).
     *
     * <p>A pin newer than any resolvable GA, or one that cannot be
     * resolved at all, fails a publish (warns a draft): staleness or a
     * misconfigured remote must never be silently baked into the zip.
     *
     * @param draft {@code true} to report only; {@code false} to
     *              rewrite the manifest and commit it
     * @throws MojoException on a backward or unresolvable pin in
     *                       publish mode, or on an I/O failure
     */
    private void bakeFoundationSnapshot(boolean draft) throws MojoException {
        File gitRoot = ctx.gitRoot();
        boolean publish = !draft;
        File manifestFile = new File(gitRoot,
                "ike-build-standards/src/main/scaffold/scaffold-manifest.yaml");
        if (!manifestFile.isFile()) {
            // Not the ike-tooling release — nothing to bake.
            return;
        }

        String content;
        ScaffoldManifest manifest;
        try {
            content = Files.readString(manifestFile.toPath(),
                    StandardCharsets.UTF_8);
            manifest = ScaffoldManifestIo.read(manifestFile.toPath());
        } catch (IOException e) {
            throw new MojoException("Could not read scaffold manifest "
                    + manifestFile + ": " + e.getMessage(), e);
        }
        if (manifest.foundation() == null) {
            ctx.log().warn("Foundation bake: scaffold manifest has no "
                    + "foundation: block — skipping.");
            return;
        }

        List<FoundationBaker.Finding> findings;
        try {
            findings = FoundationBaker.assess(manifest.foundation(),
                    new SessionCandidateVersionResolver(session));
        } catch (RuntimeException e) {
            String msg = "Foundation bake: could not resolve latest "
                    + "released versions — " + e.getMessage();
            if (publish) {
                throw new MojoException(msg, e);
            }
            ctx.log().warn(msg);
            return;
        }

        List<FoundationBaker.Finding> problems = new ArrayList<>();
        List<FoundationBaker.Finding> bumps = new ArrayList<>();
        for (FoundationBaker.Finding f : findings) {
            switch (f.status()) {
                case AHEAD -> bumps.add(f);
                case BEHIND, UNRESOLVED -> problems.add(f);
                case CURRENT -> { }
            }
        }

        if (!problems.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                    "Foundation bake found pin(s) that cannot be baked:\n");
            for (FoundationBaker.Finding f : problems) {
                msg.append("  ").append(f.coordinate().label()).append(": ");
                if (f.status() == FoundationBaker.Status.UNRESOLVED) {
                    msg.append("no released version resolved (current pin ")
                            .append(f.current()).append(").");
                } else {
                    msg.append("pin ").append(f.current())
                            .append(" is newer than the latest released ")
                            .append(f.latest()).append(" — a backward bake.");
                }
                msg.append('\n');
            }
            msg.append("Verify the remote repository and the manifest "
                    + "foundation: block before releasing.");
            if (publish) {
                throw new MojoException(msg.toString());
            }
            ctx.log().warn(msg.toString());
        }

        if (bumps.isEmpty()) {
            ctx.log().info("Foundation bake: scaffold foundation: block "
                    + "already at the latest released versions.");
            return;
        }

        ctx.log().info("Foundation bake:");
        for (FoundationBaker.Finding f : bumps) {
            ctx.log().info("  " + (draft ? "→ " : "✓ ")
                    + f.coordinate().label() + ": "
                    + f.current() + " -> " + f.latest());
        }
        if (draft) {
            ctx.log().info("  [DRAFT] manifest not modified — publish would "
                    + "rewrite and commit scaffold-manifest.yaml.");
            return;
        }

        String updated = FoundationBaker.rewrite(content, findings);
        try {
            Files.writeString(manifestFile.toPath(), updated,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Could not write baked scaffold "
                    + "manifest " + manifestFile + ": " + e.getMessage(), e);
        }
        ReleaseSupport.exec(gitRoot, ctx.log(), "git", "add",
                "ike-build-standards/src/main/scaffold/scaffold-manifest.yaml");
        ReleaseSupport.exec(gitRoot, ctx.log(), "git", "commit", "-m",
                "release: bake foundation snapshot to latest GA");
    }

    /**
     * Aligns this repository's upstream-cascade {@code ${X.version}}
     * properties to the latest released version of each upstream
     * (IKE-Network/ike-issues#419, #420).
     *
     * <p>Before a foundation repo is released it must carry current
     * upstream pins, or it ships a stale foundation. This reads the
     * repo's own {@code src/main/cascade/release-cascade.yaml} and, for
     * every {@code upstream} edge, resolves the latest released (GA)
     * version of that upstream and bumps the edge's
     * {@code version-property} when the POM is behind. A property is
     * only advanced, never lowered.
     *
     * <p>The cascade head (no upstream edges) and ordinary consumers
     * (no {@code release-cascade.yaml}) are no-ops. In draft mode the
     * alignment is reported but not applied; in publish mode the bumps
     * are written and committed before the release branch is cut, so a
     * plain single-repo {@code ike:release-publish} is correct on its own.
     *
     * @param draft {@code true} to report only; {@code false} to
     *              rewrite the POM and commit
     * @return the upgrades actually applied (empty in draft mode, when
     *         this repo is not a cascade member, or when every pin was
     *         already current) — surfaced into the align commit message
     *         and the release notes (IKE-Network/ike-issues#706)
     * @throws MojoException on an unresolvable upstream or a missing
     *                       {@code version-property} in publish mode,
     *                       or on an I/O failure
     */
    private List<CascadeBump> alignUpstreamProperties(boolean draft) throws MojoException {
        File gitRoot = ctx.gitRoot();
        Optional<ProjectCascade> loaded = ProjectCascadeIo.load(
                gitRoot.toPath().resolve(
                        ProjectCascadeIo.MANIFEST_RELATIVE_PATH));
        if (loaded.isEmpty() || loaded.get().upstream().isEmpty()) {
            // Not a cascade member, or the cascade head — nothing
            // upstream to align.
            return List.of();
        }

        File pomFile = new File(gitRoot, "pom.xml");
        String content;
        try {
            content = Files.readString(pomFile.toPath(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Could not read " + pomFile
                    + " for upstream cascade alignment: "
                    + e.getMessage(), e);
        }

        List<CascadeBump> bumps = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String updated = content;

        // Resolve "latest released" against FRESH metadata (#705). A
        // normal resolver trusts the local metadata cache (daily update
        // policy); if that cache is stale — or Nexus hasn't finished
        // propagating a just-deployed upstream — B8 would see the OLD
        // version, leave the pin untouched, and ship an incoherent
        // build (the ike-platform v110 incident, 2026-06-18). An empty
        // local repo forces a real metadata fetch from the remotes.
        ColdLocalRepo cold;
        try {
            cold = new ColdLocalRepo(session);
        } catch (IOException e) {
            throw new MojoException("Could not create a fresh-metadata"
                    + " resolver for upstream cascade alignment: "
                    + e.getMessage(), e);
        }
        try {
        CandidateVersionResolver resolver =
                new SessionCandidateVersionResolver(cold.session);

        for (CascadeEdge up : loaded.get().upstream()) {
            // ── Resolve policy (IKE-Network/ike-issues#498, #525) ────
            // <G>__GA__<A>__POLICY (typed-marker family) declares how
            // this project responds when the upstream releases. The
            // pre-#525 form (<G>·<A>·policy) is also accepted during
            // the foundation cascade transition. Default (no property
            // declared) is INTEGRATE — bump the pin in place, no
            // human gate. Policy is read and validated BEFORE the
            // upstream-version resolution so the dispatch below sees
            // the full gap context (current pin + latest released)
            // when reporting to the operator. The policy property is
            // also validated at consumer build time by
            // ike-version-management-extension; ReleasePrep re-validates
            // here so a release run gives a clear error even on
            // consumers that don't register that extension.
            String policyKey = up.policyProperty();
            String policyValue = ReleaseSupport.readPomProperty(pomFile, policyKey);
            if (policyValue == null) {
                // Transition fallback: pre-#525 ·policy form
                policyKey = up.policyPropertyLegacy();
                policyValue = ReleaseSupport.readPomProperty(pomFile, policyKey);
            }
            if (policyValue != null) {
                policyValue = policyValue.trim();
            }
            if (policyValue == null || policyValue.isEmpty() || policyValue.contains("${")) {
                policyValue = ReleasePolicy.INTEGRATE.literalName();
            }
            ReleasePolicy policy = RELEASE_POLICY_INDEX.get(policyValue);
            if (policy == null) {
                problems.add(up.ga() + ": unrecognized policy '"
                        + policyValue + "' for property " + policyKey
                        + " — must be one of " + RELEASE_POLICY_INDEX.keySet() + ".");
                continue;
            }

            // ── Read current pin value ───────────────────────────────
            // PARENT-kind edges rewrite the <parent><version> block
            // directly; property-kind edges rewrite the version-pin
            // property that pins the upstream. The site of the value
            // (the read and the write) differs by kind; the
            // candidate-resolution and "is the pin stale" logic is
            // the same for both.
            //
            // For property-kind edges we look up the typed-marker
            // form (<G>__GA__<A>__VERSION, post-#525) first, then
            // fall back to the legacy form (<G>·<A>) so the cascade
            // works on both pre- and post-#525 POMs during the
            // transition. Whichever form resolves becomes the write
            // target — the form is preserved naturally.
            boolean parentEdge = up.kind() == EdgeKind.PARENT;
            String property = up.versionProperty();
            String current = parentEdge
                    ? PomRewriter.readParentVersion(content,
                            up.groupId(), up.artifactId()).orElse(null)
                    : ReleaseSupport.readPomProperty(pomFile, property);
            if (!parentEdge && current == null) {
                // Transition fallback: pre-#525 ·-form pin
                property = up.versionPropertyLegacy();
                current = ReleaseSupport.readPomProperty(pomFile, property);
            }
            String displaySite = parentEdge
                    ? "<parent>" + up.ga() + "</parent>"
                    : "<" + property + ">";
            if (current == null) {
                problems.add(up.ga() + ": POM has no " + displaySite
                        + ".");
                continue;
            }
            if (current.contains("${")) {
                // Value is itself a property reference — the canonical
                // pin lives elsewhere (typically ike-base-parent). No
                // local action regardless of policy.
                continue;
            }

            // ── Resolve latest released upstream version ─────────────
            String latest;
            try {
                List<String> candidates = resolver.resolveCandidates(
                        up.groupId(), up.artifactId(), null);
                latest = candidates.isEmpty() ? null
                        : candidates.get(candidates.size() - 1);
            } catch (RuntimeException e) {
                problems.add(up.ga() + ": could not resolve latest"
                        + " release — " + e.getMessage());
                continue;
            }
            if (latest == null) {
                problems.add(up.ga()
                        + ": no released version resolved.");
                continue;
            }
            if (MavenVersionComparator.INSTANCE
                    .compare(latest, current) <= 0) {
                // Pin already at or ahead of latest released — no gap.
                // No policy dispatch needed; nothing to act on.
                continue;
            }

            // ── Policy dispatch with full gap context ────────────────
            boolean autoAlign = switch (policy) {
                case INTEGRATE -> true;
                case RELEASE -> {
                    ctx.log().info("  " + up.ga() + " " + current + " → "
                            + latest + " (policy=release; aligning + downstream"
                            + " release follows via cascade).");
                    yield true;
                }
                case NOTIFY -> {
                    ctx.log().warn("  " + up.ga() + " has a new release: "
                            + current + " → " + latest
                            + " (policy=notify; pin not auto-updated).");
                    ctx.log().warn("    Update manually: mvn versions:set-property"
                            + " -Dproperty=" + property + " -DnewVersion=" + latest);
                    ctx.log().warn("    Or change " + policyKey
                            + " to `integrate` for automatic alignment.");
                    yield false;
                }
                case VERIFY -> {
                    ctx.log().warn("  " + up.ga() + " has a new release: "
                            + current + " → " + latest + " (policy=verify).");
                    if (draft) {
                        ctx.log().warn("    [DRAFT] Would create a git-worktree"
                                + " sandbox and run `mvn verify` with the bump;"
                                + " no action in draft mode.");
                    } else {
                        String bumpedContent = parentEdge
                                ? PomRewriter.updateParentVersion(content,
                                        up.groupId(), up.artifactId(), latest)
                                : PomRewriter.updateProperty(content,
                                        property, latest);
                        File sandbox = new File(
                                System.getProperty("java.io.tmpdir"),
                                "ike-verify-" + gitRoot.getName()
                                        + "-" + up.artifactId()
                                        + "-v" + latest);
                        boolean verified = verifyUpstreamBump(gitRoot,
                                sandbox, bumpedContent);
                        if (verified) {
                            ctx.log().info("    ✓ " + up.ga()
                                    + " bump verified — consumer still builds"
                                    + " with " + latest + ".");
                            ctx.log().info("    Pin not auto-updated (policy=verify"
                                    + " is hand-gated). To integrate: change "
                                    + policyKey + " to `integrate`, or apply"
                                    + " manually with mvn versions:set-property"
                                    + " -Dproperty=" + property + " -DnewVersion="
                                    + latest);
                        } else {
                            ctx.log().warn("    ✗ " + up.ga()
                                    + " bump FAILED verify — see sandbox log."
                                    + " Investigation required before integration.");
                        }
                    }
                    yield false;
                }
                case PROPOSE -> {
                    String proposeBranch = "propose/" + up.artifactId()
                            + "-v" + latest;
                    ctx.log().warn("  " + up.ga() + " has a new release: "
                            + current + " → " + latest
                            + " (policy=propose).");
                    if (draft) {
                        ctx.log().warn("    [DRAFT] Would create branch "
                                + proposeBranch + " with the bump applied;"
                                + " not modifying anything in draft mode.");
                    } else if (branchExistsLocally(gitRoot, proposeBranch)) {
                        ctx.log().warn("    Branch " + proposeBranch
                                + " already exists — leaving it for the"
                                + " operator to integrate.");
                    } else {
                        String bumpedContent = parentEdge
                                ? PomRewriter.updateParentVersion(content,
                                        up.groupId(), up.artifactId(), latest)
                                : PomRewriter.updateProperty(content,
                                        property, latest);
                        boolean hasOrigin = ReleaseSupport.hasRemote(gitRoot, "origin");
                        createProposeBranch(gitRoot, proposeBranch, pomFile,
                                bumpedContent,
                                "propose: bump " + up.ga() + " "
                                        + current + " → " + latest,
                                hasOrigin);
                        ctx.log().warn("    Created branch " + proposeBranch
                                + (hasOrigin
                                        ? " and pushed to origin."
                                        : " (local only — no origin remote)."));
                        if (hasOrigin) {
                            ctx.log().warn("    Open a PR: gh pr create"
                                    + " --head " + proposeBranch);
                        }
                    }
                    yield false;
                }
            };
            if (!autoAlign) {
                continue;
            }

            // ── Apply the bump ───────────────────────────────────────
            String after = parentEdge
                    ? PomRewriter.updateParentVersion(updated,
                            up.groupId(), up.artifactId(), latest)
                    : PomRewriter.updateProperty(updated, property,
                            latest);
            if (!after.equals(updated)) {
                updated = after;
                bumps.add(new CascadeBump(up.groupId(), up.artifactId(),
                        current, latest));
            }
        }
        } finally {
            cold.close();
        }

        if (!problems.isEmpty()) {
            StringBuilder msg = new StringBuilder("Upstream cascade"
                    + " alignment found unresolvable upstream pin(s):\n");
            for (String p : problems) {
                msg.append("  ").append(p).append('\n');
            }
            msg.append("Verify the remote repository and the upstream"
                    + " edges in release-cascade.yaml before releasing.");
            if (!draft) {
                throw new MojoException(msg.toString());
            }
            ctx.log().warn(msg.toString());
        }

        if (bumps.isEmpty()) {
            ctx.log().info("Upstream cascade alignment: ${X.version}"
                    + " pins already at the latest released versions.");
            return List.of();
        }

        ctx.log().info("Upstream cascade alignment:");
        for (CascadeBump b : bumps) {
            ctx.log().info("  " + (draft ? "→ " : "✓ ") + b.ga()
                    + ": " + b.current() + " -> " + b.latest());
        }
        if (draft) {
            ctx.log().info("  [DRAFT] pom.xml not modified — publish"
                    + " would rewrite and commit it.");
            // Draft must not leak bumps as "applied": nothing was
            // written or committed, so the draft report has no real
            // upgrades to announce (IKE-Network/ike-issues#706).
            return List.of();
        }

        try {
            Files.writeString(pomFile.toPath(), updated,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Could not write aligned " + pomFile
                    + ": " + e.getMessage(), e);
        }
        ReleaseSupport.exec(gitRoot, ctx.log(), "git", "add", "pom.xml");
        // Descriptive commit message naming each upgrade — a cascade-only
        // rebuild's commit is otherwise generic and the notes generator
        // treats a generic "release:" commit as noise (#706). e.g.
        // "release: align upstream cascade — ike-tooling 221→222, ike-docs 75→76"
        String summary = bumps.stream()
                .map(CascadeBump::compact)
                .collect(Collectors.joining(", "));
        ReleaseSupport.exec(gitRoot, ctx.log(), "git", "commit", "-m",
                "release: align upstream cascade — " + summary);
        return bumps;
    }

    /**
     * Runs {@code mvn verify} against the consumer with a hypothetical
     * upstream-pin bump applied, in a {@code git worktree} sandbox
     * that doesn't disturb the release worktree.
     *
     * <p>The sandbox lives under {@code java.io.tmpdir} for the
     * duration of the verify, then is removed via
     * {@code git worktree remove --force}. The shared {@code .git/}
     * directory means the sandbox carries no history-copy cost; it
     * is just a parallel working tree at the same HEAD with the
     * proposed POM applied.
     *
     * <p>The mvnw inherited into the sandbox is the same script the
     * release-flow itself uses, so the verify exercises the consumer's
     * exact Maven toolchain. Output streams through the shared logger
     * so the operator sees verify progress in real time.
     *
     * <p>Best-effort cleanup: a stale sandbox from an interrupted
     * prior run is removed before the new worktree is added; if the
     * final remove fails, the operator can clean up with
     * {@code git worktree remove --force <path>}.
     *
     * @param gitRoot       project working tree (used for the
     *                      {@code git worktree} subcommands)
     * @param sandbox       desired sandbox directory (absolute; must
     *                      not already be a worktree of this repo)
     * @param bumpedContent the POM content with the upstream pin advanced
     * @return {@code true} when {@code mvn verify} exits zero,
     *         {@code false} otherwise
     */
    private boolean verifyUpstreamBump(File gitRoot, File sandbox,
                                       String bumpedContent) {
        // Remove any stale sandbox from a prior interrupted run.
        if (sandbox.exists()) {
            try {
                ReleaseSupport.exec(gitRoot, ctx.log(),
                        "git", "worktree", "remove", "--force",
                        sandbox.getAbsolutePath());
            } catch (RuntimeException ignored) {
                // Not a registered worktree — try a plain remove.
            }
        }
        try {
            ReleaseSupport.exec(gitRoot, ctx.log(),
                    "git", "worktree", "add", sandbox.getAbsolutePath(), "HEAD");
        } catch (RuntimeException e) {
            ctx.log().warn("    Could not create verify sandbox at "
                    + sandbox + ": " + e.getMessage());
            return false;
        }
        try {
            Files.writeString(
                    new File(sandbox, "pom.xml").toPath(),
                    bumpedContent, StandardCharsets.UTF_8);
            File sandboxMvnw = new File(sandbox, "mvnw");
            // The verify goal is configurable per IKE-Network/ike-issues#510
            // — operators can dial down to `test`, `package`, or
            // `compile` when full `verify` is too slow for the
            // upstream-bump assurance they want.
            String verifyGoal = System.getProperty(
                    "ike.policy.verify.goal", "verify");
            ctx.log().info("    Running `mvnw " + verifyGoal
                    + "` in sandbox: " + sandbox);
            ReleaseSupport.exec(sandbox, ctx.log(),
                    sandboxMvnw.getAbsolutePath(), verifyGoal, "-B");
            return true;
        } catch (IOException e) {
            ctx.log().warn("    Could not write bumped POM to sandbox: "
                    + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // mvn verify subprocess failed — message already streamed.
            return false;
        } finally {
            try {
                ReleaseSupport.exec(gitRoot, ctx.log(),
                        "git", "worktree", "remove", "--force",
                        sandbox.getAbsolutePath());
            } catch (RuntimeException e) {
                ctx.log().warn("    Could not remove verify sandbox at "
                        + sandbox + ": " + e.getMessage()
                        + " — clean up manually with"
                        + " `git worktree remove --force " + sandbox + "`.");
            }
        }
    }

    /**
     * Returns {@code true} when {@code branchName} resolves to a local
     * git ref. Used by the {@code propose} policy arm to skip
     * recreating an existing release-gate branch.
     */
    private static boolean branchExistsLocally(File gitRoot, String branchName) {
        try {
            ReleaseSupport.execCapture(gitRoot, "git", "rev-parse",
                    "--verify", "refs/heads/" + branchName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Creates a {@code propose/…} release-gate branch carrying a single
     * commit that applies a proposed upstream-pin bump.
     *
     * <p>Sequence: cut a new branch from the current HEAD, write the
     * bumped POM content, stage + commit, push to {@code origin} when
     * available, then force-checkout back to the original branch.
     * The final checkout runs in a {@code finally} so a push failure
     * doesn't strand the worktree on the propose branch.
     *
     * @param gitRoot       project working tree
     * @param branchName    the propose branch name to create
     * @param pomFile       the POM file to overwrite with {@code bumpedContent}
     * @param bumpedContent the POM content with the upstream pin advanced
     * @param commitMessage the commit message for the bump
     * @param push          when {@code true}, also push the new branch
     *                      to {@code origin} with upstream tracking
     */
    private void createProposeBranch(File gitRoot, String branchName,
                                      File pomFile, String bumpedContent,
                                      String commitMessage, boolean push) {
        String currentBranch = ReleaseSupport.currentBranch(gitRoot);
        try {
            ReleaseSupport.exec(gitRoot, ctx.log(),
                    "git", "checkout", "-b", branchName);
            try {
                Files.writeString(pomFile.toPath(), bumpedContent,
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new MojoException("Could not write "
                        + pomFile + " on propose branch "
                        + branchName + ": " + e.getMessage(), e);
            }
            ReleaseSupport.exec(gitRoot, ctx.log(),
                    "git", "add", "pom.xml");
            ReleaseSupport.exec(gitRoot, ctx.log(),
                    "git", "commit", "-m", commitMessage);
            if (push) {
                ReleaseSupport.exec(gitRoot, ctx.log(),
                        "git", "push", "-u", "origin", branchName);
            }
        } finally {
            // Always return to the original branch; force checkout
            // overwrites any stray uncommitted worktree state from a
            // failure mid-sequence.
            ReleaseSupport.exec(gitRoot, ctx.log(),
                    "git", "checkout", "-f", currentBranch);
        }
    }

    /**
     * Verifies all external dependencies before starting the release.
     *
     * <p>Each check is non-destructive and fast — failures here happen
     * in seconds instead of after a 10-minute build cycle. Every check
     * runs to completion and records into one of two buckets rather
     * than failing fast, so a single run logs the complete picture of
     * everything wrong (IKE-Network/ike-issues#428):
     * <ul>
     *   <li><b>errors</b> — git-push authentication, {@code gh} push
     *       permission on {@code issueRepo}, a missing Maven wrapper.
     *       Always abort the release; never ignorable.</li>
     *   <li><b>warnings</b> — {@code gh} CLI unavailable, a missing
     *       {@code pending-release} label or release milestone,
     *       commits with no issue trailer. Abort the release too,
     *       unless {@code -Dike.release.ignoreWarnings=true}.</li>
     * </ul>
     *
     * <p>Only invoked for a publish; draft mode skips this step.
     *
     * @param hasOrigin      whether an {@code origin} remote is configured
     * @param projectId      the project artifactId, for the milestone name
     * @param releaseVersion the version being released
     * @throws MojoException if any preflight error is found, or any
     *                       warning is found and {@code ignoreWarnings}
     *                       is not set
     */
    private void preflightChecks(boolean hasOrigin, String projectId, String releaseVersion)
            throws MojoException {
        File gitRoot = ctx.gitRoot();
        String issueRepo = ctx.request().issueRepo();
        boolean ignoreWarnings = ctx.request().ignoreWarnings();

        ctx.log().info("");
        ctx.log().info("PREFLIGHT CHECKS");
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. Git push auth — draft push (sends nothing, tests auth)
        if (hasOrigin) {
            try {
                ReleaseSupport.execCapture(gitRoot,
                        "git", "push", "--dry-run", "origin", "main");
                ctx.log().info("  Git push:    authenticated  ✓");
            } catch (Exception e) {
                errors.add("Cannot push to origin — fix authentication"
                        + " before releasing. Error: " + e.getMessage());
                ctx.log().error("  Git push:    authentication failed  ✗");
            }
        } else {
            ctx.log().info("  Git push:    no origin remote (local-only release)");
        }

        // 2. gh CLI — installed and authenticated?
        boolean ghAvailable = false;
        if (hasOrigin) {
            try {
                ReleaseSupport.execCapture(gitRoot, "gh", "auth", "status");
                ctx.log().info("  gh CLI:      authenticated  ✓");
                ghAvailable = true;
            } catch (Exception e) {
                warnings.add("gh CLI not available or not authenticated — "
                        + "GitHub Release will be skipped. "
                        + "Run: gh auth login");
                ctx.log().warn("  gh CLI:      not available (GitHub Release "
                        + "will be skipped)");
            }
        }

        // 3. gh write permission on issueRepo (#392) — an error.
        if (ghAvailable && issueRepo != null && !issueRepo.isBlank()) {
            try {
                String pushPerm = ReleaseSupport.execCapture(gitRoot,
                        "gh", "api", "/repos/" + issueRepo,
                        "--jq", ".permissions.push");
                if ("true".equals(pushPerm.trim())) {
                    ctx.log().info("  gh perms:    push on "
                            + issueRepo + "  ✓");
                } else {
                    errors.add("gh token lacks push permission on "
                            + issueRepo + " — required for milestone"
                            + " close and pending-release label removal."
                            + " Re-authenticate with repo scope:"
                            + " gh auth refresh -s repo");
                    ctx.log().error("  gh perms:    no push on "
                            + issueRepo + "  ✗");
                }
            } catch (Exception e) {
                warnings.add("Could not verify gh permissions on "
                        + issueRepo + ": " + e.getMessage());
            }
        }

        // 4. pending-release label exists on issueRepo (#392) — warn.
        if (ghAvailable && issueRepo != null && !issueRepo.isBlank()) {
            try {
                ReleaseSupport.execCapture(gitRoot, "gh", "api",
                        "/repos/" + issueRepo + "/labels/pending-release");
                ctx.log().info("  pending-rel label on " + issueRepo + "  ✓");
            } catch (Exception e) {
                warnings.add("Label 'pending-release' missing on "
                        + issueRepo + " — label removal will be a no-op. "
                        + "Create it: gh label create pending-release "
                        + "--repo " + issueRepo
                        + " --description \"Code complete; awaiting next release\"");
                ctx.log().warn("  pending-rel label: missing on " + issueRepo);
            }
        }

        // 5. Trailer compliance for commits in release range (#392) — warn.
        if (hasOrigin) {
            List<String> nonCompliant = findCommitsWithoutIssueTrailer();
            if (nonCompliant.isEmpty()) {
                ctx.log().info("  Trailer compliance: all commits ✓");
            } else {
                StringBuilder msg = new StringBuilder(nonCompliant.size()
                        + " commit(s) in release range have no issue trailer "
                        + "(IKE-COMMITS.md):");
                for (String line : nonCompliant) {
                    msg.append("\n      ").append(line);
                }
                msg.append("\n  Add Fixes/Refs <owner>/<repo>#N to comply.");
                warnings.add(msg.toString());
                ctx.log().warn("  Trailer compliance: " + nonCompliant.size()
                        + " commit(s) without issue trailer");
            }
        }

        // 6. Milestone for releaseVersion exists on issueRepo (#392) — warn.
        if (ghAvailable && issueRepo != null && !issueRepo.isBlank()
                && releaseVersion != null && !releaseVersion.isBlank()) {
            String milestoneName = projectId + " v" + releaseVersion;
            try {
                String titles = ReleaseSupport.execCapture(gitRoot, "gh", "api",
                        "/repos/" + issueRepo + "/milestones?state=open&per_page=100",
                        "--jq", ".[].title");
                boolean found = false;
                for (String title : titles.split("\n")) {
                    if (milestoneName.equals(title.trim())) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    ctx.log().info("  Milestone:   " + milestoneName + "  ✓");
                } else {
                    warnings.add("Milestone \"" + milestoneName
                            + "\" not found on " + issueRepo
                            + " — release will use auto-generated notes. "
                            + "Create it: gh api /repos/" + issueRepo
                            + "/milestones -f title='" + milestoneName + "'");
                    ctx.log().warn("  Milestone:   " + milestoneName
                            + " missing (auto-notes fallback)");
                }
            } catch (Exception e) {
                warnings.add("Could not check milestone existence: "
                        + e.getMessage());
            }
        }

        // 7. Maven wrapper
        try {
            ReleaseSupport.resolveMavenWrapper(gitRoot, ctx.log());
            ctx.log().info("  Maven:       wrapper found  ✓");
        } catch (Exception e) {
            errors.add("Maven wrapper (mvnw) not found."
                    + " Run: mvn wrapper:wrapper");
            ctx.log().error("  Maven:       wrapper not found  ✗");
        }

        // 8. Site lint — catch drift in <url>/site.xml shapes before
        //    they ship as broken decoration links. Surfaced from the
        //    bannerRight-collapse incident (IKE-Network/ike-issues#521).
        //    Each finding is a warning (ignoreable via -Dike.release.ignoreWarnings=true)
        //    so a release can ship through known drift while we fix
        //    upstream.
        List<String> siteFindings = siteLintFindings(gitRoot, projectId);
        if (siteFindings.isEmpty()) {
            ctx.log().info("  Site lint:   no issues  ✓");
        } else {
            for (String f : siteFindings) {
                warnings.add("Site lint: " + f);
            }
            ctx.log().warn("  Site lint:   " + siteFindings.size()
                    + " issue(s)");
        }

        // Report the complete preflight picture, then decide (#428).
        if (!errors.isEmpty() || !warnings.isEmpty()) {
            ctx.log().info("");
            for (String err : errors) {
                ctx.log().error("  ✗ " + err);
            }
            for (String w : warnings) {
                ctx.log().warn("  ⚠ " + w);
            }
            ctx.log().info("");
        }

        if (!errors.isEmpty()) {
            throw new MojoException("Release preflight found "
                    + errors.size() + " error(s)"
                    + (warnings.isEmpty() ? ""
                            : " and " + warnings.size() + " warning(s)")
                    + " — see above. Errors must be resolved before"
                    + " releasing; they are never ignorable.");
        }
        if (!warnings.isEmpty()) {
            if (ignoreWarnings) {
                ctx.log().warn("  Proceeding past " + warnings.size()
                        + " warning(s) (ike.release.ignoreWarnings=true).");
            } else {
                throw new MojoException("Release preflight found "
                        + warnings.size() + " warning(s) — see above."
                        + " Resolve them, or pass"
                        + " -Dike.release.ignoreWarnings=true to release"
                        + " anyway.");
            }
        }
        ctx.log().info("");
    }

    /**
     * Finds commits in {@code <previous-tag>..HEAD} whose body contains
     * no IKE-COMMITS.md issue trailer ({@code Fixes}, {@code Closes},
     * {@code Resolves}, {@code Refs} and grammatical variants).
     *
     * <p>Uses NUL-delimited git-log output to handle commit messages
     * containing arbitrary characters. Returns short SHA + subject for
     * each non-compliant commit. Release-cadence commits ({@link
     * #RELEASE_CADENCE}) are exempt — they are tool-generated and
     * carry no issue trailer by design.
     *
     * <p>Returns an empty list (not an error) if the previous tag
     * cannot be resolved — typical for first-release scenarios.
     */
    private List<String> findCommitsWithoutIssueTrailer() {
        File gitRoot = ctx.gitRoot();
        try {
            String previousTag;
            try {
                previousTag = ReleaseSupport.execCapture(gitRoot,
                        "git", "describe", "--tags", "--abbrev=0", "HEAD");
            } catch (Exception e) {
                ctx.log().debug("  No previous tag — skipping trailer compliance");
                return List.of();
            }
            // Per-commit body separated by NUL byte (-z) so embedded
            // newlines don't confuse the parser.
            String log = ReleaseSupport.execCapture(gitRoot, "git", "log",
                    "-z", "--format=%h%x00%B", previousTag + "..HEAD");
            if (log.isBlank()) {
                return List.of();
            }
            List<String> nonCompliant = new ArrayList<>();
            // Stream is "<sha>\0<body>\0<sha>\0<body>\0..." after -z.
            // Splitting on NUL gives alternating sha/body pairs.
            String[] records = log.split("\u0000");
            for (int i = 0; i + 1 < records.length; i += 2) {
                String sha = records[i].trim();
                String body = records[i + 1];
                if (!ReleaseNotesSupport.hasAnyIssueTrailer(body)) {
                    String firstLine = body.contains("\n")
                            ? body.substring(0, body.indexOf('\n'))
                            : body;
                    String subject = firstLine.trim();
                    if (RELEASE_CADENCE.matcher(subject).matches()) {
                        // Tool-generated bookkeeping — no trailer by design.
                        continue;
                    }
                    nonCompliant.add(sha + " " + subject);
                }
            }
            return nonCompliant;
        } catch (Exception e) {
            ctx.log().debug("  Trailer compliance check failed: "
                    + e.getMessage());
            return List.of();
        }
    }

    /**
     * Checks that javadoc generation — as the release profile runs it —
     * produces no warnings across every reactor module.
     *
     * <p>On {@code publish} mode any warning aborts the release; on
     * draft mode warnings are logged so the user sees what would block
     * the real release. Skipped when no {@code src/main/java} tree
     * exists anywhere in the reactor (doc-only / POM-only repos have
     * nothing to check).
     *
     * <p>Matches the release path by invoking {@code mvn compile
     * javadoc:jar} across the reactor — the same goal the {@code
     * release} profile uses. {@code -DfailOnError=false
     * -DfailOnWarnings=false} prevent the child build from exiting
     * early so every module's warnings are collected in a single pass.
     *
     * @param publish {@code true} for publish mode (hard fail),
     *                {@code false} for draft mode (warn only)
     * @throws MojoException if publish mode and warnings are present
     */
    // ── Site lint (IKE-Network/ike-issues#521) ───────────────────────

    /**
     * Project {@code <url>} element pattern. Matches the {@code <url>}
     * that sits between {@code </description>} and {@code <inceptionYear>}
     * — the canonical position for a Maven {@code <url>} top-level
     * element. {@code <scm><url>} and {@code <license><url>} are not
     * picked up because they live elsewhere in the tree.
     */
    private static final Pattern PROJECT_URL_ELEMENT = Pattern.compile(
            "</description>\\s*<url>([^<]+)</url>",
            Pattern.DOTALL);

    /**
     * Matches a {@code <bannerRight>} declaration with a GitHub
     * {@code href}. Indicates the site's primary GitHub link lives in
     * header chrome — the canonical placement.
     */
    private static final Pattern BANNER_RIGHT_GITHUB = Pattern.compile(
            "<bannerRight[^>]*href=\"https://github\\.com/",
            Pattern.DOTALL);

    /**
     * Matches a {@code <links><item name="GitHub" ...></links>} block —
     * the redundant utility-bar link to GitHub that duplicates
     * {@code <bannerRight>} on per-project sites.
     */
    private static final Pattern LINKS_GITHUB_ITEM = Pattern.compile(
            "<links>\\s*<item\\s+name=\"GitHub\"",
            Pattern.DOTALL);

    /**
     * Inspect {@code pom.xml} and {@code src/site/site.xml} for known
     * drift patterns that produce broken site decoration links.
     *
     * <p>Rules:
     * <ol>
     *   <li>Project {@code <url>} must match
     *       {@code https://ike.network/<projectId>/}. If it points at
     *       GitHub instead (a common drift), {@code maven-site-plugin}
     *       relativizes {@code <bannerRight>}'s GitHub href to {@code ./} —
     *       a self-loop that breaks the link.</li>
     *   <li>{@code site.xml} must not have a GitHub link in BOTH
     *       {@code <bannerRight>} and a top-bar {@code <links>} item.
     *       Pick one (bannerRight is canonical) — the duplication is
     *       chrome noise.</li>
     * </ol>
     *
     * <p>No-op when {@code pom.xml} or {@code site.xml} is missing.
     * Findings are returned as warnings; the caller composes the
     * report block.
     *
     * @param gitRoot   the project's git root
     * @param projectId the project's artifact ID (drives the expected
     *                  {@code <url>} value)
     * @return zero or more human-readable findings; empty if everything
     *         passes
     */
    static List<String> siteLintFindings(File gitRoot, String projectId) {
        List<String> findings = new ArrayList<>();

        File pomFile = new File(gitRoot, "pom.xml");
        if (pomFile.isFile() && projectId != null && !projectId.isBlank()) {
            try {
                String pom = Files.readString(pomFile.toPath());
                Matcher m = PROJECT_URL_ELEMENT.matcher(pom);
                if (m.find()) {
                    String actual = m.group(1).trim();
                    String expected = "https://ike.network/" + projectId + "/";
                    if (!expected.equals(actual)) {
                        findings.add("pom.xml <url> is '" + actual
                                + "', expected '" + expected
                                + "' — site-plugin will relativize"
                                + " bannerRight to './' when the two"
                                + " URLs share a prefix.");
                    }
                }
            } catch (IOException e) {
                // tolerate read failure — release-publish has bigger
                // problems if pom.xml is unreadable, and that surfaces
                // in other checks.
            }
        }

        File siteXml = new File(gitRoot, "src/site/site.xml");
        if (siteXml.isFile()) {
            try {
                String descriptor = Files.readString(siteXml.toPath());
                boolean bannerRightGitHub = BANNER_RIGHT_GITHUB
                        .matcher(descriptor).find();
                boolean linksGitHub = LINKS_GITHUB_ITEM
                        .matcher(descriptor).find();
                if (bannerRightGitHub && linksGitHub) {
                    findings.add("src/site/site.xml has GitHub in both"
                            + " <bannerRight> and <links> — drop the"
                            + " <links> item; bannerRight is the"
                            + " canonical placement.");
                }
            } catch (IOException e) {
                // tolerate read failure
            }
        }

        return findings;
    }

    private void preflightJavadoc(boolean publish) throws MojoException {
        File gitRoot = ctx.gitRoot();
        if (!hasAnyJavaSource(gitRoot)) {
            return;
        }

        List<String> warnings = collectJavadocWarnings(gitRoot);
        ctx.log().info("");
        if (warnings.isEmpty()) {
            ctx.log().info("  Javadoc:     warning-free  ✓");
            return;
        }

        ctx.log().info("  Javadoc:     " + warnings.size()
                + " warning(s)  ✗");
        for (String w : warnings) {
            ctx.log().warn("    " + w);
        }

        if (publish) {
            throw new MojoException(
                    "Javadoc preflight failed: " + warnings.size()
                            + " warning(s) must be resolved before publish.\n"
                            + "  Convention: every public method needs"
                            + " complete @param / @return / @throws tags.");
        }
        ctx.log().warn("  (Draft mode — would block publish.)");
        ctx.log().info("");
    }

    /**
     * Returns {@code true} if {@code gitRoot} or any direct subdirectory
     * contains a {@code src/main/java} tree. Covers both single-module
     * and flat multi-module reactor layouts.
     */
    private static boolean hasAnyJavaSource(File gitRoot) {
        if (new File(gitRoot, "src/main/java").isDirectory()) {
            return true;
        }
        File[] entries = gitRoot.listFiles();
        if (entries == null) {
            return false;
        }
        for (File entry : entries) {
            if (!entry.isDirectory()) {
                continue;
            }
            if (new File(entry, "src/main/java").isDirectory()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs {@code mvn compile javadoc:jar} at {@code gitRoot} to mirror
     * the release's javadoc path across every reactor module, and
     * returns every line matching {@code warning:} stripped of the
     * leading {@code [WARNING] } prefix.
     *
     * <p>Tolerates subprocess failure so the release does not abort on
     * an infrastructure issue (a real javadoc failure will resurface
     * during the subsequent build phase).
     */
    private List<String> collectJavadocWarnings(File gitRoot) {
        List<String> warnings = new ArrayList<>();
        try {
            // -q stripped the [WARNING] prefix the grep below keys on,
            // letting javadoc "reference not found" warnings slip through
            // preflight (see ike-issues #178). -B keeps output non-interactive.
            Process proc = new ProcessBuilder(
                    "mvn", "-B",
                    "compile", "javadoc:jar",
                    "-DskipTests",
                    "-DfailOnError=false",
                    "-DfailOnWarnings=false")
                    .directory(gitRoot)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains("warning:")) {
                        continue;
                    }
                    warnings.add(line.replaceFirst(
                            "^\\[WARNING\\] ", "").strip());
                }
            }
            proc.waitFor();
        } catch (IOException | InterruptedException e) {
            ctx.log().debug("Javadoc preflight subprocess failed: "
                    + e.getMessage());
        }
        return warnings;
    }
}

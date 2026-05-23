package network.ike.plugin.release;

/**
 * Immutable user-supplied inputs for one invocation of the release pipeline.
 *
 * <p>Built by {@code ReleaseDraftMojo.runGoal()} from the mojo's
 * {@code @Parameter} fields, then carried through the phases via
 * {@link ReleaseContext}. Phases read configuration from
 * {@code ctx.request()} instead of touching mojo instance fields,
 * which keeps each phase testable in isolation once the
 * decomposition lands (IKE-Network/ike-issues#489).
 *
 * <p>Carved out of {@code ReleaseDraftMojo} during the Phase 4 P2
 * prep commit. Resolved values that the prep phase derives
 * ({@code oldVersion}, {@code newVersion}, {@code releaseBranch},
 * {@code projectId}, {@code releaseTimestamp}) intentionally live
 * in {@code PrepOutcome}, not here — the request captures user
 * input, the prep outcome captures derivations.
 *
 * @param releaseVersion             explicit release version override, or {@code null} to derive from POMs
 * @param nextVersion                explicit next-development version override, or {@code null} to derive
 * @param publish                    {@code true} for a real release, {@code false} for the draft preview
 * @param skipVerify                 skip the pre-flight verify pass (development-only convenience)
 * @param allowBranch                permit running from a branch other than {@code main}, or {@code null}
 * @param publishSite                whether to generate and publish the project site to gh-pages
 * @param nonRecursiveSite           skip aggregator-recursive site builds (single-module fast path)
 * @param skipOrgSite                skip the cross-repo org-site register (B25)
 * @param publishToCentral           whether to attempt the Maven Central deploy (opt-in)
 * @param nexusDeployMaxAttempts     maximum Nexus deploy attempts (retry budget)
 * @param nexusDeployBackoffSeconds  comma-separated backoff schedule between Nexus retries
 * @param skipNexusDeploy            skip the Nexus deploy phase entirely
 * @param centralDeployMaxAttempts   maximum Maven Central deploy attempts (retry budget)
 * @param centralDeployBackoffSeconds comma-separated backoff schedule between Central retries
 * @param skipCentralDeploy          skip the Central deploy phase entirely
 * @param centralDeployAsync         whether to use the detached async-bash spawn path for Central (#484)
 * @param centralSentinelDir         override directory for the Central async sentinel/log files
 * @param issueRepo                  GitHub repo (owner/name) hosting the release milestone
 * @param ignoreWarnings             proceed past preflight warnings (errors still abort)
 */
public record ReleaseRequest(
        String releaseVersion,
        String nextVersion,
        boolean publish,
        boolean skipVerify,
        String allowBranch,
        boolean publishSite,
        boolean nonRecursiveSite,
        boolean skipOrgSite,
        boolean publishToCentral,
        int nexusDeployMaxAttempts,
        String nexusDeployBackoffSeconds,
        boolean skipNexusDeploy,
        int centralDeployMaxAttempts,
        String centralDeployBackoffSeconds,
        boolean skipCentralDeploy,
        boolean centralDeployAsync,
        String centralSentinelDir,
        String issueRepo,
        boolean ignoreWarnings) {

    /**
     * Returns {@code true} when this request is for a draft preview, not a real release.
     *
     * <p>Derived as {@code !publish}. The release flow short-circuits
     * at B10 (after preflight) when {@code draft} is {@code true}.
     *
     * @return whether this is a draft-mode invocation
     */
    public boolean draft() {
        return !publish;
    }
}

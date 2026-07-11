package network.ike.knowledge.spi;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A knowledge-set verification request (IKE-Network/ike-issues#852): checks run against
 * a pristine store assembled from the declared base plus the artifact under
 * verification — never against the store that produced it.
 *
 * @param artifact     the change-set artifact under verification
 * @param baseInputs   the base the artifact must fit, in load order; may be empty for a
 *                     self-contained check
 * @param checks       which checks to run
 * @param priorRelease the previous release's artifact, required by {@link Check#DELTA}
 * @param view         the view specification; a time-pinned stamp position verifies the
 *                     knowledge-state as of the release
 */
public record VerifyRequest(Path artifact, List<ArtifactInput> baseInputs, Set<Check> checks,
                            Optional<Path> priorRelease, ViewSpec view) {

    /** The verification checks. */
    public enum Check {
        /** Every reference resolves in the artifact or the declared base. */
        FIT_REFERENCES,
        /** Manifest counts and dependent identities are present and exact. */
        PRESENCE,
        /** Identity invariants: birth names never reused, released names never edited. */
        IDENTITY,
        /** Difference against the prior release, reported as a review packet. */
        DELTA,
        /** Classification over base plus artifact succeeds with no orphans. */
        CLASSIFICATION_FIT
    }

    /**
     * Validates and defensively copies the request.
     *
     * @param artifact     the change-set artifact under verification
     * @param baseInputs   the base the artifact must fit, in load order
     * @param checks       which checks to run
     * @param priorRelease the previous release's artifact
     * @param view         the view specification
     * @throws NullPointerException     if any component is null
     * @throws IllegalArgumentException if {@code checks} is empty, {@link Check#DELTA}
     *                                  is requested without a prior release, or an
     *                                  {@link ArtifactInput.Role#STORE_SEED} appears
     *                                  after the first base position
     */
    public VerifyRequest {
        Objects.requireNonNull(artifact, "artifact");
        baseInputs = List.copyOf(Objects.requireNonNull(baseInputs, "baseInputs"));
        ArtifactInput.requireSeedFirstOnly(baseInputs);
        Objects.requireNonNull(priorRelease, "priorRelease");
        Objects.requireNonNull(view, "view");
        checks = Set.copyOf(Objects.requireNonNull(checks, "checks"));
        if (checks.isEmpty()) {
            throw new IllegalArgumentException("A verification requires at least one check");
        }
        if (checks.contains(Check.DELTA) && priorRelease.isEmpty()) {
            throw new IllegalArgumentException("The DELTA check requires the prior release's artifact");
        }
    }

    /**
     * Encodes this request in the seam wire format.
     *
     * @return the request properties
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        PropCodec.put(properties, "artifact", artifact.toString());
        PropCodec.putInputs(properties, "base.", baseInputs);
        PropCodec.put(properties, "checks",
                checks.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        PropCodec.putOptional(properties, "priorRelease", priorRelease.map(Path::toString));
        PropCodec.putView(properties, view);
        return properties;
    }

    /**
     * Decodes a request from the seam wire format.
     *
     * @param properties the request properties
     * @return the decoded request
     * @throws IllegalArgumentException if required keys are absent or malformed
     */
    public static VerifyRequest fromProperties(Properties properties) {
        Set<Check> checks = java.util.Arrays.stream(PropCodec.require(properties, "checks").split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .map(Check::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Check.class)));
        return new VerifyRequest(
                PropCodec.requirePath(properties, "artifact"),
                PropCodec.getInputs(properties, "base."),
                checks,
                PropCodec.optionalPath(properties, "priorRelease"),
                PropCodec.getView(properties));
    }
}

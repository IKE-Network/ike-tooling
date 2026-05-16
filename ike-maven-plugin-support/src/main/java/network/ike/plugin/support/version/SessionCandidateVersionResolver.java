package network.ike.plugin.support.version;

import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.Session;
import org.apache.maven.api.Version;
import org.apache.maven.api.services.VersionRangeResolver;
import org.apache.maven.api.services.VersionRangeResolverException;
import org.apache.maven.api.services.VersionRangeResolverResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link CandidateVersionResolver} backed by the Maven 4
 * {@link VersionRangeResolver} service.
 *
 * <p>Constructs an open version range {@code "(,)"} (every version,
 * inclusive of the lower bound and unbounded upper) and asks the
 * resolver to enumerate. Results are filtered to drop SNAPSHOTs, then
 * returned in ascending order.
 *
 * <p>Network access happens here. Errors from the resolver are
 * surfaced via {@link VersionResolverFailureException} so callers can
 * decide whether to abort the plan or carry on with a synthetic
 * "unable to resolve" entry. We deliberately do not silently return
 * an empty list — that would mask a misconfigured Nexus and produce a
 * plan that proposes no upgrades for unrelated reasons.
 */
public final class SessionCandidateVersionResolver
        implements CandidateVersionResolver {

    private final Session session;
    private final VersionRangeResolver resolver;

    /**
     * Build a resolver bound to a Maven session.
     *
     * @param session the active Maven 4 session; provides the resolver
     *                service and the configured remote repositories
     */
    public SessionCandidateVersionResolver(Session session) {
        this.session = session;
        this.resolver = session.getService(VersionRangeResolver.class);
        if (this.resolver == null) {
            throw new IllegalStateException(
                    "Maven session has no VersionRangeResolver service");
        }
    }

    @Override
    public List<String> resolveCandidates(String groupId, String artifactId,
                                          String currentVersion) {
        // Coordinate string with an open version range. Maven 4
        // accepts "(,)" as "any version".
        String coords = groupId + ":" + artifactId + ":(,)";
        ArtifactCoordinates artifactCoords;
        try {
            artifactCoords = session.createArtifactCoordinates(coords);
        } catch (RuntimeException e) {
            throw new VersionResolverFailureException(
                    "Cannot construct coordinates for " + groupId + ":"
                            + artifactId, e);
        }

        VersionRangeResolverResult result;
        try {
            result = resolver.resolve(session, artifactCoords);
        } catch (VersionRangeResolverException e) {
            throw new VersionResolverFailureException(
                    "Resolver failed for " + groupId + ":" + artifactId,
                    e);
        }

        List<Version> versions = result.getVersions();
        if (versions == null || versions.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> out = new ArrayList<>(versions.size());
        for (Version v : versions) {
            String s = v.toString();
            if (s.endsWith("-SNAPSHOT")) {
                continue;
            }
            out.add(s);
        }
        // Maven returns versions ascending already, but be defensive:
        out.sort(MavenVersionComparator.INSTANCE);
        return out;
    }
}

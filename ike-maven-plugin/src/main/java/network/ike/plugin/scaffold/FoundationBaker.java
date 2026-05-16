package network.ike.plugin.scaffold;

import network.ike.plugin.support.version.CandidateVersionResolver;
import network.ike.plugin.support.version.MavenVersionComparator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Release-time refresh of the scaffold manifest's {@code foundation:}
 * block (IKE-Network/ike-issues#414).
 *
 * <p>The {@code foundation:} block in
 * {@code ike-build-standards/src/main/scaffold/scaffold-manifest.yaml}
 * is the tested-together compatibility snapshot a consumer picks up
 * with a given {@code ike-tooling} scaffold. {@code ike-tooling.version}
 * is filtered from {@code ${project.version}} and so is always correct;
 * {@code ike-parent}, {@code ike-docs}, and {@code ike-platform} were
 * hand-maintained literals. This class resolves their latest released
 * (GA) versions from the configured remote repositories and rewrites
 * the block, so the scaffold zip always ships a current snapshot
 * without a manual edit.
 *
 * <p>This is the <em>discovery</em> half of foundation currency — the
 * scaffold {@code foundation:} block can only propagate a snapshot, it
 * cannot find out a newer upstream version exists. Moving discovery to
 * release-time bake here is what lets the scaffold mechanism own
 * foundation currency end to end (the precondition for retiring the
 * {@code versions-upgrade} subsystem, ike-issues#415).
 */
public final class FoundationBaker {

    /**
     * A foundation artifact whose pin is baked at release time.
     *
     * @param label      short human-readable name, e.g. {@code "ike-parent"}
     * @param groupId    the Maven groupId
     * @param artifactId the Maven artifactId
     */
    public record Coordinate(String label, String groupId,
                              String artifactId) {
    }

    /**
     * {@code ike-parent} — carried in the {@code foundation.parent}
     * block. {@code ike-tooling.version} is deliberately absent: it is
     * filtered from {@code ${project.version}} and never baked.
     */
    public static final Coordinate IKE_PARENT = new Coordinate(
            "ike-parent", "network.ike.platform", "ike-parent");

    /** {@code ike-docs} — the {@code ike-docs.version} property pin. */
    public static final Coordinate IKE_DOCS = new Coordinate(
            "ike-docs", "network.ike.docs", "ike-docs");

    /**
     * {@code ike-platform} — the {@code ike-platform.version} pin.
     *
     * <p>{@code ike-parent}, {@code ike-bom}, {@code ike-workspace-maven-plugin}
     * and the {@code network.ike.platform:ike-platform} reactor root all
     * share one unified version, so this pin and {@link #IKE_PARENT}
     * always resolve to the same release. {@link #assess} queries
     * {@code ike-parent} once and answers both — the {@code groupId} /
     * {@code artifactId} here document what the pin tracks but are not
     * resolved independently.
     */
    public static final Coordinate IKE_PLATFORM = new Coordinate(
            "ike-platform", "network.ike.platform", "ike-platform");

    /**
     * {@code ike-tooling} — tracks the {@code ike-tooling.version}
     * property pin (the version line {@code ike-build-standards},
     * {@code ike-maven-plugin} and the rest of the ike-tooling reactor
     * share). Resolved only for the consumer-side
     * {@link #latestFoundation} path; the release-time {@link #assess}
     * never touches it — there it is {@code ${project.version}},
     * correct by construction.
     */
    public static final Coordinate IKE_TOOLING = new Coordinate(
            "ike-tooling", "network.ike.tooling", "ike-tooling");

    /** Classification of a foundation pin against its latest GA. */
    public enum Status {
        /** Latest GA is newer than the pin — a bake is needed. */
        AHEAD,
        /** Pin already equals the latest GA. */
        CURRENT,
        /** Pin is newer than any GA — a backward bake; release fails. */
        BEHIND,
        /** No released version could be resolved for the coordinate. */
        UNRESOLVED
    }

    /**
     * The assessment of one foundation pin.
     *
     * @param coordinate the artifact this pin tracks
     * @param current    the version currently pinned in the manifest
     * @param latest     the latest resolved GA, or {@code null} when
     *                   {@link Status#UNRESOLVED}
     * @param status     how {@code latest} relates to {@code current}
     */
    public record Finding(Coordinate coordinate, String current,
                           String latest, Status status) {
    }

    private FoundationBaker() {
    }

    /**
     * Assess every baked foundation pin against the latest GA versions
     * the resolver can see.
     *
     * <p>Two coordinates are resolved, not three: {@code ike-parent} and
     * {@code ike-docs}. The {@code ike-platform.version} pin shares the
     * unified ike-platform reactor version with {@code ike-parent}, so
     * its {@link Finding} is derived from the same resolution rather
     * than queried separately.
     *
     * @param foundation the manifest's current {@code foundation:} block
     * @param resolver   resolves released versions for a coordinate
     * @return one {@link Finding} per pin, in
     *         {@code ike-parent, ike-docs, ike-platform} order
     */
    public static List<Finding> assess(ScaffoldManifest.Foundation foundation,
                                        CandidateVersionResolver resolver) {
        String platformLatest = resolveLatest(IKE_PARENT, resolver);
        String docsLatest = resolveLatest(IKE_DOCS, resolver);
        List<Finding> findings = new ArrayList<>(3);
        findings.add(classify(IKE_PARENT,
                foundation.parent().version(), platformLatest));
        findings.add(classify(IKE_DOCS,
                foundation.properties().get("ike-docs.version"), docsLatest));
        findings.add(classify(IKE_PLATFORM,
                foundation.properties().get("ike-platform.version"),
                platformLatest));
        return findings;
    }

    /**
     * Resolve the highest released (GA) version of a coordinate, or
     * {@code null} when nothing can be resolved.
     */
    private static String resolveLatest(Coordinate coord,
                                        CandidateVersionResolver resolver) {
        // resolveCandidates returns ascending GA-only versions.
        List<String> candidates = resolver.resolveCandidates(
                coord.groupId(), coord.artifactId(), null);
        return candidates.isEmpty() ? null
                : candidates.get(candidates.size() - 1);
    }

    /**
     * Resolve the latest released versions for every foundation pin and
     * return a {@link ScaffoldManifest.Foundation} carrying them — the
     * input snapshot with each version advanced to the latest GA the
     * resolver can see.
     *
     * <p>The consumer-side counterpart to {@link #assess}: it lets
     * {@code ike:scaffold-publish}'s opt-in resolve-latest mode apply
     * <em>current</em> foundation pins instead of the (possibly stale,
     * parent-pinned) snapshot baked into the scaffold zip — the escape
     * hatch for the bootstrap loop where a consumer's scaffold tooling
     * is itself gated by the {@code ike-parent} it needs to bump.
     *
     * <p>A pin is only advanced, never lowered: when the resolved GA is
     * not newer than the baked value, or cannot be resolved, the baked
     * value is kept. A value containing {@code ${...}} is left verbatim.
     *
     * @param baked    the foundation snapshot from the scaffold zip
     * @param resolver resolves released versions for a coordinate
     * @return a foundation with each pin at the latest released version
     */
    public static ScaffoldManifest.Foundation latestFoundation(
            ScaffoldManifest.Foundation baked,
            CandidateVersionResolver resolver) {
        String platform = resolveLatest(IKE_PARENT, resolver);
        String docs = resolveLatest(IKE_DOCS, resolver);
        String tooling = resolveLatest(IKE_TOOLING, resolver);

        ScaffoldManifest.ParentRef p = baked.parent();
        ScaffoldManifest.ParentRef parent = new ScaffoldManifest.ParentRef(
                p.groupId(), p.artifactId(), higher(p.version(), platform));

        Map<String, String> props = new LinkedHashMap<>(baked.properties());
        props.computeIfPresent("ike-tooling.version",
                (k, v) -> higher(v, tooling));
        props.computeIfPresent("ike-docs.version",
                (k, v) -> higher(v, docs));
        props.computeIfPresent("ike-platform.version",
                (k, v) -> higher(v, platform));
        return new ScaffoldManifest.Foundation(parent, props);
    }

    /**
     * The higher of the baked and resolved versions. The baked value
     * wins when the resolved one is {@code null}, when the baked value
     * is an unresolved {@code ${...}} placeholder, or when the resolved
     * one is not strictly newer — a pin is advanced, never lowered.
     */
    private static String higher(String baked, String resolved) {
        if (resolved == null || baked == null || baked.contains("${")) {
            return baked;
        }
        return MavenVersionComparator.INSTANCE.compare(resolved, baked) > 0
                ? resolved : baked;
    }

    /** Classify a pin's current value against the resolved latest GA. */
    private static Finding classify(Coordinate coord, String current,
                                    String latest) {
        if (latest == null) {
            return new Finding(coord, current, null, Status.UNRESOLVED);
        }
        int cmp = current == null ? 1
                : MavenVersionComparator.INSTANCE.compare(latest, current);
        Status status = cmp > 0 ? Status.AHEAD
                : cmp == 0 ? Status.CURRENT
                : Status.BEHIND;
        return new Finding(coord, current, latest, status);
    }

    /**
     * Rewrite the {@code foundation:} block of a scaffold-manifest YAML
     * document, applying every {@link Status#AHEAD} finding. Only the
     * version values change — comments, indentation, key order, and the
     * rest of the document are byte-preserved.
     *
     * @param manifestYaml the full scaffold-manifest.yaml content
     * @param findings      the assessment from {@link #assess}
     * @return the rewritten content; identical to the input when no
     *         finding is {@link Status#AHEAD}
     */
    public static String rewrite(String manifestYaml,
                                 List<Finding> findings) {
        String parentTarget = aheadTarget(findings, IKE_PARENT);
        String docsTarget = aheadTarget(findings, IKE_DOCS);
        String platformTarget = aheadTarget(findings, IKE_PLATFORM);
        if (parentTarget == null && docsTarget == null
                && platformTarget == null) {
            return manifestYaml;
        }

        String[] lines = manifestYaml.split("\n", -1);
        boolean inFoundation = false;
        boolean parentDone = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();

            if (trimmed.equals("foundation:")) {
                inFoundation = true;
                continue;
            }
            if (!inFoundation) {
                continue;
            }
            // Foundation block ends at the next column-0 key.
            if (!line.isBlank() && !line.startsWith(" ")
                    && !trimmed.startsWith("#")) {
                break;
            }

            if (parentTarget != null && !parentDone
                    && trimmed.startsWith("version:")) {
                // The only bare `version:` key in the block is the
                // parent's — property pins are `<name>.version:`.
                lines[i] = replaceValue(line, parentTarget);
                parentDone = true;
            } else if (docsTarget != null
                    && trimmed.startsWith("ike-docs.version:")) {
                lines[i] = replaceValue(line, docsTarget);
            } else if (platformTarget != null
                    && trimmed.startsWith("ike-platform.version:")) {
                lines[i] = replaceValue(line, platformTarget);
            }
        }
        return String.join("\n", lines);
    }

    /**
     * The latest version for {@code coord} when its finding is
     * {@link Status#AHEAD}, else {@code null}.
     */
    private static String aheadTarget(List<Finding> findings,
                                      Coordinate coord) {
        for (Finding f : findings) {
            if (f.coordinate().equals(coord) && f.status() == Status.AHEAD) {
                return f.latest();
            }
        }
        return null;
    }

    /**
     * Replace the quoted value of a {@code key: "value"} YAML line,
     * preserving the leading indentation and the key.
     */
    private static String replaceValue(String line, String newValue) {
        int colon = line.indexOf(':');
        return line.substring(0, colon + 1) + " \"" + newValue + "\"";
    }
}

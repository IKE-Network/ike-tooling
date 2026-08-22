package network.ike.tooling.buildreport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers the question a repository finding raises and the resolver
 * event cannot: <em>whose repository is this?</em>
 *
 * <p>Maven 4 disambiguates remote repositories by appending a SHA-1 of
 * the effective repository definition to the declared id, so a
 * resolver event names {@code spring-release-e22ad65b…} rather than
 * {@code spring-release}. That id is opaque, and — because the hash
 * covers the URL and policies — it also changes whenever an upstream
 * POM edits the repository, silently orphaning any ledger entry keyed
 * on it. Findings therefore key on the declared id with the hash
 * stripped, and carry the full id and URL as context instead.</p>
 *
 * <p>Provenance is resolved at session end rather than at observation
 * time: repository events fire before most POMs have been read, and
 * the declarations come from {@link ModelObservations}, which the
 * model observer fills throughout the build.</p>
 */
public final class RepositoryProvenance {

    /**
     * Maven 4's repository-id disambiguation suffix: a hyphen and the
     * 40-hex SHA-1 of the effective repository definition.
     */
    private static final Pattern DISAMBIGUATION_SUFFIX = Pattern.compile("-[0-9a-f]{40}$");

    /** How many declaring POMs to name before summarizing the rest. */
    private static final int MAX_NAMED_DECLARERS = 3;

    /**
     * Upper bound on resolved POMs scanned for a declaration, so a
     * pathological session cannot turn reporting into build cost.
     */
    private static final int MAX_SCANNED_POMS = 4000;

    /** Largest POM worth reading into memory during a scan. */
    private static final long MAX_SCANNED_POM_BYTES = 1_000_000L;

    /** Local-repository layout: {@code …/artifactId/version/artifactId-version.pom}. */
    private static final Pattern LOCAL_REPOSITORY_POM =
            Pattern.compile("^(?<artifactId>.+)-(?<version>[^/]+)\\.pom$");

    private RepositoryProvenance() {
    }

    /**
     * Strips Maven 4's disambiguation suffix from a repository id.
     *
     * @param repositoryId the id as the resolver reported it; may be null
     * @return the declared id, or {@code "unknown"} when absent
     */
    public static String declaredId(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            return "unknown";
        }
        return DISAMBIGUATION_SUFFIX.matcher(repositoryId).replaceFirst("");
    }

    /**
     * Adds a {@link Finding#CONTEXT_DECLARED_BY} entry to every
     * repository finding whose repository can be traced to a POM read
     * during the session.
     *
     * @param findings      the session's findings, in observation order
     * @param observations  the session's file-model observations, which
     *                      cover this workspace's POMs
     * @param resolvedPoms  the dependency POMs the session resolved,
     *                      which cover everything else
     * @param localRepository the local repository's base directory, which
     *                      anchors a resolved POM's group id; may be null
     * @param executionRoot the session's execution root, used to say
     *                      whether a declaring POM is in this workspace
     * @return the findings, repository ones enriched where possible
     */
    public static List<Finding> enrich(
            List<Finding> findings,
            List<ModelObservations.FileModel> observations,
            List<Path> resolvedPoms,
            Path localRepository,
            Path executionRoot) {
        Objects.requireNonNull(findings, "findings");
        Objects.requireNonNull(observations, "observations");
        List<Path> poms = resolvedPoms == null ? List.of() : resolvedPoms;
        Path root = executionRoot == null ? null : executionRoot.toAbsolutePath().normalize();
        List<Finding> enriched = new ArrayList<>(findings.size());
        for (Finding finding : findings) {
            if (finding.category() != FindingCategory.REPOSITORY) {
                enriched.add(finding);
                continue;
            }
            enriched.add(finding.withContext(
                    Finding.CONTEXT_DECLARED_BY,
                    describeDeclarers(
                            finding.context().get(Finding.CONTEXT_REPOSITORY_ID),
                            finding.context().get(Finding.CONTEXT_REPOSITORY_URL),
                            observations,
                            poms,
                            localRepository,
                            root)));
        }
        return enriched;
    }

    /**
     * Names the POMs that declared a repository.
     *
     * <p>Matching is by URL first — the identity Maven itself hashes —
     * and by declared id second, so a repository still resolves when
     * the event carries no URL. Workspace declarations are listed
     * before third-party ones, because "you declared this" and
     * "a dependency declared this" call for opposite responses.</p>
     *
     * @param repositoryId  the repository id from the resolver event
     * @param repositoryUrl the repository URL from the resolver event
     * @param observations  the session's file-model observations
     * @param root          the normalized execution root, or null
     * @return a human-readable provenance line, or empty when no POM
     *         read this session declared the repository
     */
    private static String describeDeclarers(
            String repositoryId,
            String repositoryUrl,
            List<ModelObservations.FileModel> observations,
            List<Path> resolvedPoms,
            Path localRepository,
            Path root) {
        String declaredId = declaredId(repositoryId);
        String url = normalizeUrl(repositoryUrl);
        Set<String> workspace = new LinkedHashSet<>();
        Set<String> external = new LinkedHashSet<>();

        for (ModelObservations.FileModel model : observations) {
            for (ModelObservations.RepositoryDeclaration declaration : model.repositories()) {
                boolean matches = !url.isEmpty() && url.equals(normalizeUrl(declaration.url()));
                if (!matches) {
                    matches = !declaration.id().isEmpty() && declaration.id().equals(declaredId);
                }
                if (!matches) {
                    continue;
                }
                (inWorkspace(model.pomFile(), root) ? workspace : external).add(model.gav());
            }
        }

        if (!url.isEmpty()) {
            scanResolvedPoms(
                    resolvedPoms, repositoryUrl.strip(), root, localRepository, workspace, external);
        }

        if (workspace.isEmpty() && external.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(96);
        if (!workspace.isEmpty()) {
            out.append("declared in this workspace by ").append(summarize(workspace));
        }
        if (!external.isEmpty()) {
            if (out.length() > 0) {
                out.append("; also ");
            }
            out.append("declared by the POM of ").append(summarize(external))
                    .append(" — a dependency, not this workspace");
        }
        return out.toString();
    }

    /**
     * Finds the resolved POMs whose text declares a repository URL.
     *
     * <p>A substring match on the URL, not an XML query: the POM has
     * already been read and validated by Maven, the URL is a long and
     * distinctive literal, and a false positive costs one wrong name in
     * a diagnostic line rather than anything the build acts on. Files
     * are read only when a repository finding exists, which is rare.</p>
     */
    private static void scanResolvedPoms(
            List<Path> resolvedPoms,
            String url,
            Path root,
            Path localRepository,
            Set<String> workspace,
            Set<String> external) {
        int scanned = 0;
        for (Path pom : resolvedPoms) {
            if (scanned++ >= MAX_SCANNED_POMS) {
                return;
            }
            try {
                if (!Files.isRegularFile(pom) || Files.size(pom) > MAX_SCANNED_POM_BYTES) {
                    continue;
                }
                if (!Files.readString(pom, StandardCharsets.UTF_8).contains(url)) {
                    continue;
                }
            } catch (Exception e) {
                // An unreadable or non-UTF-8 POM simply yields no provenance.
                continue;
            }
            String gav = coordinatesOf(pom, localRepository);
            if (!gav.isEmpty()) {
                (inWorkspace(pom, root) ? workspace : external).add(gav);
            }
        }
    }

    /**
     * Derives a POM's coordinates from its local-repository path.
     *
     * <p>The layout {@code <group as directories>/<artifactId>/<version>/<artifactId>-<version>.pom}
     * is guaranteed for anything the resolver put on disk, which is
     * exactly the set being scanned — a cheaper and more reliable
     * source than parsing a POM whose own coordinates may be inherited
     * from a parent. The group id needs the repository root to know
     * where it begins; without one, the artifact and version still
     * name the dependency, which is what the reader came for.</p>
     *
     * @param pom             the resolved POM's path
     * @param localRepository the local repository's base directory, or null
     * @return {@code groupId:artifactId:version}, {@code artifactId:version}
     *         when the group cannot be anchored, or empty when the path
     *         does not follow the layout at all
     */
    static String coordinatesOf(Path pom, Path localRepository) {
        Path fileName = pom.getFileName();
        Path versionDir = pom.getParent();
        if (fileName == null || versionDir == null || versionDir.getParent() == null) {
            return "";
        }
        Matcher matcher = LOCAL_REPOSITORY_POM.matcher(fileName.toString());
        if (!matcher.matches()) {
            return "";
        }
        String artifactId = matcher.group("artifactId");
        String version = matcher.group("version");
        Path artifactDir = versionDir.getParent();
        if (!versionDir.getFileName().toString().equals(version)
                || !artifactDir.getFileName().toString().equals(artifactId)) {
            return "";
        }
        Path groupDir = artifactDir.getParent();
        if (groupDir == null || localRepository == null) {
            return artifactId + ":" + version;
        }
        Path base = localRepository.toAbsolutePath().normalize();
        Path absoluteGroupDir = groupDir.toAbsolutePath().normalize();
        if (!absoluteGroupDir.startsWith(base)) {
            return artifactId + ":" + version;
        }
        StringBuilder groupId = new StringBuilder();
        for (Path segment : base.relativize(absoluteGroupDir)) {
            groupId.append(groupId.length() == 0 ? "" : ".").append(segment);
        }
        return groupId.length() == 0
                ? artifactId + ":" + version
                : groupId + ":" + artifactId + ":" + version;
    }

    private static boolean inWorkspace(Path pomFile, Path root) {
        return root != null && pomFile != null && pomFile.toAbsolutePath().normalize().startsWith(root);
    }

    private static String summarize(Set<String> gavs) {
        List<String> all = new ArrayList<>(gavs);
        if (all.size() <= MAX_NAMED_DECLARERS) {
            return String.join(", ", all);
        }
        return String.join(", ", all.subList(0, MAX_NAMED_DECLARERS))
                + " and " + (all.size() - MAX_NAMED_DECLARERS) + " more";
    }

    private static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.strip().toLowerCase(Locale.ROOT);
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}

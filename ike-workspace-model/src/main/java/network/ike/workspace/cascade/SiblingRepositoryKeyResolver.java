package network.ike.workspace.cascade;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link RepositoryKeyResolver} backed by sibling checkouts on the
 * local filesystem (IKE-Network/ike-issues#496 part C).
 *
 * <p>Scans a base directory of sibling repository checkouts, walks
 * every {@code pom.xml} it finds, and indexes the coordinate the POM
 * declares against the {@link RepositoryKey} of the repository
 * containing it. Subsequent {@link #resolve(String, String) resolve}
 * calls are constant-time map lookups against the index.
 *
 * <h2>How the repository identity is determined</h2>
 *
 * <p>The repository identity is taken from the <em>root POM of the
 * containing git repository</em>, not from Maven inheritance. For
 * each {@code pom.xml} found:
 *
 * <ol>
 *   <li>Walk upward to the nearest ancestor directory containing a
 *       {@code .git} entry — the git repo root.</li>
 *   <li>Read that root's {@code pom.xml} for its local
 *       {@code <scm>} block.</li>
 *   <li>Convert the {@code <scm>} URL or connection to a
 *       {@link RepositoryKey} via {@link RepositoryKey#of(String)}.</li>
 *   <li>Index every coordinate produced under that git repo against
 *       this same key.</li>
 * </ol>
 *
 * <p>This bypasses Maven's default {@code <scm>} inheritance, which
 * appends each subproject's {@code <artifactId>} to the parent's
 * URL (producing paths like {@code .../ike-tooling/ike-build-standards}
 * for what is in fact still the {@code ike-tooling} repository). The
 * git-boundary read gives every coordinate in a reactor the same
 * key — the goal of the cascade's repository-keyed node model.
 *
 * <h2>Scoping</h2>
 *
 * <p>The scan is bounded: it descends from the supplied base
 * directory but skips {@code target/}, {@code .git/}, {@code .idea/},
 * {@code node_modules/}, and similar build/IDE caches.
 */
public final class SiblingRepositoryKeyResolver
        implements RepositoryKeyResolver {

    /** Conventional directory names skipped during the POM scan. */
    private static final java.util.Set<String> SKIP_DIRS = java.util.Set.of(
            "target", ".git", ".idea", ".mvn", "node_modules",
            ".gradle", "build", "dist");

    private final Path baseDir;
    private final Map<String, RepositoryKey> byCoordinate;
    private boolean indexed;

    /**
     * Creates a resolver that lazily indexes the POMs under
     * {@code baseDir} on the first {@link #resolve} call.
     *
     * @param baseDir the directory of sibling checkouts; required
     */
    public SiblingRepositoryKeyResolver(Path baseDir) {
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir is required");
        }
        this.baseDir = baseDir;
        this.byCoordinate = new HashMap<>();
        this.indexed = false;
    }

    /**
     * Indexes the POMs eagerly. Optional — {@link #resolve} will
     * trigger the same indexing on first call.
     */
    public synchronized void index() {
        if (indexed) {
            return;
        }
        indexed = true;
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        walkPoms(baseDir);
    }

    @Override
    public Optional<RepositoryKey> resolve(String groupId,
                                            String artifactId) {
        if (groupId == null || artifactId == null) {
            return Optional.empty();
        }
        if (!indexed) {
            index();
        }
        return Optional.ofNullable(
                byCoordinate.get(coordinateKey(groupId, artifactId)));
    }

    private void walkPoms(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                        Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null
                            ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                        Path file, BasicFileAttributes attrs) {
                    if ("pom.xml".equals(
                            file.getFileName().toString())) {
                        indexPom(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot scan " + root + " for POMs", e);
        }
    }

    private void indexPom(Path pom) {
        Path repoRoot = gitRoot(pom.getParent());
        if (repoRoot == null) {
            // POM not inside any git repo we can identify; skip.
            return;
        }
        RepositoryKey key = repoKey(repoRoot);
        if (key == null) {
            return;
        }
        PomCoordinates coords = readCoordinates(pom);
        if (coords.groupId != null && coords.artifactId != null) {
            byCoordinate.put(
                    coordinateKey(coords.groupId, coords.artifactId), key);
        }
    }

    /**
     * Returns the nearest ancestor directory that contains a
     * {@code .git} entry, or {@code null} when none exists at or
     * above {@code start}.
     */
    private static Path gitRoot(Path start) {
        Path cursor = start;
        while (cursor != null) {
            if (Files.exists(cursor.resolve(".git"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private final Map<Path, RepositoryKey> repoKeyCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private RepositoryKey repoKey(Path repoRoot) {
        RepositoryKey cached = repoKeyCache.get(repoRoot);
        if (cached != null) {
            return cached;
        }
        Path rootPom = repoRoot.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            return null;
        }
        ScmIdentity scm = readScm(rootPom);
        String url = scm.url != null && !scm.url.isBlank()
                ? scm.url
                : scm.connection;
        if (url == null || url.isBlank()) {
            return null;
        }
        RepositoryKey key = RepositoryKey.of(url);
        repoKeyCache.put(repoRoot, key);
        return key;
    }

    private static String coordinateKey(String groupId,
                                        String artifactId) {
        return groupId + ":" + artifactId;
    }

    // ── POM XML reading ────────────────────────────────────────────

    private record PomCoordinates(String groupId, String artifactId) {}

    private record ScmIdentity(String url, String connection) {}

    private static PomCoordinates readCoordinates(Path pom) {
        try (Reader reader = Files.newBufferedReader(
                pom, StandardCharsets.UTF_8)) {
            return parseCoordinates(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read " + pom, e);
        } catch (XMLStreamException e) {
            throw new IllegalStateException(
                    "Malformed " + pom + ": " + e.getMessage(), e);
        }
    }

    private static ScmIdentity readScm(Path pom) {
        try (Reader reader = Files.newBufferedReader(
                pom, StandardCharsets.UTF_8)) {
            return parseScm(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read " + pom, e);
        } catch (XMLStreamException e) {
            throw new IllegalStateException(
                    "Malformed " + pom + ": " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the project's top-level {@code <groupId>} and
     * {@code <artifactId>}. When {@code <groupId>} is absent at the
     * project level it falls back to {@code <parent><groupId>} —
     * the standard Maven inheritance default.
     */
    private static PomCoordinates parseCoordinates(Reader source)
            throws XMLStreamException {
        XMLStreamReader xml = openReader(source);
        try {
            String projectGroupId = null;
            String parentGroupId = null;
            String artifactId = null;
            int depth = 0;
            String containerAtDepth1 = null;
            String currentLeaf = null;
            StringBuilder leaf = new StringBuilder();

            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String name = xml.getLocalName();
                    if (depth == 2 && "parent".equals(name)) {
                        containerAtDepth1 = "parent";
                    } else if (depth == 3
                            && "parent".equals(containerAtDepth1)
                            && ("groupId".equals(name))) {
                        currentLeaf = "parent.groupId";
                        leaf.setLength(0);
                    } else if (depth == 2 && ("groupId".equals(name)
                            || "artifactId".equals(name))) {
                        currentLeaf = "project." + name;
                        leaf.setLength(0);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS
                        && currentLeaf != null) {
                    leaf.append(xml.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = xml.getLocalName();
                    if (currentLeaf != null) {
                        String value = leaf.toString().trim();
                        switch (currentLeaf) {
                            case "project.groupId" -> projectGroupId = value;
                            case "project.artifactId" -> artifactId = value;
                            case "parent.groupId" -> parentGroupId = value;
                            default -> { /* ignored */ }
                        }
                        currentLeaf = null;
                    }
                    if (depth == 2 && "parent".equals(name)) {
                        containerAtDepth1 = null;
                    }
                    depth--;
                }
            }
            String groupId = projectGroupId != null && !projectGroupId.isBlank()
                    ? projectGroupId
                    : parentGroupId;
            return new PomCoordinates(groupId, artifactId);
        } finally {
            xml.close();
        }
    }

    /**
     * Extracts the project's local {@code <scm><url>} and
     * {@code <scm><connection>}. Does not consult inheritance —
     * the caller is reading the git-repo-root POM where IKE
     * convention places the local declaration.
     */
    private static ScmIdentity parseScm(Reader source)
            throws XMLStreamException {
        XMLStreamReader xml = openReader(source);
        try {
            String url = null;
            String connection = null;
            int depth = 0;
            String containerAtDepth1 = null;
            String currentLeaf = null;
            StringBuilder leaf = new StringBuilder();

            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    String name = xml.getLocalName();
                    if (depth == 2 && "scm".equals(name)) {
                        containerAtDepth1 = "scm";
                    } else if (depth == 3
                            && "scm".equals(containerAtDepth1)
                            && ("url".equals(name)
                                    || "connection".equals(name))) {
                        currentLeaf = name;
                        leaf.setLength(0);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS
                        && currentLeaf != null) {
                    leaf.append(xml.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = xml.getLocalName();
                    if (currentLeaf != null) {
                        String value = leaf.toString().trim();
                        switch (currentLeaf) {
                            case "url" -> url = value;
                            case "connection" -> connection = value;
                            default -> { /* ignored */ }
                        }
                        currentLeaf = null;
                    }
                    if (depth == 2 && "scm".equals(name)) {
                        containerAtDepth1 = null;
                    }
                    depth--;
                }
            }
            return new ScmIdentity(url, connection);
        } finally {
            xml.close();
        }
    }

    private static XMLStreamReader openReader(Reader source)
            throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE,
                Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(
                "javax.xml.stream.isSupportingExternalEntities",
                Boolean.FALSE);
        return factory.createXMLStreamReader(source);
    }
}

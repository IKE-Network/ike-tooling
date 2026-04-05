package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.Component;
import network.ike.workspace.Dependency;
import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.PublishedArtifactSet;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Add a component repository to an existing workspace.
 *
 * <p>Given a git URL, this goal:
 * <ol>
 *   <li>Clones the repository into the workspace</li>
 *   <li>Derives the component name from the URL (or accepts
 *       {@code -Dcomponent=<name>})</li>
 *   <li>Scans the POM to derive groupId and inter-component
 *       dependencies (matching dependency/parent groupIds against
 *       already-registered workspace components)</li>
 *   <li>Appends a component entry to workspace.yaml</li>
 *   <li>Adds a file-activated profile to the reactor POM</li>
 *   <li>Re-scans existing components to discover any that depend
 *       on the newly added component (backward resolution)</li>
 * </ol>
 *
 * <p>The component name is derived from the last path segment of the
 * URL with {@code .git} stripped. For example,
 * {@code https://github.com/ikmdev/tinkar-core.git} becomes
 * {@code tinkar-core}.
 *
 * <pre>{@code
 * mvn ws:add -Drepo=https://github.com/ikmdev/tinkar-core.git
 * mvn ws:add -Drepo=https://github.com/ikmdev/rocks-kb.git
 * mvn ws:add -Drepo=https://github.com/ikmdev/komet.git
 * }</pre>
 *
 * @see WsCreateMojo for creating a new workspace
 * @see InitWorkspaceMojo for cloning all components
 */
@Mojo(name = "add", requiresProject = false, threadSafe = true)
public class WsAddMojo extends AbstractMojo {

    /**
     * Git repository URL (required). The component name is derived
     * from the URL unless {@code -Dcomponent} is specified.
     */
    @Parameter(property = "repo", required = true)
    private String repo;

    /**
     * Component name override. If omitted, derived from the repo URL
     * (last path segment minus {@code .git}).
     */
    @Parameter(property = "component")
    private String component;

    /**
     * Component type. Must match a key in the workspace.yaml
     * {@code component-types} section.
     */
    @Parameter(property = "type", defaultValue = "software")
    private String type;

    /**
     * Short description of the component.
     */
    @Parameter(property = "description")
    private String description;

    /**
     * Branch to track. If omitted, uses the workspace default.
     */
    @Parameter(property = "branch")
    private String branch;

    /**
     * Maven groupId for the component. If omitted, left as
     * a placeholder in workspace.yaml.
     */
    @Parameter(property = "groupId")
    private String groupId;

    /**
     * Skip cloning — register the component in workspace.yaml without
     * cloning. Dependencies cannot be derived without a POM to scan,
     * so they will be empty. Use {@code ws:init} to clone later.
     */
    @Parameter(property = "skipClone", defaultValue = "false")
    private boolean skipClone;

    /** Creates this goal instance. */
    public WsAddMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        // Resolve workspace root
        Path wsDir = findWorkspaceRoot();
        Path manifestPath = wsDir.resolve("workspace.yaml");
        Path pomPath = wsDir.resolve("pom.xml");

        if (!Files.exists(manifestPath)) {
            throw new MojoExecutionException(
                    "No workspace.yaml found in " + wsDir
                    + ". Run ws:create first.");
        }

        // Derive component name from URL if not specified
        if (component == null || component.isBlank()) {
            component = deriveComponentName(repo);
        }

        if (description == null || description.isBlank()) {
            description = component + " component.";
        }

        // Clone so we can scan the POM for groupId and dependencies
        Path componentDir = wsDir.resolve(component);
        boolean cloned = false;
        String derivedDeps = null;

        if (!skipClone && !Files.exists(componentDir)) {
            cloneComponent(wsDir);
            cloned = true;
        }

        if (Files.exists(componentDir.resolve("pom.xml"))) {
            // Derive groupId from POM if not explicitly specified
            if (groupId == null || groupId.isBlank()) {
                groupId = deriveGroupId(componentDir);
            }

            // Derive dependencies by matching POM groupIds against
            // already-registered workspace components
            try {
                derivedDeps = deriveDependencies(wsDir, manifestPath,
                        componentDir, component);
            } catch (IOException e) {
                getLog().warn("  Could not derive dependencies from POM: "
                        + e.getMessage());
            }
        } else if (!skipClone) {
            getLog().warn("  No pom.xml found — dependencies not derived");
        }

        getLog().info("");
        getLog().info("IKE Workspace — Add Component");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Component: " + component);
        getLog().info("  Repo:      " + repo);
        getLog().info("  Type:      " + type);
        if (branch != null) {
            getLog().info("  Branch:    " + branch);
        }
        if (groupId != null && !groupId.isBlank()) {
            getLog().info("  GroupId:   " + groupId);
        }
        if (derivedDeps != null && !derivedDeps.isBlank()) {
            getLog().info("  Depends:   " + derivedDeps + " (derived from POM)");
        } else {
            getLog().info("  Depends:   (none)");
        }
        if (cloned) {
            getLog().info("  ✓ Cloned " + component);
        }
        getLog().info("");

        try {
            // Update workspace.yaml
            appendComponentToManifest(manifestPath, derivedDeps);
            getLog().info("  ✓ workspace.yaml updated");

            // Update pom.xml
            addProfileToPom(pomPath);
            getLog().info("  ✓ pom.xml updated (profile: with-" + component + ")");

        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to update workspace files: " + e.getMessage(), e);
        }

        // Backward resolution: check if any existing components
        // depend on the newly added component's groupId
        if (Files.exists(componentDir.resolve("pom.xml"))) {
            try {
                int backfilled = backfillDependencies(
                        wsDir, manifestPath, component, componentDir);
                if (backfilled > 0) {
                    getLog().info("  ✓ Updated " + backfilled
                            + " existing component(s) with dependency on "
                            + component);
                }
            } catch (IOException e) {
                getLog().warn("  Could not backfill dependencies: "
                        + e.getMessage());
            }
        }

        // Version alignment: update dependency versions in the newly
        // added component (and any backfilled components) to match
        // workspace SNAPSHOT versions. Changes are left uncommitted
        // so the developer can review and fold them into a feature branch.
        if (Files.exists(componentDir.resolve("pom.xml"))) {
            try {
                Manifest updatedManifest = ManifestReader.read(manifestPath);
                int aligned = alignVersions(wsDir, componentDir, component,
                        updatedManifest);
                if (aligned > 0) {
                    getLog().info("");
                    getLog().info("  ⚠ " + aligned + " file(s) modified for version "
                            + "alignment (uncommitted)");
                    getLog().info("    Review with 'git diff' in " + component);
                }
            } catch (IOException e) {
                getLog().warn("  Could not align versions: " + e.getMessage());
            }
        }

        getLog().info("");
        if (cloned) {
            getLog().info("  Component added and cloned.");
        } else {
            getLog().info("  Component added. Run 'mvn ws:init' to clone.");
        }
        getLog().info("");
    }

    // ── YAML generation ──────────────────────────────────────────

    void appendComponentToManifest(Path manifestPath, String derivedDeps)
            throws IOException {
        String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);

        StringBuilder entry = new StringBuilder();
        entry.append("\n  ").append(component).append(":\n");
        entry.append("    type: ").append(type).append("\n");
        entry.append("    description: >\n");
        entry.append("      ").append(description).append("\n");
        entry.append("    repo: ").append(repo).append("\n");
        if (branch != null && !branch.isBlank()) {
            entry.append("    branch: ").append(branch).append("\n");
        }
        if (groupId != null && !groupId.isBlank()) {
            entry.append("    groupId: ").append(groupId).append("\n");
        }
        if (derivedDeps != null && !derivedDeps.isBlank()) {
            entry.append("    depends-on:\n");
            for (String dep : derivedDeps.split(",")) {
                dep = dep.trim();
                if (!dep.isEmpty()) {
                    entry.append("      - component: ").append(dep).append("\n");
                    entry.append("        relationship: build\n");
                }
            }
        } else {
            entry.append("    depends-on: []\n");
        }

        // Insert before the "groups:" section if it exists,
        // otherwise append at end
        int groupsIdx = yaml.indexOf("\ngroups:");
        if (groupsIdx >= 0) {
            yaml = yaml.substring(0, groupsIdx) + entry + yaml.substring(groupsIdx);
        } else {
            yaml = yaml + entry;
        }

        // Update the "all" group to include the new component
        yaml = addToAllGroup(yaml, component);

        Files.writeString(manifestPath, yaml, StandardCharsets.UTF_8);
    }

    // ── POM generation ───────────────────────────────────────────

    void addProfileToPom(Path pomPath) throws IOException {
        String pom = Files.readString(pomPath, StandardCharsets.UTF_8);

        // Check if profile already exists
        if (pom.contains("with-" + component)) {
            getLog().info("  Profile with-" + component + " already exists");
            return;
        }

        String profile = "\n"
                + "        <profile>\n"
                + "            <id>with-" + component + "</id>\n"
                + "            <activation>\n"
                + "                <file>\n"
                + "                    <exists>${project.basedir}/" + component + "/pom.xml</exists>\n"
                + "                </file>\n"
                + "            </activation>\n"
                + "            <subprojects>\n"
                + "                <subproject>" + component + "</subproject>\n"
                + "            </subprojects>\n"
                + "        </profile>\n";

        // Insert before closing </profiles>
        int closingIdx = pom.lastIndexOf("</profiles>");
        if (closingIdx >= 0) {
            pom = pom.substring(0, closingIdx) + profile + "\n    " + pom.substring(closingIdx);
        } else {
            getLog().warn("  No </profiles> tag found in pom.xml — add profile manually");
        }

        Files.writeString(pomPath, pom, StandardCharsets.UTF_8);
    }

    // ── Clone ────────────────────────────────────────────────────

    private void cloneComponent(Path wsDir) throws MojoExecutionException {
        String[] cmd;
        if (branch != null && !branch.isBlank()) {
            cmd = new String[]{"git", "clone", "-b", branch, repo, component};
        } else {
            cmd = new String[]{"git", "clone", repo, component};
        }
        ReleaseSupport.exec(wsDir.toFile(), getLog(), cmd);
    }

    // ── POM-based dependency derivation ────────────────────────

    /**
     * Scan the new component's POM for dependency and parent groupIds,
     * then match against groupIds of already-registered workspace
     * components to derive the depends-on list.
     *
     * @param manifestPath path to workspace.yaml
     * @param componentDir the cloned component directory
     * @return comma-separated component names, or null if none found
     */
    /**
     * Derive dependencies by scanning the new component's POMs for
     * referenced {@code groupId:artifactId} pairs and matching them
     * against the published artifact sets of already-registered
     * workspace components.
     *
     * <p>This is artifact-level matching, not groupId-level — it
     * correctly handles components that share a groupId (e.g.,
     * tinkar-core and tinkar-composer both use {@code dev.ikm.tinkar}).
     */
    private String deriveDependencies(Path wsDir, Path manifestPath,
                                       Path componentDir, String componentName)
            throws IOException {
        // Collect all groupId:artifactId pairs referenced by this component
        Set<String> referencedArtifacts = extractReferencedArtifacts(
                componentDir.resolve("pom.xml"));
        if (referencedArtifacts.isEmpty()) return null;

        Manifest manifest = ManifestReader.read(manifestPath);

        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, Component> entry : manifest.components().entrySet()) {
            String existingName = entry.getKey();

            // Never depend on yourself
            if (existingName.equals(componentName)) continue;

            Path existingDir = wsDir.resolve(existingName);
            if (!Files.exists(existingDir.resolve("pom.xml"))) continue;

            // Build the published artifact set for the existing component
            Set<PublishedArtifactSet.Artifact> published =
                    PublishedArtifactSet.scan(existingDir);

            // Check if any referenced artifact is published by this component
            for (PublishedArtifactSet.Artifact artifact : published) {
                String key = artifact.groupId() + ":" + artifact.artifactId();
                if (referencedArtifacts.contains(key)) {
                    matched.add(existingName);
                    break;
                }
            }
        }

        return matched.isEmpty() ? null : String.join(",", matched);
    }

    /**
     * Backward resolution: for each existing cloned component, scan
     * its POM and check whether it references the newly added
     * component's groupId. If so, and it doesn't already have a
     * depends-on edge, add one to workspace.yaml.
     *
     * @return the number of existing components updated
     */
    /**
     * Backward resolution: for each existing cloned component, check
     * whether its POMs reference any artifact published by the newly
     * added component. Uses artifact-level matching via
     * {@link PublishedArtifactSet} to avoid false positives from
     * shared groupIds.
     */
    private int backfillDependencies(Path wsDir, Path manifestPath,
                                     String newComponent, Path newComponentDir)
            throws IOException {
        // Build the published artifact set for the new component
        Set<PublishedArtifactSet.Artifact> newPublished =
                PublishedArtifactSet.scan(newComponentDir);
        if (newPublished.isEmpty()) return 0;

        // Build a lookup set of "groupId:artifactId" strings
        Set<String> newArtifactKeys = new LinkedHashSet<>();
        for (PublishedArtifactSet.Artifact a : newPublished) {
            newArtifactKeys.add(a.groupId() + ":" + a.artifactId());
        }

        String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);
        Manifest manifest = ManifestReader.read(manifestPath);
        int updated = 0;

        for (Map.Entry<String, Component> entry : manifest.components().entrySet()) {
            String existingName = entry.getKey();
            Component existing = entry.getValue();

            // Skip the newly added component itself
            if (existingName.equals(newComponent)) continue;

            // Skip if already depends on the new component
            if (existing.dependsOn() != null
                    && existing.dependsOn().stream()
                    .anyMatch(d -> newComponent.equals(d.component()))) {
                continue;
            }

            // Check if this existing component references any artifact
            // published by the new component
            Path existingPom = wsDir.resolve(existingName).resolve("pom.xml");
            if (!Files.exists(existingPom)) continue;

            Set<String> referenced = extractReferencedArtifacts(existingPom);
            boolean dependsOnNew = referenced.stream()
                    .anyMatch(newArtifactKeys::contains);

            if (!dependsOnNew) continue;

            yaml = addDependencyEdge(yaml, existingName, newComponent);
            updated++;
            getLog().info("  → " + existingName + " depends on " + newComponent);
        }

        if (updated > 0) {
            Files.writeString(manifestPath, yaml, StandardCharsets.UTF_8);
        }

        return updated;
    }

    /**
     * Add a depends-on edge for an existing component in workspace.yaml.
     * Converts {@code depends-on: []} to a populated list, or appends
     * to an existing list.
     */
    static String addDependencyEdge(String yaml, String componentName,
                                    String dependsOnName) {
        // Case 1: depends-on: [] — replace with populated entry
        String emptyDeps = "(" + componentName + ":[\\s\\S]*?)(depends-on:\\s*\\[])";
        Pattern emptyPattern = Pattern.compile(emptyDeps);
        Matcher emptyMatcher = emptyPattern.matcher(yaml);
        if (emptyMatcher.find()) {
            String replacement = emptyMatcher.group(1)
                    + "depends-on:\n"
                    + "      - component: " + dependsOnName + "\n"
                    + "        relationship: build";
            return emptyMatcher.replaceFirst(Matcher.quoteReplacement(replacement));
        }

        // Case 2: existing depends-on list — append before next component
        // or section. Find the component's depends-on block and add an entry.
        String existingDeps = "(" + componentName
                + ":[\\s\\S]*?depends-on:\\n)((?:\\s+- component:.*\\n\\s+relationship:.*\\n)*)";
        Pattern existingPattern = Pattern.compile(existingDeps);
        Matcher existingMatcher = existingPattern.matcher(yaml);
        if (existingMatcher.find()) {
            String replacement = existingMatcher.group(1)
                    + existingMatcher.group(2)
                    + "      - component: " + dependsOnName + "\n"
                    + "        relationship: build\n";
            return existingMatcher.replaceFirst(Matcher.quoteReplacement(replacement));
        }

        return yaml;
    }

    /**
     * Extract all {@code groupId:artifactId} pairs referenced as
     * build dependencies across the entire component (root POM +
     * all submodules/subprojects).
     *
     * <p>Scans {@code <parent>} and {@code <dependencies>} blocks,
     * but excludes {@code <dependencyManagement>} (which contains
     * BOM imports and version constraints, not build dependencies).
     *
     * @return set of "groupId:artifactId" strings
     */
    private Set<String> extractReferencedArtifacts(Path pomFile) throws IOException {
        Set<String> artifacts = new LinkedHashSet<>();
        scanPomForArtifacts(pomFile, artifacts);
        return artifacts;
    }

    /**
     * Recursively scan a POM and its submodules for referenced
     * groupId:artifactId pairs.
     */
    private void scanPomForArtifacts(Path pomFile, Set<String> artifacts)
            throws IOException {
        if (!Files.exists(pomFile)) return;

        String content = Files.readString(pomFile, StandardCharsets.UTF_8);

        // Extract parent groupId:artifactId
        Matcher parentBlock = PARENT_BLOCK.matcher(content);
        if (parentBlock.find()) {
            String block = parentBlock.group();
            String gid = extractFirst(GROUP_ID_PATTERN, block);
            String aid = extractFirst(ARTIFACT_ID_PATTERN, block);
            if (gid != null && aid != null) {
                artifacts.add(gid + ":" + aid);
            }
        }

        // Strip <dependencyManagement> before scanning <dependency> blocks
        String stripped = DEP_MGMT_BLOCK.matcher(content).replaceAll("");

        Matcher depBlock = DEPENDENCY_BLOCK.matcher(stripped);
        while (depBlock.find()) {
            String block = depBlock.group();
            String gid = extractFirst(GROUP_ID_PATTERN, block);
            String aid = extractFirst(ARTIFACT_ID_PATTERN, block);
            if (gid != null && aid != null) {
                artifacts.add(gid + ":" + aid);
            }
        }

        // Recurse into subprojects (POM 4.1.0) and modules (POM 4.0.0)
        Path pomDir = pomFile.getParent();

        Matcher subMatcher = SUBPROJECT_PATTERN.matcher(content);
        while (subMatcher.find()) {
            Path subPom = pomDir.resolve(subMatcher.group(1).trim()).resolve("pom.xml");
            scanPomForArtifacts(subPom, artifacts);
        }

        Matcher modMatcher = MODULE_PATTERN.matcher(content);
        while (modMatcher.find()) {
            Path modPom = pomDir.resolve(modMatcher.group(1).trim()).resolve("pom.xml");
            scanPomForArtifacts(modPom, artifacts);
        }
    }

    private static String extractFirst(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    // ── Version alignment ───────────────────────────────────────

    /**
     * Align dependency versions in the newly added component's POMs
     * to match workspace SNAPSHOT versions. For each workspace component
     * that this component depends on, find explicit version declarations
     * and update them.
     *
     * @return the number of POM files modified
     */
    private int alignVersions(Path wsDir, Path componentDir,
                               String componentName, Manifest manifest)
            throws IOException {
        // Build a map: groupId:artifactId → workspace version
        // for all workspace components (except the one being added)
        Map<String, String> artifactVersions = new LinkedHashMap<>();
        for (Map.Entry<String, Component> entry : manifest.components().entrySet()) {
            if (entry.getKey().equals(componentName)) continue;
            Component comp = entry.getValue();
            if (comp.version() == null) continue;

            Path compDir = wsDir.resolve(entry.getKey());
            if (!Files.exists(compDir.resolve("pom.xml"))) continue;

            Set<PublishedArtifactSet.Artifact> published =
                    PublishedArtifactSet.scan(compDir);
            for (PublishedArtifactSet.Artifact artifact : published) {
                artifactVersions.put(
                        artifact.groupId() + ":" + artifact.artifactId(),
                        comp.version());
            }
        }

        if (artifactVersions.isEmpty()) return 0;

        // Walk all POM files in the new component and update versions
        int filesModified = 0;
        List<Path> pomFiles = findAllPomFiles(componentDir);

        for (Path pomFile : pomFiles) {
            String original = Files.readString(pomFile, StandardCharsets.UTF_8);
            String updated = alignDependencyVersions(original, artifactVersions);

            if (!updated.equals(original)) {
                Files.writeString(pomFile, updated, StandardCharsets.UTF_8);
                filesModified++;
                // Log each change
                Path relative = componentDir.getParent().relativize(pomFile);
                getLog().info("  Version alignment: " + relative);
            }
        }

        return filesModified;
    }

    /**
     * In a POM content string, find {@code <dependency>} blocks that
     * reference a known workspace artifact and update their
     * {@code <version>} to the workspace version. Skips dependencies
     * inside {@code <dependencyManagement>}.
     */
    static String alignDependencyVersions(String pom,
                                            Map<String, String> artifactVersions) {
        // Strip dependencyManagement to avoid modifying BOM imports
        // We'll process only dependencies outside of dependencyManagement
        StringBuilder result = new StringBuilder();
        Matcher dmMatcher = DEP_MGMT_BLOCK.matcher(pom);
        int lastEnd = 0;

        while (dmMatcher.find()) {
            // Process the segment before this dependencyManagement block
            String segment = pom.substring(lastEnd, dmMatcher.start());
            result.append(alignDepsInSegment(segment, artifactVersions));
            // Append the dependencyManagement block unchanged
            result.append(dmMatcher.group());
            lastEnd = dmMatcher.end();
        }
        // Process remaining content after last dependencyManagement
        result.append(alignDepsInSegment(pom.substring(lastEnd), artifactVersions));

        return result.toString();
    }

    private static String alignDepsInSegment(String segment,
                                              Map<String, String> artifactVersions) {
        Matcher depMatcher = DEPENDENCY_BLOCK.matcher(segment);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;

        while (depMatcher.find()) {
            sb.append(segment, lastEnd, depMatcher.start());

            String depBlock = depMatcher.group();
            String gid = extractFirst(GROUP_ID_PATTERN, depBlock);
            String aid = extractFirst(ARTIFACT_ID_PATTERN, depBlock);
            String key = gid + ":" + aid;

            String targetVersion = artifactVersions.get(key);
            if (targetVersion != null) {
                // Update the version in this dependency block
                String currentVersion = extractFirst(VERSION_PATTERN, depBlock);
                if (currentVersion != null && !currentVersion.equals(targetVersion)) {
                    depBlock = depBlock.replaceFirst(
                            "<version>" + Pattern.quote(currentVersion) + "</version>",
                            "<version>" + targetVersion + "</version>");
                }
            }

            sb.append(depBlock);
            lastEnd = depMatcher.end();
        }

        sb.append(segment.substring(lastEnd));
        return sb.toString();
    }

    /**
     * Find all pom.xml files in a component directory (root + submodules).
     */
    private List<Path> findAllPomFiles(Path componentDir) throws IOException {
        try (var stream = Files.walk(componentDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList();
        }
    }

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("<version>([^<]+)</version>");

    /**
     * Derive the Maven groupId from the component's root POM.
     * Strips the parent block first; if no groupId is declared
     * outside parent, falls back to the parent's groupId.
     */
    private String deriveGroupId(Path componentDir) {
        Path pomFile = componentDir.resolve("pom.xml");
        if (!Files.exists(pomFile)) return null;

        try {
            String content = Files.readString(pomFile, StandardCharsets.UTF_8);

            // Try groupId outside parent block first
            String stripped = PARENT_BLOCK.matcher(content).replaceFirst("");
            Matcher gm = GROUP_ID_PATTERN.matcher(stripped);
            if (gm.find()) return gm.group(1).trim();

            // Fall back to parent groupId
            Matcher parentBlock = PARENT_BLOCK.matcher(content);
            if (parentBlock.find()) {
                gm = GROUP_ID_PATTERN.matcher(parentBlock.group());
                if (gm.find()) return gm.group(1).trim();
            }
        } catch (IOException e) {
            // Non-fatal — groupId will be null in manifest
        }
        return null;
    }

    private static final Pattern PARENT_BLOCK =
            Pattern.compile("(?s)<parent>.*?</parent>");
    private static final Pattern GROUP_ID_PATTERN =
            Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern ARTIFACT_ID_PATTERN =
            Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern DEPENDENCY_BLOCK =
            Pattern.compile("(?s)<dependency>.*?</dependency>");
    private static final Pattern DEP_MGMT_BLOCK =
            Pattern.compile("(?s)<dependencyManagement>.*?</dependencyManagement>");
    private static final Pattern SUBPROJECT_PATTERN =
            Pattern.compile("<subproject>([^<]+)</subproject>");
    private static final Pattern MODULE_PATTERN =
            Pattern.compile("<module>([^<]+)</module>");

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Derive a component name from a git URL.
     * {@code https://github.com/ikmdev/tinkar-core.git} → {@code tinkar-core}
     */
    static String deriveComponentName(String repoUrl) {
        String name = repoUrl;
        // Strip trailing .git
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        // Strip trailing slash
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        // Take last path segment
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        return name;
    }

    /**
     * Add a component to the "all" group in workspace.yaml.
     */
    static String addToAllGroup(String yaml, String componentName) {
        // Match "  all: [...]" and add the component
        Pattern allGroup = Pattern.compile(
                "(  all:\\s*\\[)(.*?)(])", Pattern.DOTALL);
        Matcher m = allGroup.matcher(yaml);
        if (m.find()) {
            String existing = m.group(2).trim();
            String updated;
            if (existing.isEmpty()) {
                updated = componentName;
            } else {
                updated = existing + ", " + componentName;
            }
            return m.replaceFirst("$1" + Matcher.quoteReplacement(updated) + "]");
        }
        return yaml;
    }

    private Path findWorkspaceRoot() throws MojoExecutionException {
        Path dir = Path.of(System.getProperty("user.dir"));
        while (dir != null) {
            if (Files.exists(dir.resolve("workspace.yaml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new MojoExecutionException(
                "Cannot find workspace.yaml. Run from within a workspace "
                + "directory or use ws:create first.");
    }
}

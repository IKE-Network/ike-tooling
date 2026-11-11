/*
 * Copyright © 2026 IKE Network (support@ike.network)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.plugin;

import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.settings.Server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The shared input surface of the {@code ike:kb-create-*} pair
 * (IKE-Network/ike-issues#1370). Required: the name and, for a source layer, the importer
 * and release. Everything else is defaulted from the existing knowledge bases of
 * {@code solor-kb-ws} or derived, and every layer is pinned against the repository before
 * anything is shown or written.
 */
abstract class KbCreateParameters {

    /** The repository the layers are pinned against unless {@code -DlayerRepository} says. */
    static final String DEFAULT_LAYER_REPOSITORY = "https://nexus.tinkar.org/repository/ike-restricted/";

    /** The restricted releases repository, the default deploy target (decision 5). */
    static final String DEFAULT_RELEASES = "https://nexus.tinkar.org/repository/ike-restricted-releases/";

    /** The restricted snapshots repository, the default deploy target (decision 5). */
    static final String DEFAULT_SNAPSHOTS = "https://nexus.tinkar.org/repository/ike-restricted-snapshots/";

    /** How long one repository request may take. */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** The artifactId, which also names the project's folder, such as {@code my-kb}. Required. */
    @Parameter(property = "name")
    String name;

    /** {@code assembly} (the default) or {@code source-layer}. */
    @Parameter(property = "kind")
    String kind;

    /** The project's groupId. */
    @Parameter(property = "groupId", defaultValue = "network.ike.data")
    String groupId = "network.ike.data";

    /** The project's version. */
    @Parameter(property = "version", defaultValue = "1-SNAPSHOT")
    String version = "1-SNAPSHOT";

    /** The POM {@code <name>}; derived from the name when absent. */
    @Parameter(property = "displayName")
    String displayName;

    /** The POM {@code <description>}; derived from the kind and layers when absent. */
    @Parameter(property = "description")
    String description;

    /**
     * The protobuf layers in load order, comma-separated, each
     * {@code groupId:artifactId:zip:classifier:version}. The IKE starter set is put first
     * unless it is listed or a {@code reasoned-pb} layer is the base.
     */
    @Parameter(property = "layers")
    String layers;

    /** The starter set's version when it is put first. */
    @Parameter(property = "starterSetVersion", defaultValue = "1-SNAPSHOT")
    String starterSetVersion = "1-SNAPSHOT";

    /** A source layer's importer, such as {@code snomed-rf2}: {@code dev.ikm.tinkar:importer-<name>}. */
    @Parameter(property = "importer")
    String importer;

    /** A source layer's release: a Maven coordinate (pinned like a layer) or a path. */
    @Parameter(property = "release")
    String release;

    /** A source layer's importer options, such as {@code release=full}. */
    @Parameter(property = "importerOptions")
    String importerOptions;

    /** The IKE Dashboard collection named in the store's descriptor; none when absent. */
    @Parameter(property = "collection")
    String collection;

    /** The deploy repositories' server id. */
    @Parameter(property = "distributionId", defaultValue = "ike-restricted")
    String distributionId = "ike-restricted";

    /** The releases repository a deploy goes to. */
    @Parameter(property = "releasesUrl", defaultValue = DEFAULT_RELEASES)
    String releasesUrl = DEFAULT_RELEASES;

    /** The snapshots repository a deploy goes to. */
    @Parameter(property = "snapshotsUrl", defaultValue = DEFAULT_SNAPSHOTS)
    String snapshotsUrl = DEFAULT_SNAPSHOTS;

    /** The site deploy URL ike-parent's enforcer requires; {@code https://ike.network/<name>/} when absent. */
    @Parameter(property = "siteUrl")
    String siteUrl;

    /** {@code true} or {@code false} to force the Rocks store; decided by the layers' size when absent. */
    @Parameter(property = "rocks")
    String rocks;

    /** The build heap, such as {@code -Xmx24g}; estimated from the layers when absent. */
    @Parameter(property = "heap")
    String heap;

    /** The knowledge base's module UUID, whose versions alone authoring keeps; every version when absent. */
    @Parameter(property = "editModule")
    String editModule;

    /** The {@code ike-parent} version; the scaffold's tested foundation parent when absent. */
    @Parameter(property = "parentVersion")
    String parentVersion;

    /** The {@code ike-knowledge-provider} version, as solor-kb-ws pins it. */
    @Parameter(property = "providerVersion", defaultValue = "7-SNAPSHOT")
    String providerVersion = "7-SNAPSHOT";

    /** The {@code tinkar-core} version a source layer's importer comes from, as solor-kb-ws pins it. */
    @Parameter(property = "tinkarCoreVersion", defaultValue = "1.127.7-SNAPSHOT")
    String tinkarCoreVersion = "1.127.7-SNAPSHOT";

    /** The {@code rocks-kb} version, as solor-kb-ws pins it. */
    @Parameter(property = "rocksKbVersion", defaultValue = "0.1.5-SNAPSHOT")
    String rocksKbVersion = "0.1.5-SNAPSHOT";

    /** The {@code rocksdbjni} version, as solor-kb-ws pins it. */
    @Parameter(property = "rocksdbjniVersion", defaultValue = "10.4.2-r2")
    String rocksdbjniVersion = "10.4.2-r2";

    /** The {@code rocksdbjni} platform classifier; the existing knowledge bases build on macOS. */
    @Parameter(property = "rocksdbjniClassifier", defaultValue = "osx")
    String rocksdbjniClassifier = "osx";

    /** The repository the layers are pinned against. */
    @Parameter(property = "layerRepository", defaultValue = DEFAULT_LAYER_REPOSITORY)
    String layerRepository = DEFAULT_LAYER_REPOSITORY;

    /** The server id whose login in {@code settings.xml} reads the layer repository. */
    @Parameter(property = "layerRepositoryId", defaultValue = "ike-restricted")
    String layerRepositoryId = "ike-restricted";

    /** The project's folder; {@code <name>} under the directory Maven runs in when absent. */
    @Parameter(property = "targetDirectory")
    String targetDirectory;

    /**
     * The project's folder.
     *
     * @return its absolute path
     * @throws MojoException if no name is given
     */
    Path target() {
        if (targetDirectory != null && !targetDirectory.isBlank()) {
            return Path.of(targetDirectory).toAbsolutePath().normalize();
        }
        if (name == null || name.isBlank()) {
            throw new MojoException("A knowledge base needs -Dname, its artifactId, such as -Dname=my-kb");
        }
        return Path.of(System.getProperty("user.dir"), name.strip()).toAbsolutePath().normalize();
    }

    /**
     * Pins the layers against the repository and builds the plan.
     *
     * @param session      the Maven session, for its settings and offline mode
     * @param log          where progress goes
     * @param scaffoldRoot the unpacked scaffold, for the default parent version
     * @return the plan
     * @throws MojoException if a parameter is malformed, Maven runs offline, or a layer
     *                       cannot be pinned
     */
    KbCreatePlan plan(Session session, Log log, Path scaffoldRoot) {
        if (name == null || name.isBlank()) {
            throw new MojoException("A knowledge base needs -Dname, its artifactId, such as -Dname=my-kb");
        }
        if (session.getSettings().isOffline()) {
            throw new MojoException("Creating a knowledge base pins every layer against "
                    + layerRepository + "; Maven is offline");
        }
        try {
            KbKind parsedKind = KbKind.parse(kind);
            List<KbLayer> requested = new ArrayList<>();
            if (layers != null) {
                for (String spec : layers.split(",")) {
                    if (!spec.isBlank()) {
                        requested.add(KbLayer.parse(spec));
                    }
                }
            }
            List<KbLayer> ordered = KbCreatePlan.withStarterSet(requested, starterSetVersion);
            KbLayerResolver resolver = resolver(session, log);
            List<KbLayer> pinned = new ArrayList<>();
            for (KbLayer layer : ordered) {
                log.info("Pinning " + layer.coordinate(layer.version()) + " …");
                pinned.add(resolver.pin(layer));
            }
            KbLayer pinnedRelease = null;
            String releasePath = null;
            if (release != null && !release.isBlank()) {
                Optional<KbLayer> coordinate = KbCreatePlan.coordinateOf(release.strip());
                if (coordinate.isPresent()) {
                    log.info("Pinning " + release.strip() + " …");
                    pinnedRelease = resolver.pin(coordinate.get());
                } else {
                    releasePath = release.strip();
                }
            }
            Path target = target();
            return new KbCreatePlan(new KbCreatePlan.Inputs(parsedKind, groupId, name.strip(), version,
                    displayName, description,
                    parentVersion != null && !parentVersion.isBlank() ? parentVersion.strip()
                            : KbCreateSupport.foundationParentVersion(scaffoldRoot),
                    !insideWorkspace(target), target.toUri().toString(), pinned,
                    importer, pinnedRelease, releasePath, importerOptions, collection,
                    distributionId, releasesUrl, snapshotsUrl,
                    siteUrl != null && !siteUrl.isBlank() ? siteUrl.strip() : "https://ike.network/" + name.strip() + "/",
                    parseRocks(rocks), heap, editModule, providerVersion, tinkarCoreVersion,
                    rocksKbVersion, rocksdbjniVersion, rocksdbjniClassifier));
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoException("Cannot pin the layers: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoException("Interrupted pinning the layers", e);
        }
    }

    /**
     * Whether a folder is a workspace member: its parent holds a {@code workspace.yaml}, so
     * the workspace root, not the project, is the Maven root.
     *
     * @param target the project's folder
     * @return {@code true} inside a workspace
     */
    static boolean insideWorkspace(Path target) {
        Path parent = target.getParent();
        return parent != null && Files.isRegularFile(parent.resolve("workspace.yaml"));
    }

    /**
     * Reads {@code -Drocks}.
     *
     * @param value {@code true}, {@code false}, or absent
     * @return the forced choice, or {@code null} to decide by size
     * @throws IllegalArgumentException for any other value
     */
    static Boolean parseRocks(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("-Drocks takes true or false, or nothing to decide by size; got " + value);
        };
    }

    private KbLayerResolver resolver(Session session, Log log) {
        String username = null;
        String password = null;
        for (Server server : session.getSettings().getServers()) {
            if (layerRepositoryId.equals(server.getId())) {
                String secret = server.getPassword();
                if (secret != null && secret.startsWith("{") && secret.endsWith("}")) {
                    log.warn("The password for " + layerRepositoryId + " is encrypted; reading "
                            + layerRepository + " without a login");
                } else {
                    username = server.getUsername();
                    password = secret;
                }
            }
        }
        if (username == null) {
            log.info("No login for " + layerRepositoryId + " in settings.xml; reading " + layerRepository + " anonymously");
        }
        return new KbLayerResolver(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                URI.create(layerRepository), username, password, TIMEOUT);
    }
}

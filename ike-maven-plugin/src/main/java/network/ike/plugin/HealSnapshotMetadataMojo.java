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

import network.ike.plugin.deploy.SnapshotMetadataHealer;
import network.ike.plugin.deploy.SnapshotMetadataHealer.Credentials;
import network.ike.plugin.deploy.SnapshotMetadataHealer.Result;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.DeploymentRepository;
import org.apache.maven.api.model.DistributionManagement;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.settings.Server;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * {@code ike:heal-snapshot-metadata} — before a snapshot deploy, repairs the
 * version's {@code maven-metadata.xml} on the deploy target when its checksum
 * sidecars no longer match it (IKE-Network/ike-issues#1160).
 *
 * <p>Nexus can leave that pair inconsistent after its background metadata
 * rebuild (IKE-Network/ike-issues#1107), and every later deploy of the
 * version then fails checksum validation. {@code ike-parent} binds this goal
 * to {@code before:deploy}, so the repair happens ahead of
 * {@code maven-deploy-plugin} and a plain {@code install} never reaches the
 * network. See {@link SnapshotMetadataHealer} for exactly what is read and
 * deleted.
 *
 * <p>The goal never fails the build. A repository it cannot read or repair
 * is logged, and the deploy that follows reports the underlying problem.
 */
@org.apache.maven.api.plugin.annotations.Mojo(name = IkeGoal.NAME_HEAL_SNAPSHOT_METADATA)
public class HealSnapshotMetadataMojo implements Mojo {

    /** Maven logger, injected by the plugin runtime. */
    @Inject
    Log log;

    /** The Maven session, for settings (server credentials) and properties. */
    @Inject
    Session session;

    /** The project about to be deployed. */
    @Inject
    Project project;

    /** Skip the check entirely: plain Maven deploy behaviour. */
    @Parameter(property = "ike.metadata.heal.skip", defaultValue = "false")
    boolean skip;

    /** Timeout, in seconds, for each request to the repository. */
    @Parameter(property = "ike.metadata.heal.timeoutSeconds", defaultValue = "20")
    int timeoutSeconds;

    /** Creates this goal instance. */
    public HealSnapshotMetadataMojo() {}

    @Override
    public void execute() {
        if (skip) {
            log.info("Snapshot metadata check skipped (ike.metadata.heal.skip)");
            return;
        }
        String version = project.getVersion();
        if (!version.endsWith("-SNAPSHOT")) {
            log.debug("Snapshot metadata check: " + version + " is a release; nothing to do");
            return;
        }
        if (Boolean.parseBoolean(property("maven.deploy.skip"))) {
            log.debug("Snapshot metadata check: maven.deploy.skip is set; nothing to do");
            return;
        }
        Optional<Target> target = snapshotTarget();
        if (target.isEmpty()) {
            log.debug("Snapshot metadata check: no snapshot deploy repository; nothing to do");
            return;
        }
        Target repository = target.get();
        SnapshotMetadataHealer healer = new SnapshotMetadataHealer(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                Duration.ofSeconds(timeoutSeconds));
        Result result = healer.heal(URI.create(repository.url()), credentials(repository.id()),
                project.getGroupId(), project.getArtifactId(), version);
        report(repository, result);
    }

    private void report(Target repository, Result result) {
        String where = project.getArtifactId() + ":" + project.getVersion()
                + " on " + repository.id();
        switch (result.outcome()) {
            case HEALED -> {
                log.warn("Snapshot metadata for " + where + " did not match its checksums ("
                        + result.detail() + "); deleted so this deploy rewrites it"
                        + " (IKE-Network/ike-issues#1107):");
                result.deleted().forEach(path -> log.warn("  deleted " + path));
            }
            case DELETE_FAILED -> {
                log.warn("Snapshot metadata for " + where + " does not match its checksums,"
                        + " and could not be deleted: " + result.detail());
                log.warn("  The deploy will likely fail with 'Checksum validation failed'."
                        + " Delete the version's maven-metadata.xml and its checksum files"
                        + " on the repository by hand (IKE-Network/ike-issues#1107).");
                result.deleted().forEach(path -> log.warn("  deleted " + path));
            }
            case UNREACHABLE -> log.warn("Snapshot metadata check for " + where
                    + " could not read the repository: " + result.detail());
            case CONSISTENT, ABSENT, NOT_A_SNAPSHOT -> log.debug("Snapshot metadata for "
                    + where + ": " + result.detail());
        }
    }

    /** The snapshot deploy target: an {@code alt*DeploymentRepository} override, else the POM's. */
    private Optional<Target> snapshotTarget() {
        for (String override : new String[]{"altSnapshotDeploymentRepository", "altDeploymentRepository"}) {
            String value = property(override);
            if (value != null && !value.isBlank()) {
                return Target.parse(value);
            }
        }
        DistributionManagement distribution = project.getModel().getDistributionManagement();
        if (distribution == null) {
            return Optional.empty();
        }
        DeploymentRepository repository = distribution.getSnapshotRepository() != null
                ? distribution.getSnapshotRepository()
                : distribution.getRepository();
        if (repository == null || repository.getUrl() == null || repository.getId() == null) {
            return Optional.empty();
        }
        return Optional.of(new Target(repository.getId(), repository.getUrl()));
    }

    /** The server credentials Maven's settings hold for {@code id}, when usable. */
    private Optional<Credentials> credentials(String id) {
        for (Server server : session.getSettings().getServers()) {
            if (!id.equals(server.getId())) {
                continue;
            }
            String username = server.getUsername();
            String password = server.getPassword();
            if (username == null || password == null) {
                return Optional.empty();
            }
            if (password.startsWith("{") && password.endsWith("}")) {
                log.debug("Snapshot metadata check: the password for " + id
                        + " is encrypted; reading without credentials");
                return Optional.empty();
            }
            return Optional.of(new Credentials(username, password));
        }
        return Optional.empty();
    }

    /** A user, system or project property; the first defined wins. */
    private String property(String name) {
        String value = session.getUserProperties().get(name);
        if (value == null) {
            value = session.getSystemProperties().get(name);
        }
        if (value == null) {
            value = project.getModel().getProperties().get(name);
        }
        return value;
    }

    /**
     * A deploy target: a server id and a repository URL.
     *
     * @param id  the server id, which keys the credentials in settings
     * @param url the repository URL
     */
    record Target(String id, String url) {

        /**
         * Parses Maven's {@code id::url} (or legacy {@code id::layout::url})
         * deployment-repository override.
         */
        static Optional<Target> parse(String value) {
            String[] parts = value.split("::");
            if (parts.length == 2) {
                return Optional.of(new Target(parts[0], parts[1]));
            }
            if (parts.length == 3) {
                return Optional.of(new Target(parts[0], parts[2]));
            }
            return Optional.empty();
        }
    }
}

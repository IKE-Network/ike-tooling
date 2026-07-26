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

import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ProjectManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Attaches produced knowledge artifacts to <em>another</em> reactor project — typically
 * the aggregator — so a knowledge set's released coordinate carries the set's own name
 * even though a subproject produced the files (IKE-Network/ike-issues#933). An
 * aggregator builds before its subprojects and cannot run the exports itself; this goal
 * runs in the producing subproject (after {@code ike:knowledge-export} and
 * {@code ike:kb-assemble}) and attaches each file under the target project's GAV, so
 * the released coordinate reads, for example,
 * {@code network.ike.foundation:ike-starter-set:<v>:reasoned-pb}.
 * <p>
 * <b>Pair with {@code deployAtEnd}/{@code installAtEnd}.</b> The target project's own
 * {@code install}/{@code deploy} executions run at its reactor position — before this
 * goal attaches — so the consuming reactor must configure
 * {@code maven-install-plugin} {@code installAtEnd=true} and {@code maven-deploy-plugin}
 * {@code deployAtEnd=true}; both defer publication to session end, where the late
 * attachments are included. Without that pairing the attachments never publish — this
 * goal cannot detect the misconfiguration, so it is stated loudly here and in the
 * consuming POM.
 */
@Mojo(name = IkeGoal.NAME_KNOWLEDGE_ATTACH, defaultPhase = "verify")
public class KnowledgeAttachMojo implements org.apache.maven.api.plugin.Mojo {

    /** Creates this goal instance. */
    public KnowledgeAttachMojo() {}

    @Inject
    private org.apache.maven.api.plugin.Log log;

    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() {
        return log;
    }

    @Inject
    private Session session;

    @Inject
    private Project project;

    /**
     * The target project's groupId — the GAV the attachments publish under. Defaults to
     * this project's own groupId (aggregator and subprojects usually share one).
     */
    @Parameter(property = "ike.knowledgeAttach.targetGroupId",
               defaultValue = "${project.groupId}")
    String targetGroupId;

    /**
     * The target project's artifactId — typically the aggregator whose name is the
     * knowledge set's released coordinate.
     */
    @Parameter(property = "ike.knowledgeAttach.targetArtifactId", required = true)
    String targetArtifactId;

    /**
     * The attachments, each {@code "<classifier> <file>"} — mirroring the
     * {@code ike:kb-assemble} input idiom of a marker followed by a spec. Example:
     * {@code <attachment>reasoned-pb ${project.build.directory}/x-reasoned-pb.zip</attachment>}.
     * Extension is always {@code zip}.
     */
    @Parameter
    List<String> attachments = List.of();

    /**
     * Skip attaching.
     */
    @Parameter(property = "ike.knowledgeAttach.skip", defaultValue = "false")
    boolean skip;

    /**
     * Resolves the target reactor project and attaches each declared file under its
     * GAV.
     *
     * @throws MojoException if the target project is not in the reactor, an attachment
     *                       entry is malformed, or a declared file does not exist
     */
    @Override
    public void execute() {
        if (skip) {
            getLog().info("ike:knowledge-attach skipped (ike.knowledgeAttach.skip=true)");
            return;
        }
        if (attachments.isEmpty()) {
            throw new MojoException("ike:knowledge-attach requires at least one"
                    + " <attachments><attachment> entry (\"<classifier> <file>\")");
        }

        Project target = session.getProjects().stream()
                .filter(candidate -> candidate.getGroupId().equals(targetGroupId)
                        && candidate.getArtifactId().equals(targetArtifactId))
                .findFirst()
                .orElseThrow(() -> new MojoException("Attach target " + targetGroupId + ":"
                        + targetArtifactId + " is not in this reactor — ike:knowledge-attach"
                        + " publishes under a reactor project's GAV (run from the full"
                        + " reactor, not with -pl alone)"));

        ProjectManager projectManager = session.getService(ProjectManager.class);
        for (String entry : attachments) {
            String stripped = entry.strip();
            int space = stripped.indexOf(' ');
            if (space <= 0) {
                throw new MojoException("Attachment entry needs \"<classifier> <file>\": " + entry);
            }
            String classifier = stripped.substring(0, space);
            Path file = Path.of(stripped.substring(space + 1).strip());
            if (!Files.isRegularFile(file)) {
                throw new MojoException("Attachment file does not exist: " + file
                        + " (classifier " + classifier + ") — run the producing goal first");
            }
            ProducedArtifact artifact = session.createProducedArtifact(
                    target.getGroupId(), target.getArtifactId(), target.getVersion(),
                    classifier, "zip", "zip");
            projectManager.attachArtifact(target, artifact, file);
            getLog().info("Attached " + file.getFileName() + " under " + target.getGroupId()
                    + ":" + target.getArtifactId() + ":" + target.getVersion()
                    + " (classifier: " + classifier + ", extension: zip)");
        }
    }
}

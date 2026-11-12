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

import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

/**
 * Gives a project its remote, the executing half of the {@code ike:kb-remote-*} pair
 * (IKE-Network/ike-issues#1370, decision 4): sets {@code origin}, replaces the
 * {@code file:} URLs in the {@code <scm>} of {@code pom.xml} and commits that file alone,
 * replaces the {@code repo:} of the project in its working set's {@code workspace.yaml}
 * when it is a member of one, and pushes the branch, which then tracks {@code origin}.
 *
 * <p>Run {@code ike:kb-remote-draft} first. A push that would fail, because the remote
 * does not answer or already has other commits on the branch, is refused before anything
 * is changed. When the push itself fails, the remote is set and the POM committed; run the
 * goal again to push. The remote is the project's own, not the knowledge base's.
 *
 * @since 281
 */
@Mojo(name = IkeGoal.NAME_KB_REMOTE_PUBLISH, projectRequired = false)
public class KbRemotePublishMojo extends KbRemoteParameters implements org.apache.maven.api.plugin.Mojo {

    /** Creates this goal instance. */
    public KbRemotePublishMojo() {}

    @Inject
    private org.apache.maven.api.plugin.Log log;

    /**
     * Set the remote and commit the POM without pushing, for a remote that cannot be
     * reached yet; push later with {@code git push -u origin <branch>}.
     */
    @Parameter(property = "skipPush", defaultValue = "false")
    boolean skipPush;

    /**
     * Sets the remote, commits and pushes.
     *
     * @throws MojoException if the project cannot be read, the plan breaks a rule, the
     *                       push would fail, or git fails
     */
    @Override
    public void execute() {
        KbRemotePlan plan = plan(!skipPush);
        KbRemoteSupport.apply(log, plan);
        for (String line : plan.report()) {
            log.info(line);
        }
        log.info(skipPush ? "The remote is set. Nothing was pushed." : "The remote is set and the project is pushed.");
    }
}

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

/**
 * Previews giving a project its remote, the side-effect-free half of the
 * {@code ike:kb-remote-*} pair (IKE-Network/ike-issues#1370): whether the remote answers
 * and is empty, what {@code origin} becomes, each {@code <scm>} URL of {@code pom.xml}
 * before and after, the working set's {@code repo:} when the project is a member of one,
 * and the push. The remote is asked for its branches; nothing is changed. Rerun as
 * {@code kb-remote-publish} with the same inputs to do exactly what this draft shows.
 *
 * <pre>{@code
 * mvn ike:kb-remote-draft -Dremote=https://github.com/my-org/my-kb.git
 * }</pre>
 *
 * @since 281
 */
@Mojo(name = IkeGoal.NAME_KB_REMOTE_DRAFT, projectRequired = false)
public class KbRemoteDraftMojo extends KbRemoteParameters implements org.apache.maven.api.plugin.Mojo {

    /** Creates this goal instance. */
    public KbRemoteDraftMojo() {}

    @Inject
    private org.apache.maven.api.plugin.Log log;

    /**
     * Prints the plan.
     *
     * @throws MojoException if the project cannot be read or the plan breaks a rule
     */
    @Override
    public void execute() {
        KbRemotePlan plan = plan(true);
        for (String line : plan.report()) {
            log.info(line);
        }
        plan.pushProblem().ifPresent(problem -> log.warn("  " + problem + " Publish will refuse."));
        log.info("Draft only — run ike:kb-remote-publish with the same inputs to do it.");
    }
}

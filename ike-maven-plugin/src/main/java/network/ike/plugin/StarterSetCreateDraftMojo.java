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
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.nio.file.Path;
import java.util.Map;

/**
 * Previews the genesis of a new starter-set project — the side-effect-free half of
 * the {@code ike:starter-set-create-*} pair (IKE-Network/ike-issues#866): the derived
 * name family, the semantic tag (a headline item — it lives in every birth FQN
 * forever), the UUID posture, the file list, and the next-step commands. Nothing is
 * minted and nothing is written; rerun as {@code starter-set-create-publish} to
 * execute exactly what this draft shows.
 *
 * <p>Genesis is distinct from the scaffold system: scaffold goals reconcile an
 * existing project's build conventions, repeatedly and safely; genesis runs once and
 * mints permanent identity. After genesis the generated project enters the ordinary
 * scaffold-conformance regime.
 *
 * @since 235
 */
@Mojo(name = IkeGoal.NAME_STARTER_SET_CREATE_DRAFT, projectRequired = false)
public class StarterSetCreateDraftMojo extends StarterSetCreateParameters
        implements org.apache.maven.api.plugin.Mojo {

    /** Creates this goal instance. */
    public StarterSetCreateDraftMojo() {}

    @Inject
    private org.apache.maven.api.plugin.Log log;

    @Inject
    private Session session;

    /**
     * Prints the genesis plan.
     */
    @Override
    public void execute() {
        StarterSetPlan plan = plan("<minted-at-publish>", log, session);
        Map<String, String> templates = StarterSetCreateSupport.loadTemplates(session);
        Path target = targetDirectory(plan);
        for (String line : StarterSetCreateSupport
                .report(plan, templates, target, setUuid == null || setUuid.isBlank())
                .split("\n")) {
            log.info(line);
        }
        log.info("Draft only — run ike:starter-set-create-publish with the same inputs to execute.");
    }
}

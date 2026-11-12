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

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.nio.file.Path;

/**
 * The parameters the {@code ike:kb-remote-draft} and {@code ike:kb-remote-publish} goals
 * share (IKE-Network/ike-issues#1370).
 */
abstract class KbRemoteParameters {

    /** Creates the shared parameter holder. */
    KbRemoteParameters() {}

    /**
     * The remote's git URL on any git host, such as
     * {@code https://github.com/my-org/my-kb.git} or {@code git@github.com:my-org/my-kb.git}.
     * Create the repository there first, empty.
     */
    @Parameter(property = "remote")
    String remote;

    /** The project's folder, the root of its git repository; the current folder when not given. */
    @Parameter(property = "projectRoot")
    String projectRoot;

    /**
     * The project's folder.
     *
     * @return its absolute path
     */
    Path root() {
        return Path.of(projectRoot == null || projectRoot.isBlank() ? System.getProperty("user.dir") : projectRoot)
                .toAbsolutePath().normalize();
    }

    /**
     * Reads the project and builds the plan.
     *
     * @param push whether the branch is pushed once the remote is set
     * @return the plan
     * @throws MojoException if the project cannot be read or the plan breaks a rule
     */
    KbRemotePlan plan(boolean push) {
        try {
            return KbRemoteSupport.plan(root(), remote, push);
        } catch (IllegalArgumentException e) {
            throw new MojoException(e.getMessage(), e);
        }
    }
}

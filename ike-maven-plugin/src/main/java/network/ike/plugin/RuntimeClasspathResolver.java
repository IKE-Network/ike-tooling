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

import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.services.DependencyResolver;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one entry point through which this plugin's goals resolve a project's
 * {@code MAIN_RUNTIME} classpath (IKE-Network/ike-issues#901).
 *
 * <p>Maven 4 rc-5's {@code DefaultDependencyResolver} keeps its session-scoped module
 * cache in a plain, unsynchronized {@code HashMap}: two goals resolving concurrently in
 * a {@code -T} reactor race its {@code computeIfAbsent} and fail with
 * {@code ConcurrentModificationException} — observed nondeterministically from
 * {@code knowledge-export} and {@code knowledge-bindings} building the ike-starter-set
 * reactor in parallel. The defect is in Maven core, not in this plugin's own state, so
 * the remedy here is to serialize just this plugin's entry into the racy API with a
 * JVM-wide lock. Resolution is fast and per-project, so the contention cost is
 * negligible; remove the lock when the build adopts a Maven release carrying the
 * announced rc-6 {@code -T} concurrency fixes.
 */
final class RuntimeClasspathResolver {

    private static final ReentrantLock RESOLVER_LOCK = new ReentrantLock();

    private RuntimeClasspathResolver() {
    }

    /**
     * Resolves the project's {@code MAIN_RUNTIME} dependency paths, serialized
     * JVM-wide against every other goal in this plugin doing the same.
     *
     * @param session the Maven session
     * @param project the project whose runtime classpath to resolve
     * @return the resolved dependency paths, in resolver order
     */
    static List<Path> mainRuntimePaths(Session session, Project project) {
        RESOLVER_LOCK.lock();
        try {
            return session.getService(DependencyResolver.class)
                    .resolve(session, project, PathScope.MAIN_RUNTIME)
                    .getPaths();
        } finally {
            RESOLVER_LOCK.unlock();
        }
    }
}

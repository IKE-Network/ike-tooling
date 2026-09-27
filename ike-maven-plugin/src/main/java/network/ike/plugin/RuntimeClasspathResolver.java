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
 * the remedy is to serialize just this plugin's entry into the racy API with a
 * JVM-wide lock.
 *
 * <p>Maven 4.0.0-rc-7 fixed it: the cache is a {@code ConcurrentHashMap}
 * (IKE-Network/ike-issues#1153). The plugin runs on whatever Maven its caller uses,
 * though, so the lock stays for callers still on an earlier Maven and is skipped on
 * rc-7 and later. Delete it once no working set runs a Maven before rc-7.
 */
final class RuntimeClasspathResolver {

    private static final ReentrantLock RESOLVER_LOCK = new ReentrantLock();

    /** The first Maven whose dependency resolver is safe to call concurrently. */
    static final String FIRST_THREAD_SAFE_MAVEN = "4.0.0-rc-7";

    private RuntimeClasspathResolver() {
    }

    /**
     * Resolves the project's {@code MAIN_RUNTIME} dependency paths. On a Maven
     * before {@value #FIRST_THREAD_SAFE_MAVEN} the call is serialized JVM-wide
     * against every other goal in this plugin doing the same.
     *
     * @param session the Maven session
     * @param project the project whose runtime classpath to resolve
     * @return the resolved dependency paths, in resolver order
     */
    static List<Path> mainRuntimePaths(Session session, Project project) {
        if (resolverIsThreadSafe(session)) {
            return resolve(session, project);
        }
        RESOLVER_LOCK.lock();
        try {
            return resolve(session, project);
        } finally {
            RESOLVER_LOCK.unlock();
        }
    }

    /**
     * Whether the running Maven's dependency resolver may be called concurrently:
     * {@value #FIRST_THREAD_SAFE_MAVEN} or later, compared with Maven's own version
     * ordering (so {@code 4.0.0} and later releases count, {@code 4.0.0-rc-5} does not).
     */
    private static boolean resolverIsThreadSafe(Session session) {
        return session.getMavenVersion().compareTo(session.parseVersion(FIRST_THREAD_SAFE_MAVEN)) >= 0;
    }

    private static List<Path> resolve(Session session, Project project) {
        return session.getService(DependencyResolver.class)
                .resolve(session, project, PathScope.MAIN_RUNTIME)
                .getPaths();
    }
}

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

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code ike:changeset-harvest} — copy the change sets captured in an installed knowledge
 * base into the project, before anything is built from them. A browser writes what a person
 * authors in the store as one change set per session; the next install replaces the store,
 * so they are harvested into the project directory that keeps the extension, to be committed
 * and merged. With {@code changeSetModules}, only a change set whose stamps reference one of
 * them is harvested; the rest (what a browser writes on its own) are parked in
 * {@code <installDirectory>-sessions}. A session still being written stops the build.
 * {@code ike:kb-assemble} can harvest too; this goal lets a multi-module build harvest in the
 * module that merges the extension, ahead of the one that assembles the knowledge base.
 */
@Mojo(name = IkeGoal.NAME_CHANGESET_HARVEST, defaultPhase = "initialize")
public class ChangeSetHarvestMojo implements org.apache.maven.api.plugin.Mojo {

    /** Instantiated by Maven. */
    public ChangeSetHarvestMojo() {
    }

    @Inject
    private Log log;

    /** The installed knowledge base whose captured change sets are harvested. */
    @Parameter(property = "ike.changeset.installDirectory", required = true)
    String installDirectory;

    /** The project directory that keeps the harvested change sets. */
    @Parameter(property = "ike.changeset.directory", required = true)
    String changeSetDirectory;

    /** The extension's modules, by UUID; empty harvests every captured change set. */
    @Parameter
    List<String> changeSetModules = List.of();

    @Parameter(property = "ike.changeset.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() {
        if (skip) {
            log.info("ike:changeset-harvest skipped (ike.changeset.skip=true)");
            return;
        }
        Path install = Path.of(installDirectory);
        List<Path> captured = ChangeSetHarvest.captured(install);
        if (!changeSetModules.isEmpty() && !captured.isEmpty()) {
            Path parking = Path.of(installDirectory + "-sessions");
            List<Path> extension = ChangeSetHarvest.sortOut(captured,
                    changeSetModules.stream().map(String::strip).collect(Collectors.toSet()), parking);
            if (extension.size() < captured.size()) {
                log.info("Parked " + (captured.size() - extension.size()) + " change set(s) with no content in "
                        + changeSetModules + " in " + parking);
            }
            captured = extension;
        }
        Path directory = Path.of(changeSetDirectory);
        List<Path> harvested = ChangeSetHarvest.harvest(captured, directory);
        harvested.forEach(file -> log.info("Harvested change set " + file.getFileName() + " into " + directory));
        if (harvested.isEmpty()) {
            log.info("No new change sets in " + install);
        }
    }
}

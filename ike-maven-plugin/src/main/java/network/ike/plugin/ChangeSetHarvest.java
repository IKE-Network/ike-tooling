package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * The change sets a knowledge browser captured in an installed knowledge base, and the
 * project directory they are kept in. An install replaces the store wholesale, so what a
 * person authored there (Komet writes each session's edits as a change set under the store's
 * {@code changeSets} directory) is harvested into the project first: kept, committed, and
 * replayed as {@code CHANGESET} inputs by every later assembly, on top of whatever new
 * releases the sources bring.
 */
final class ChangeSetHarvest {

    /** Where a store keeps captured change sets, relative to its root. */
    static final String CHANGE_SETS = "changeSets";

    private ChangeSetHarvest() {
    }

    /**
     * The change sets captured in an installed store, in name order.
     *
     * @param installDirectory the installed store
     * @return the captured change sets; none when the store or its change-set directory is absent
     * @throws MojoException when a change set is still being written: the store is open in a browser
     */
    static List<Path> captured(Path installDirectory) {
        Path changeSets = installDirectory.resolve(CHANGE_SETS);
        if (!Files.isDirectory(changeSets)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(changeSets)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            List<Path> open = files.stream().filter(f -> f.getFileName().toString().endsWith(".spool")).toList();
            if (!open.isEmpty()) {
                throw new MojoException("The knowledge base at " + installDirectory + " is open: change sets are"
                        + " still being written (" + open + "). Close it in the knowledge browser, then assemble.");
            }
            return files.stream().filter(f -> f.getFileName().toString().endsWith(".zip")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Reading the change sets of " + installDirectory, e);
        }
    }

    /**
     * Copies captured change sets the project does not yet keep into its change-set directory.
     * A change set already kept under the same name must be the same file.
     *
     * @param captured  the captured change sets
     * @param directory the project's change-set directory
     * @return the change sets copied
     * @throws MojoException when a kept change set and a captured one share a name but differ
     */
    static List<Path> harvest(List<Path> captured, Path directory) {
        try {
            Files.createDirectories(directory);
            List<Path> copied = new ArrayList<>();
            for (Path file : captured) {
                Path kept = directory.resolve(file.getFileName().toString());
                if (Files.exists(kept)) {
                    if (!Arrays.equals(Files.readAllBytes(kept), Files.readAllBytes(file))) {
                        throw new MojoException("The captured change set " + file + " differs from the one kept as "
                                + kept + " under the same name; resolve which is right before assembling.");
                    }
                    continue;
                }
                Files.copy(file, kept, StandardCopyOption.COPY_ATTRIBUTES);
                copied.add(kept);
            }
            return copied;
        } catch (IOException e) {
            throw new UncheckedIOException("Harvesting change sets into " + directory, e);
        }
    }

    /**
     * Whether a change set carries content in any of the given modules: its manifest names
     * every concept its stamps reference, modules among them, by their UUIDs.
     *
     * @param changeSet   the change set
     * @param moduleUuids the modules' UUIDs
     * @return true when the manifest names one of the modules
     */
    static boolean carriesModule(Path changeSet, java.util.Set<String> moduleUuids) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(changeSet.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
            if (entry == null) {
                return false;
            }
            java.util.jar.Manifest manifest = new java.util.jar.Manifest(zip.getInputStream(entry));
            return manifest.getEntries().keySet().stream()
                    .flatMap(name -> Arrays.stream(name.split(",")))
                    .map(String::strip)
                    .anyMatch(moduleUuids::contains);
        } catch (IOException e) {
            throw new UncheckedIOException("Reading the manifest of " + changeSet, e);
        }
    }

    /**
     * Moves the captured change sets that carry no content in the extension's modules out of
     * the way, into a parking directory beside the store, so the harvest takes only authored
     * content and nothing a browser wrote on its own (a plugin's bootstrap, an assistant's
     * conversation) is lost.
     *
     * @param captured    the captured change sets
     * @param moduleUuids the extension's modules' UUIDs
     * @param parking     where the others go
     * @return the captured change sets that carry the extension's modules
     */
    static List<Path> sortOut(List<Path> captured, java.util.Set<String> moduleUuids, Path parking) {
        List<Path> extension = new ArrayList<>();
        try {
            for (Path file : captured) {
                if (carriesModule(file, moduleUuids)) {
                    extension.add(file);
                } else {
                    Files.createDirectories(parking);
                    Path parked = parking.resolve(file.getFileName().toString());
                    if (!Files.exists(parked)) {
                        Files.copy(file, parked, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            return extension;
        } catch (IOException e) {
            throw new UncheckedIOException("Parking change sets in " + parking, e);
        }
    }

    /**
     * The change sets a project keeps, in name order: the order they are replayed in.
     *
     * @param directory the project's change-set directory
     * @return the kept change sets; none when the directory is absent
     */
    static List<Path> kept(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(directory)) {
            return list.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".zip")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Listing the change sets in " + directory, e);
        }
    }
}

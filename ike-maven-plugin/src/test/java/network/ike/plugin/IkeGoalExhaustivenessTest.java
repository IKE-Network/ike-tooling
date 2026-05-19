package network.ike.plugin;

import org.apache.maven.api.plugin.annotations.Mojo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-reference check: {@link IkeGoal} must stay in lockstep with the
 * {@link Mojo @Mojo} declarations on the ike plugin's mojo classes.
 *
 * <p>Two failure modes are both guarded:
 * <ul>
 *   <li>An enum entry's {@code goalName} doesn't match its mojo class's
 *       {@code @Mojo(name = ...)}.</li>
 *   <li>A new {@code @Mojo} class is added without a matching enum
 *       entry, or vice versa.</li>
 * </ul>
 *
 * <p>See issue #166.
 */
class IkeGoalExhaustivenessTest {

    /**
     * Matches the {@code @Mojo(name = …)} value in either form:
     * <ul>
     *   <li>group 1 — the legacy {@code "literal"} form;</li>
     *   <li>group 2 — the post-#453 {@code IkeGoal.NAME_*} constant form,
     *       captured as the SCREAMING_SNAKE_CASE suffix.</li>
     * </ul>
     * {@link #scanMojoNamesInSource()} normalises group 2 to kebab-case.
     */
    private static final Pattern MOJO_NAME = Pattern.compile(
            "@(?:[\\w.]*\\.)?Mojo\\s*\\(\\s*name\\s*=\\s*"
                    + "(?:\"([a-z][a-z0-9-]*)\""
                    + "|IkeGoal\\.NAME_([A-Z][A-Z0-9_]*))");

    @Test
    void every_enum_entry_matches_its_mojo_annotation() {
        for (IkeGoal goal : IkeGoal.values()) {
            Mojo annotation = goal.mojoClass().getAnnotation(Mojo.class);
            assertThat(annotation)
                    .withFailMessage("IkeGoal.%s mojoClass %s has no @Mojo annotation",
                            goal.name(), goal.mojoClass().getSimpleName())
                    .isNotNull();
            assertThat(annotation.name())
                    .withFailMessage("IkeGoal.%s declares goalName=\"%s\""
                                    + " but @Mojo on %s says \"%s\"",
                            goal.name(), goal.goalName(),
                            goal.mojoClass().getSimpleName(), annotation.name())
                    .isEqualTo(goal.goalName());
        }
    }

    @Test
    void enum_names_cover_every_mojo_in_source() throws IOException {
        Set<String> fromSource = scanMojoNamesInSource();
        Set<String> fromEnum = Arrays.stream(IkeGoal.values())
                .map(IkeGoal::goalName)
                .collect(Collectors.toSet());

        Set<String> missingFromEnum = new HashSet<>(fromSource);
        missingFromEnum.removeAll(fromEnum);
        Set<String> missingFromSource = new HashSet<>(fromEnum);
        missingFromSource.removeAll(fromSource);

        assertThat(missingFromEnum)
                .withFailMessage("@Mojo names without an IkeGoal entry: %s",
                        missingFromEnum)
                .isEmpty();
        assertThat(missingFromSource)
                .withFailMessage("IkeGoal entries without a matching @Mojo"
                                + " in source: %s",
                        missingFromSource)
                .isEmpty();
    }

    private static Set<String> scanMojoNamesInSource() throws IOException {
        Path srcMain = Path.of("src/main/java");
        Set<String> names = new HashSet<>();
        try (Stream<Path> files = Files.walk(srcMain)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(file)
                        || !file.toString().endsWith(".java")) {
                    continue;
                }
                Matcher m = MOJO_NAME.matcher(Files.readString(file));
                while (m.find()) {
                    String literal = m.group(1);
                    if (literal != null) {
                        names.add(literal);
                    } else {
                        // IkeGoal.NAME_<CONST> form — convert
                        // SCREAMING_SNAKE_CASE back to kebab-case.
                        names.add(m.group(2)
                                .toLowerCase(Locale.ROOT)
                                .replace('_', '-'));
                    }
                }
            }
        }
        return names;
    }
}

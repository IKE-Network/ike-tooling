package network.ike.plugin;

import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.di.Injector;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verify that every mojo in the plugin can be constructed by Maven 4's
 * DI injector using only the four types that
 * {@code DefaultMavenPluginManager.loadV4Mojo} binds:
 * {@link Session}, {@link Project}, {@link MojoExecution}, and {@link Log}.
 *
 * <p>This catches the class of bug where a mojo field-injects a service
 * (e.g. {@code ProjectManager}) that is NOT directly bound by the plugin
 * injector. Such services must be obtained at runtime via
 * {@code session.getService()}.
 *
 * <p>Mojo classes are discovered from {@code META-INF/maven/plugin.xml}
 * on the test classpath, so adding a new mojo automatically gets coverage.
 */
class MojoDiWiringTest {

    /**
     * The four types that Maven 4 rc-5 {@code DefaultMavenPluginManager.loadV4Mojo}
     * binds directly into the plugin injector via {@code bindInstance}.
     */
    private static final Set<Class<?>> BOUND_TYPES = Set.of(
            Log.class, Session.class, Project.class, MojoExecution.class);

    // ── DI construction test ────────────────────────────────────────

    /**
     * For each mojo listed in {@code plugin.xml}, create an {@link Injector}
     * that mirrors Maven 4's plugin injector, bind the four standard types,
     * and verify that the mojo can be fully constructed without missing bindings.
     */
    @TestFactory
    Stream<DynamicTest> everyMojoCanBeConstructed() {
        List<Class<? extends Mojo>> mojoClasses = discoverMojoClasses();
        assertThat(mojoClasses)
                .as("Should discover mojos from plugin.xml on the test classpath. "
                        + "Run 'mvn compile' to generate META-INF/maven/plugin.xml")
                .isNotEmpty();

        return mojoClasses.stream().map(mojoClass ->
                DynamicTest.dynamicTest(
                        "DI wiring: " + mojoClass.getSimpleName(),
                        () -> {
                            Injector injector = createPluginInjector();
                            injector.bindImplicit(mojoClass);
                            Mojo mojo = (Mojo) injector.getInstance(mojoClass);
                            assertThat(mojo)
                                    .as("Injector should construct %s without missing bindings",
                                            mojoClass.getSimpleName())
                                    .isNotNull();
                        }));
    }

    // ── Static analysis: no unbound @Inject fields ──────────────────

    /**
     * For each mojo, verify that every {@code @Inject}-annotated field has a
     * type that Maven 4's plugin injector actually binds. Fields of other types
     * must use {@code session.getService()} instead.
     *
     * <p>This is a compile-time guard — it catches the bug at the declaration
     * level without needing the injector at all.
     */
    @TestFactory
    Stream<DynamicTest> noMojoDirectlyInjectsUnboundService() {
        List<Class<? extends Mojo>> mojoClasses = discoverMojoClasses();
        assertThat(mojoClasses).isNotEmpty();

        return mojoClasses.stream().map(mojoClass ->
                DynamicTest.dynamicTest(
                        "No unbound @Inject: " + mojoClass.getSimpleName(),
                        () -> {
                            for (Class<?> c = mojoClass; c != null && c != Object.class;
                                 c = c.getSuperclass()) {
                                for (var field : c.getDeclaredFields()) {
                                    if (!field.isAnnotationPresent(
                                            org.apache.maven.api.di.Inject.class)) {
                                        continue;
                                    }
                                    // @Parameter fields are set by Maven XML config, not DI
                                    if (field.isAnnotationPresent(Parameter.class)) {
                                        continue;
                                    }
                                    assertThat(BOUND_TYPES)
                                            .as("@Inject field '%s.%s' of type %s is not bound "
                                                            + "by Maven 4's plugin injector. Use "
                                                            + "session.getService(%s.class) instead.",
                                                    mojoClass.getSimpleName(),
                                                    field.getName(),
                                                    field.getType().getSimpleName(),
                                                    field.getType().getSimpleName())
                                            .contains(field.getType());
                                }
                            }
                        }));
    }

    // ── Cross-check: plugin.xml vs @Mojo-annotated classes ──────────

    /**
     * Verify that every concrete class annotated with {@code @Mojo} in the
     * plugin package appears in {@code plugin.xml}. Guards against a stale
     * descriptor after adding a new mojo without regenerating.
     */
    @Test
    void pluginDescriptorCoversAllMojoAnnotatedClasses() throws Exception {
        List<Class<? extends Mojo>> descriptorClasses = discoverMojoClasses();
        List<String> descriptorClassNames = descriptorClasses.stream()
                .map(Class::getName)
                .toList();

        // Scan the compiled classes directory for @Mojo-annotated types
        var classDir = java.nio.file.Path.of(
                MojoDiWiringTest.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
        var packageDir = classDir.resolve("network/ike/plugin");

        if (!java.nio.file.Files.isDirectory(packageDir)) {
            // Running from JAR or unusual layout — skip this cross-check
            return;
        }

        List<String> annotatedClassNames;
        try (var files = java.nio.file.Files.list(packageDir)) {
            annotatedClassNames = files
                    .filter(p -> p.toString().endsWith(".class"))
                    .map(p -> {
                        String fileName = p.getFileName().toString();
                        return "network.ike.plugin."
                                + fileName.substring(0, fileName.length() - 6);
                    })
                    .filter(name -> {
                        try {
                            Class<?> cls = Class.forName(name);
                            return cls.isAnnotationPresent(
                                    org.apache.maven.api.plugin.annotations.Mojo.class)
                                    && Mojo.class.isAssignableFrom(cls)
                                    && !java.lang.reflect.Modifier.isAbstract(
                                    cls.getModifiers());
                        } catch (ClassNotFoundException e) {
                            return false;
                        }
                    })
                    .toList();
        }

        assertThat(descriptorClassNames)
                .as("plugin.xml should list every @Mojo-annotated class. "
                        + "Run 'mvn compile' to regenerate the descriptor.")
                .containsAll(annotatedClassNames);
    }

    // ── Infrastructure ──────────────────────────────────────────────

    /**
     * Create an injector that mirrors Maven 4's plugin injector: bind the
     * four standard types as instances.
     */
    private static Injector createPluginInjector() {
        Session session = stub(Session.class);
        Project project = stub(Project.class);
        MojoExecution mojoExecution = stub(MojoExecution.class);
        Log log = new TestLog();

        return Injector.create()
                .bindInstance(Session.class, session)
                .bindInstance(Project.class, project)
                .bindInstance(MojoExecution.class, mojoExecution)
                .bindInstance(Log.class, log);
    }

    /**
     * Parse {@code META-INF/maven/plugin.xml} from the test classpath to
     * extract all mojo implementation class names, then load them.
     */
    @SuppressWarnings("unchecked")
    private static List<Class<? extends Mojo>> discoverMojoClasses() {
        String xml = readPluginDescriptor();
        Pattern pattern = Pattern.compile("<implementation>([^<]+)</implementation>");
        Matcher matcher = pattern.matcher(xml);

        List<Class<? extends Mojo>> classes = new ArrayList<>();
        while (matcher.find()) {
            String className = matcher.group(1).trim();
            try {
                Class<?> cls = Class.forName(className);
                if (Mojo.class.isAssignableFrom(cls)) {
                    classes.add((Class<? extends Mojo>) cls);
                }
            } catch (ClassNotFoundException e) {
                throw new AssertionError(
                        "plugin.xml references " + className
                                + " but it is not on the test classpath", e);
            }
        }
        return classes;
    }

    private static String readPluginDescriptor() {
        // Try Maven 4 descriptor first, fall back to Maven 3 location
        for (String path : List.of(
                "META-INF/maven/plugin.xml",
                "META-INF/maven/org.apache.maven/plugin.xml")) {
            try (InputStream in = MojoDiWiringTest.class.getClassLoader()
                    .getResourceAsStream(path)) {
                if (in != null) {
                    return new String(in.readAllBytes());
                }
            } catch (Exception ignored) {
            }
        }
        throw new AssertionError(
                "META-INF/maven/plugin.xml not found on test classpath. "
                        + "Run 'mvn compile' to generate the plugin descriptor.");
    }

    /**
     * Create a minimal proxy stub for a Maven 4 API interface. Returns
     * safe defaults (null, empty collections, false) — just enough for
     * the injector to satisfy {@code @Inject} fields.
     */
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> iface) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                (proxy, method, args) -> {
                    Class<?> ret = method.getReturnType();
                    if (ret == List.class) return List.of();
                    if (ret == Map.class) return Map.of();
                    if (ret == Optional.class) return Optional.empty();
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    return null;
                });
    }
}

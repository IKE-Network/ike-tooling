package network.ike.tooling.buildreport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.plugin.MojoExecution;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.repository.ArtifactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects structured session events and writes the
 * {@code ike꞉build-report.md} receipt at session end.
 *
 * <p>Phase 1 is report-only: this spy never fails the build and never
 * throws out of {@link #onEvent(Object)} — a collection error degrades
 * to a log line, not a build problem. The receipt is written exactly
 * once, at {@code SessionEnded} (with {@link #close()} as a fallback
 * when a session ends abnormally).</p>
 *
 * <p>Registered through the hand-written sisu index at
 * {@code META-INF/sisu/javax.inject.Named}; see the module POM for the
 * EventSpy-vs-new-API rationale.</p>
 */
@Named("ike-build-report")
@Singleton
public class BuildReportSpy implements EventSpy {

    private static final Logger LOG = LoggerFactory.getLogger(BuildReportSpy.class);

    /** System property that adds a DIAGNOSTIC section listing observed event types. */
    public static final String DEBUG_PROPERTY = "ike.build.report.debug";

    /** Receipt file name at the execution root ({@code ꞉} is U+A789). */
    public static final String RECEIPT_FILE_NAME = "ike꞉build-report.md";

    /** Ledger location relative to the execution root. */
    public static final String LEDGER_RELATIVE_PATH = ".mvn/build-report.yaml";

    private final List<Finding> findings = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> observedEventTypes = Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * Creates the spy; instantiated by the Maven container via the sisu
     * index, never directly.
     */
    public BuildReportSpy() {}
    private final AtomicBoolean receiptWritten = new AtomicBoolean(false);
    private volatile Path executionRoot;

    /**
     * Initializes the spy; no context state is needed.
     *
     * @param context the spy context supplied by Maven
     */
    @Override
    public void init(Context context) {
        // The execution root is captured from SessionStarted instead —
        // the context's working directory may differ under -f.
    }

    /**
     * Routes one build event into the collection model.
     *
     * @param event the event published by Maven or the resolver
     */
    @Override
    public void onEvent(Object event) {
        try {
            if (Boolean.getBoolean(DEBUG_PROPERTY)) {
                observedEventTypes.add(describeEventType(event));
            }
            if (event instanceof ExecutionEvent) {
                onExecutionEvent((ExecutionEvent) event);
            } else if (event instanceof RepositoryEvent) {
                onRepositoryEvent((RepositoryEvent) event);
            }
        } catch (Exception e) {
            LOG.warn("ike-build-report: event collection failed ({}); the receipt may be incomplete",
                    e.toString());
        }
    }

    /**
     * Writes the receipt if the session ended without a
     * {@code SessionEnded} event reaching this spy.
     */
    @Override
    public void close() {
        writeReceipt();
    }

    private void onExecutionEvent(ExecutionEvent event) {
        switch (event.getType()) {
            case ProjectDiscoveryStarted -> resetSession();
            case SessionStarted -> captureExecutionRoot(event);
            case MojoFailed -> findings.add(new Finding(
                    FindingCategory.EXECUTION,
                    Severity.ERROR,
                    "execution/mojo-failed/" + describeMojo(event.getMojoExecution()),
                    describeProject(event) + ": " + describeThrowable(event.getException())));
            case ProjectFailed -> findings.add(new Finding(
                    FindingCategory.EXECUTION,
                    Severity.ERROR,
                    "execution/project-failed/" + describeProject(event),
                    describeThrowable(event.getException())));
            case SessionEnded -> {
                captureExecutionRoot(event);
                writeReceipt();
            }
            default -> {
                // Successes, skips, and forks carry no receipt content in Phase 1.
            }
        }
    }

    private void onRepositoryEvent(RepositoryEvent event) {
        List<Exception> exceptions = event.getExceptions();
        if (exceptions == null || exceptions.isEmpty()) {
            return;
        }
        boolean metadata = event.getMetadata() != null;
        String subject = metadata ? "metadata" : "artifact";
        String repositoryId = describeRepository(event.getRepository());
        for (Exception exception : exceptions) {
            if (isRoutineNegativeLookup(exception)) {
                continue;
            }
            findings.add(new Finding(
                    FindingCategory.REPOSITORY,
                    Severity.WARNING,
                    "repository/" + subject + "-resolve-failed/" + repositoryId,
                    describeSubject(event) + ": " + describeThrowable(exception)));
        }
    }

    /**
     * Distinguishes routine negative lookups (an artifact simply absent
     * from one repository of several) from transfer-level failures such
     * as authentication rejections, which are always report-worthy.
     */
    private static boolean isRoutineNegativeLookup(Exception exception) {
        return exception.getClass().getSimpleName().endsWith("NotFoundException");
    }

    /**
     * Clears all session state at {@code ProjectDiscoveryStarted} —
     * before model building — so a long-lived JVM (the IDE's
     * maven-server) starts every session clean and writes a fresh
     * receipt each time.
     */
    private void resetSession() {
        findings.clear();
        observedEventTypes.clear();
        receiptWritten.set(false);
        executionRoot = null;
        ModelObservations.reset();
    }

    private void captureExecutionRoot(ExecutionEvent event) {
        if (executionRoot != null || event.getSession() == null) {
            return;
        }
        java.io.File rootDirectory = event.getSession().getRequest().getMultiModuleProjectDirectory();
        if (rootDirectory != null) {
            executionRoot = rootDirectory.toPath();
        }
    }

    private void writeReceipt() {
        if (!receiptWritten.compareAndSet(false, true)) {
            return;
        }
        try {
            Path root = executionRoot != null ? executionRoot : Path.of(System.getProperty("user.dir"));
            Ledger ledger;
            String ledgerNote = "";
            try {
                ledger = Ledger.load(root.resolve(LEDGER_RELATIVE_PATH));
            } catch (IOException | IllegalArgumentException e) {
                ledger = Ledger.empty();
                ledgerNote = "ledger " + LEDGER_RELATIVE_PATH + " not used: " + e.getMessage();
            }
            List<Finding> snapshot;
            synchronized (findings) {
                snapshot = new ArrayList<>(findings);
            }
            snapshot.addAll(ModelCrossReference.findings(ModelObservations.snapshot(), root));
            LedgerEvaluation evaluation = ledger.evaluate(snapshot);
            String receipt = ReceiptRenderer.render(
                    toolVersion(), ZonedDateTime.now(), ledger.mode(), ledgerNote, evaluation);
            if (Boolean.getBoolean(DEBUG_PROPERTY)) {
                receipt = receipt + renderDiagnostic();
            }
            Path receiptFile = root.resolve(RECEIPT_FILE_NAME);
            Files.writeString(receiptFile, receipt, StandardCharsets.UTF_8);
            if (evaluation.demandsAttention()) {
                LOG.warn("ike-build-report: {} failure(s), {} attention item(s) — see {}",
                        evaluation.failures().size(), evaluation.attention().size(), receiptFile);
            } else {
                LOG.info("ike-build-report: clean — receipt at {}", receiptFile);
            }
        } catch (Exception e) {
            LOG.warn("ike-build-report: could not write receipt: {}", e.toString());
        }
    }

    private String renderDiagnostic() {
        StringBuilder out = new StringBuilder("\n## DIAGNOSTIC\n\n");
        synchronized (observedEventTypes) {
            for (String type : observedEventTypes) {
                out.append("- ").append(type).append('\n');
            }
        }
        return out.toString();
    }

    private static String describeEventType(Object event) {
        if (event instanceof ExecutionEvent) {
            return "ExecutionEvent." + ((ExecutionEvent) event).getType();
        }
        if (event instanceof RepositoryEvent) {
            return "RepositoryEvent." + ((RepositoryEvent) event).getType();
        }
        return event == null ? "null" : event.getClass().getName();
    }

    private static String describeMojo(MojoExecution mojo) {
        if (mojo == null) {
            return "unknown";
        }
        return mojo.getPlugin().getArtifactId() + ":" + mojo.getGoal();
    }

    private static String describeProject(ExecutionEvent event) {
        return event.getProject() == null ? "unknown" : event.getProject().getArtifactId();
    }

    private static String describeRepository(ArtifactRepository repository) {
        return repository == null ? "unknown" : repository.getId();
    }

    private static String describeSubject(RepositoryEvent event) {
        if (event.getMetadata() != null) {
            return String.valueOf(event.getMetadata());
        }
        if (event.getArtifact() != null) {
            return String.valueOf(event.getArtifact());
        }
        return "unknown";
    }

    private static String describeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "no exception detail";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static String toolVersion() {
        try (InputStream in = BuildReportSpy.class.getResourceAsStream("build-report.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                return properties.getProperty("version", "unknown");
            }
        } catch (IOException e) {
            // fall through to unknown
        }
        return "unknown";
    }
}

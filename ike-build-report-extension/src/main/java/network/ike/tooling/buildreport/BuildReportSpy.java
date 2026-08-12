package network.ike.tooling.buildreport;

import java.util.List;

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
 * Routes structured build events into {@link ReportSession}, which
 * writes the {@code ike꞉build-report.md} receipt at session end.
 *
 * <p>This spy never fails the build and never throws out of
 * {@link #onEvent(Object)} — a collection error degrades to a log
 * line, not a build problem. Gate enforcement, when armed, is
 * {@link GateKeeper}'s job at {@code afterSessionEnd}; receipt
 * writing is idempotent across the two components, whichever runs
 * first.</p>
 *
 * <p>Registered through the hand-written sisu index at
 * {@code META-INF/sisu/javax.inject.Named}; see the module POM for the
 * EventSpy-vs-new-API rationale.</p>
 */
@Named("ike-build-report")
@Singleton
public class BuildReportSpy implements EventSpy {

    private static final Logger LOG = LoggerFactory.getLogger(BuildReportSpy.class);

    /**
     * Creates the spy; instantiated by the Maven container via the sisu
     * index, never directly.
     */
    public BuildReportSpy() {}

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
            if (Boolean.getBoolean(ReportSession.DEBUG_PROPERTY)) {
                ReportSession.addEventType(describeEventType(event));
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
        ReportSession.finalizeAndWrite();
    }

    private void onExecutionEvent(ExecutionEvent event) {
        switch (event.getType()) {
            case ProjectDiscoveryStarted -> ReportSession.reset();
            case SessionStarted -> captureExecutionRoot(event);
            case MojoFailed -> ReportSession.addFinding(new Finding(
                    FindingCategory.EXECUTION,
                    Severity.ERROR,
                    "execution/mojo-failed/" + describeMojo(event.getMojoExecution()),
                    describeProject(event) + ": " + describeThrowable(event.getException())));
            case ProjectFailed -> ReportSession.addFinding(new Finding(
                    FindingCategory.EXECUTION,
                    Severity.ERROR,
                    "execution/project-failed/" + describeProject(event),
                    describeThrowable(event.getException())));
            case SessionEnded -> {
                captureExecutionRoot(event);
                ReportSession.finalizeAndWrite();
            }
            default -> {
                // Successes, skips, and forks carry no receipt content.
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
            ReportSession.addFinding(new Finding(
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

    private void captureExecutionRoot(ExecutionEvent event) {
        if (event.getSession() == null) {
            return;
        }
        java.io.File rootDirectory = event.getSession().getRequest().getMultiModuleProjectDirectory();
        if (rootDirectory != null) {
            ReportSession.captureRoot(rootDirectory.toPath());
        }
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
}

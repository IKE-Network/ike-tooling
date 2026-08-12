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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session-scoped state and finalization shared by the two components
 * that must cooperate at session end: {@link BuildReportSpy} (event
 * collection, {@code SessionEnded}) and {@link GateKeeper}
 * ({@code afterSessionEnd} enforcement).
 *
 * <p>Their relative ordering at the end of a Maven session is a core
 * implementation detail this extension does not depend on:
 * {@link #finalizeAndWrite()} is idempotent, whichever component calls
 * it first performs the write, and both receive the same
 * {@link GateVerdict}. All state is static for the same reason
 * {@link ModelObservations} is — the spy is a classic sisu component
 * and the observer a new-API component, in separate DI containers on
 * one extension classloader. {@link #reset()} runs at
 * {@code ProjectDiscoveryStarted} so long-lived JVMs (the IDE's
 * maven-server) start every session clean.</p>
 */
public final class ReportSession {

    private static final Logger LOG = LoggerFactory.getLogger(ReportSession.class);

    /** System property that adds a DIAGNOSTIC section listing observed event types. */
    public static final String DEBUG_PROPERTY = "ike.build.report.debug";

    /**
     * Escape hatch: skips gate enforcement for one invocation. Using it
     * is recorded in the receipt.
     */
    public static final String GATE_SKIP_PROPERTY = "ike.build.report.gate.skip";

    /** Receipt file name at the execution root ({@code ꞉} is U+A789). */
    public static final String RECEIPT_FILE_NAME = "ike꞉build-report.md";

    /** Ledger location relative to the execution root. */
    public static final String LEDGER_RELATIVE_PATH = ".mvn/build-report.yaml";

    /** Machine-readable observations sidecar, relative to the execution root. */
    public static final String OBSERVATIONS_RELATIVE_PATH = "target/build-report-observations.yaml";

    private static final List<Finding> FINDINGS = Collections.synchronizedList(new ArrayList<>());
    private static final Set<String> OBSERVED_EVENT_TYPES =
            Collections.synchronizedSet(new LinkedHashSet<>());

    private static volatile Path executionRoot;
    private static volatile GateVerdict verdict;

    private ReportSession() {
    }

    /**
     * Clears all session state; runs at {@code ProjectDiscoveryStarted}
     * so a long-lived JVM starts every session clean.
     */
    public static void reset() {
        FINDINGS.clear();
        OBSERVED_EVENT_TYPES.clear();
        executionRoot = null;
        verdict = null;
        ModelObservations.reset();
    }

    /**
     * Records one finding.
     *
     * @param finding the finding to record
     */
    public static void addFinding(Finding finding) {
        FINDINGS.add(finding);
    }

    /**
     * Records an observed event type for the DIAGNOSTIC section.
     *
     * @param eventType a describing label for the event's type
     */
    public static void addEventType(String eventType) {
        OBSERVED_EVENT_TYPES.add(eventType);
    }

    /**
     * Captures the execution root once per session.
     *
     * @param root the session's top directory
     */
    public static void captureRoot(Path root) {
        if (executionRoot == null && root != null) {
            executionRoot = root;
        }
    }

    /**
     * Finalizes the session exactly once: evaluates the ledger, writes
     * the receipt and the observations sidecar, and computes the gate
     * verdict. Safe to call from both the spy and the gate participant;
     * the second caller receives the first caller's verdict.
     *
     * <p>Never throws — a reporting failure degrades to a log line and
     * a permissive verdict.</p>
     *
     * @return the session's gate verdict
     */
    public static synchronized GateVerdict finalizeAndWrite() {
        if (verdict != null) {
            return verdict;
        }
        Path root = executionRoot != null ? executionRoot : Path.of(System.getProperty("user.dir"));
        boolean skipRequested = Boolean.getBoolean(GATE_SKIP_PROPERTY);
        Ledger ledger = Ledger.empty();
        String ledgerNote = "";
        try {
            ledger = Ledger.load(root.resolve(LEDGER_RELATIVE_PATH));
        } catch (IOException | IllegalArgumentException e) {
            ledgerNote = "ledger " + LEDGER_RELATIVE_PATH + " not used: " + e.getMessage();
        }

        List<Finding> snapshot;
        synchronized (FINDINGS) {
            snapshot = new ArrayList<>(FINDINGS);
        }
        snapshot.addAll(ModelCrossReference.findings(ModelObservations.snapshot(), root));

        LedgerEvaluation evaluation = ledger.evaluate(snapshot);
        List<LedgerEvaluation.AttentionItem> gating = ledger.gatingAttention(evaluation);
        boolean buildAlreadyFailed = !evaluation.failures().isEmpty();

        String note = composeNote(ledgerNote, ledger.mode(), skipRequested, gating,
                evaluation.attention().size(), buildAlreadyFailed);
        Path receiptFile = null;
        try {
            String receipt = ReceiptRenderer.render(
                    toolVersion(), ZonedDateTime.now(), ledger.mode(), note, evaluation);
            if (Boolean.getBoolean(DEBUG_PROPERTY)) {
                receipt = receipt + renderDiagnostic();
            }
            receiptFile = root.resolve(RECEIPT_FILE_NAME);
            Files.writeString(receiptFile, receipt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("ike-build-report: could not write receipt: {}", e.toString());
            receiptFile = null;
        }
        try {
            ObservationsFile.write(root.resolve(OBSERVATIONS_RELATIVE_PATH), evaluation, snapshot);
        } catch (Exception e) {
            LOG.warn("ike-build-report: could not write observations sidecar: {}", e.toString());
        }

        if (evaluation.demandsAttention()) {
            LOG.warn("ike-build-report: {} failure(s), {} attention item(s) — see {}",
                    evaluation.failures().size(), evaluation.attention().size(),
                    receiptFile != null ? receiptFile : RECEIPT_FILE_NAME);
        } else {
            LOG.info("ike-build-report: clean — receipt at {}",
                    receiptFile != null ? receiptFile : RECEIPT_FILE_NAME);
        }

        verdict = new GateVerdict(ledger.mode(), gating, buildAlreadyFailed, skipRequested, receiptFile);
        return verdict;
    }

    private static String composeNote(
            String ledgerNote,
            LedgerMode mode,
            boolean skipRequested,
            List<LedgerEvaluation.AttentionItem> gating,
            int totalAttention,
            boolean buildAlreadyFailed) {
        StringBuilder note = new StringBuilder();
        if (!ledgerNote.isBlank()) {
            note.append(ledgerNote);
        }
        if (mode == LedgerMode.GATE) {
            if (note.length() > 0) {
                note.append('\n');
            }
            if (skipRequested) {
                note.append("gate: SKIPPED for this invocation (-D")
                        .append(GATE_SKIP_PROPERTY).append("=true)");
            } else if (buildAlreadyFailed) {
                note.append("gate: build already failed; gate does not double-fail");
            } else if (!gating.isEmpty()) {
                note.append("gate: FAILING the build — ").append(gating.size())
                        .append(" gating attention item(s)");
            } else if (totalAttention > 0) {
                note.append("gate: passing — ").append(totalAttention)
                        .append(" attention item(s) exempted by entry mode: report");
            } else {
                note.append("gate: clean");
            }
        }
        return note.toString();
    }

    private static String renderDiagnostic() {
        StringBuilder out = new StringBuilder("\n## DIAGNOSTIC\n\n");
        synchronized (OBSERVED_EVENT_TYPES) {
            for (String type : OBSERVED_EVENT_TYPES) {
                out.append("- ").append(type).append('\n');
            }
        }
        return out.toString();
    }

    private static String toolVersion() {
        try (InputStream in = ReportSession.class.getResourceAsStream("build-report.properties")) {
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

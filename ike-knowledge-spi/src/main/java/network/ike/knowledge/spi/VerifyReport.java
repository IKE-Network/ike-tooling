package network.ike.knowledge.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * A verification's findings. The report passes when no finding is an
 * {@link Finding.Severity#ERROR}; the goal face fails the build on a non-passing
 * report by normal control flow — never by terminating the JVM. (Deliberately a
 * <em>report</em>, not a {@code *Result}: it is a findings list with a gate, not a
 * value the caller consumes.)
 *
 * @param findings the findings, most severe first by convention
 */
public record VerifyReport(List<Finding> findings) {

    /**
     * Validates and defensively copies the report.
     *
     * @param findings the findings
     * @throws NullPointerException if {@code findings} is null
     */
    public VerifyReport {
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
    }

    /**
     * Whether the verification passed.
     *
     * @return {@code true} when no finding is an error
     */
    public boolean passed() {
        return findings.stream().noneMatch(finding -> finding.severity() == Finding.Severity.ERROR);
    }

    /**
     * Encodes this report in the seam wire format.
     *
     * @return the report properties
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        for (int i = 0; i < findings.size(); i++) {
            Finding finding = findings.get(i);
            PropCodec.put(properties, "finding." + (i + 1) + ".severity", finding.severity().name());
            PropCodec.put(properties, "finding." + (i + 1) + ".check", finding.check().name());
            PropCodec.put(properties, "finding." + (i + 1) + ".component", finding.component());
            PropCodec.put(properties, "finding." + (i + 1) + ".message", finding.message());
        }
        return properties;
    }

    /**
     * Decodes a report from the seam wire format.
     *
     * @param properties the report properties
     * @return the decoded report
     * @throws IllegalArgumentException if present keys are malformed
     */
    public static VerifyReport fromProperties(Properties properties) {
        int count = PropCodec.indexedCount(properties, "finding.", "severity");
        List<Finding> findings = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            findings.add(new Finding(
                    Finding.Severity.valueOf(PropCodec.require(properties, "finding." + i + ".severity")),
                    VerifyRequest.Check.valueOf(PropCodec.require(properties, "finding." + i + ".check")),
                    PropCodec.require(properties, "finding." + i + ".component"),
                    PropCodec.require(properties, "finding." + i + ".message")));
        }
        return new VerifyReport(findings);
    }
}

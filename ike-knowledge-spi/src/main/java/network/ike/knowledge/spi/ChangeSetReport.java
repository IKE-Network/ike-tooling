package network.ike.knowledge.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * What a change set tool reports: whether the change set, or the file written, is as it
 * should be; a one-line summary; and the lines of the report, errors and warnings first for a
 * verification, facts for an inspection.
 *
 * @param ok      false when a verification found an error
 * @param summary one line: the file, its format, its counts
 * @param lines   the report, one fact or finding per line
 */
public record ChangeSetReport(boolean ok, String summary, List<String> lines) {

    public ChangeSetReport {
        Objects.requireNonNull(summary, "summary");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }

    /** The summary and the lines as one text. */
    public String text() {
        StringBuilder text = new StringBuilder(summary);
        for (String line : lines) {
            text.append('\n').append(line);
        }
        return text.toString();
    }

    public Properties toProperties() {
        Properties properties = new Properties();
        PropCodec.put(properties, "ok", Boolean.toString(ok));
        PropCodec.put(properties, "summary", summary);
        for (int i = 0; i < lines.size(); i++) {
            PropCodec.put(properties, "line." + (i + 1), lines.get(i));
        }
        return properties;
    }

    public static ChangeSetReport fromProperties(Properties properties) {
        int count = PropCodec.indexedCount(properties, "line.", "");
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            lines.add(properties.getProperty("line." + i, ""));
        }
        return new ChangeSetReport(PropCodec.getBoolean(properties, "ok", false),
                properties.getProperty("summary", ""), lines);
    }
}

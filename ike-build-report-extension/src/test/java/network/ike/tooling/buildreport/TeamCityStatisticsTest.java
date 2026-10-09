package network.ike.tooling.buildreport;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service messages that hand measures to TeamCity.
 */
class TeamCityStatisticsTest {

    private static Measures measures() {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("warnings.total", 800.0);
        values.put("coverage.line.covered", 12345.0);
        values.put("coverage.line.total", 29960.0);
        values.put("coverage.line.percent", 41.2);
        values.put("bench.rocks scan[1]", 2.5);
        return new Measures(values, Map.of(), List.of(), List.of());
    }

    @Test
    void everyMeasureBecomesAStatisticAndCoverageAlsoTeamCitysOwnKeys() {
        List<String> messages = TeamCityStatistics.messages(measures());

        assertThat(messages).containsExactly(
                "##teamcity[buildStatisticValue key='warnings.total' value='800']",
                "##teamcity[buildStatisticValue key='coverage.line.covered' value='12345']",
                "##teamcity[buildStatisticValue key='CodeCoverageAbsLCovered' value='12345']",
                "##teamcity[buildStatisticValue key='coverage.line.total' value='29960']",
                "##teamcity[buildStatisticValue key='CodeCoverageAbsLTotal' value='29960']",
                "##teamcity[buildStatisticValue key='coverage.line.percent' value='41.2']",
                "##teamcity[buildStatisticValue key='bench.rocks_scan_1_' value='2.5']");
    }

    @Test
    void escapingFollowsTeamCitysRules() {
        assertThat(TeamCityStatistics.escape("a|b'c[d]\r\n")).isEqualTo("a||b|'c|[d|]|r|n");
        assertThat(TeamCityStatistics.key("ok.key-1_x")).isEqualTo("ok.key-1_x");
    }

    @Test
    void publishPrintsOneLinePerMessageAndCountsThem() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        int published = TeamCityStatistics.publish(measures(), out);

        assertThat(published).isEqualTo(7);
        assertThat(bytes.toString(StandardCharsets.UTF_8).lines()).hasSize(7)
                .allSatisfy(line -> assertThat(line).startsWith("##teamcity[buildStatisticValue "));
    }

    @Test
    void valuesRenderPlainly() {
        assertThat(Numbers.plain(415)).isEqualTo("415");
        assertThat(Numbers.plain(38.5)).isEqualTo("38.5");
        assertThat(Numbers.plain(0.1)).isEqualTo("0.1");
        assertThat(Numbers.plain(1.0 / 3)).isEqualTo("0.333333");
        assertThat(Numbers.bytes(512)).isEqualTo("512 B");
        assertThat(Numbers.bytes(1_300_000)).isEqualTo("1.2 MiB");
    }
}

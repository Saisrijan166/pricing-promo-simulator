package com.pricingsim.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pricingsim.domain.enums.Competitor;
import com.pricingsim.domain.enums.CostChangeReason;
import com.pricingsim.domain.enums.ObservationSource;
import com.pricingsim.domain.model.CompetitorObservation;
import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.generator.sink.CsvRowSink;

/** Write → read gives equal records; nulls, decimals, timestamps and quoting survive; LF endings (PLAN.md §12). */
class CsvRoundTripTest {

    @TempDir
    Path tmp;

    private static final CostRecord COST_FIRST = new CostRecord("COST-SKU-000001-2026-01", "SKU-000001", "2026-01",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), new BigDecimal("100.50"), new BigDecimal("2.00"),
            new BigDecimal("102.50"), null, CostChangeReason.INITIAL);
    private static final CostRecord COST_SECOND = new CostRecord("COST-SKU-000001-2026-02", "SKU-000001", "2026-02",
            LocalDate.of(2026, 2, 1), null, new BigDecimal("110.00"), new BigDecimal("0.00"),
            new BigDecimal("110.00"), new BigDecimal("0.0732"), CostChangeReason.SUPPLIER_INCREASE);

    @Test
    void recordsRoundTripWithNullsDecimalsAndTimestamps() {
        Path dir = tmp.resolve("csv");
        CompetitorObservation obs = new CompetitorObservation("OBS-2026-0000001", "SKU-000001", Competitor.COMP_BETA,
                new BigDecimal("1199.00"), Instant.parse("2026-01-01T23:59:59Z"), Instant.parse("2026-01-02T05:00:00Z"),
                ObservationSource.PARTNER_FEED, false, new BigDecimal("0.799"));
        try (CsvRowSink sink = new CsvRowSink(dir)) {
            sink.accept(TableRegistry.COST_RECORD, COST_SECOND); // out of order on purpose
            sink.accept(TableRegistry.COST_RECORD, COST_FIRST);
            sink.accept(TableRegistry.COMPETITOR_OBSERVATION, obs);
        }
        List<CostRecord> costs = CsvSupport.read(TableRegistry.COST_RECORD, dir.resolve("cost_record.csv"));
        assertThat(costs).containsExactly(COST_FIRST, COST_SECOND);
        assertThat(costs.get(1).freightCost().toPlainString()).isEqualTo("0.00");
        assertThat(CsvSupport.<CompetitorObservation>read(TableRegistry.COMPETITOR_OBSERVATION,
                dir.resolve("competitor_observation.csv"))).containsExactly(obs);
    }

    @Test
    void fileFormatIsPlainRfc4180WithLf() throws IOException {
        Path dir = tmp.resolve("csv");
        try (CsvRowSink sink = new CsvRowSink(dir)) {
            sink.accept(TableRegistry.COST_RECORD, COST_FIRST);
        }
        String text = Files.readString(dir.resolve("cost_record.csv"), StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("cost_record_id,sku_id,cost_month,effective_from,effective_to,unit_cost,"
                + "freight_cost,landed_cost,change_pct,change_reason\n"
                + "COST-SKU-000001-2026-01,SKU-000001,2026-01,2026-01-01,2026-02-01,100.50,2.00,102.50,,INITIAL\n");
    }

    @Test
    void quotingAndJsonTextSurvive() {
        String json = "[{\"step\":\"S1\",\"note\":\"a, b\"}]";
        assertThat(CsvSupport.formatLine(new String[] {"x", json, null, "q\"uote"}))
                .isEqualTo("x,\"[{\"\"step\"\":\"\"S1\"\",\"\"note\"\":\"\"a, b\"\"}]\",,\"q\"\"uote\"");
        assertThatThrownBy(() -> CsvSupport.formatValue("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CsvSupport.formatValue(Instant.parse("2026-01-01T00:00:00.5Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void datasetReaderTypesValuesForJdbc() {
        Path root = tmp.resolve("ds");
        try (CsvRowSink sink = new CsvRowSink(root.resolve("csv"))) {
            sink.accept(TableRegistry.COST_RECORD, COST_SECOND);
        }
        List<Object[]> rows = new ArrayList<>();
        long n = new CsvDatasetReader(root).forEachTyped(TableRegistry.COST_RECORD, rows::add);
        assertThat(n).isEqualTo(1);
        Object[] r = rows.get(0);
        assertThat(r[3]).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(r[4]).isNull();
        assertThat(r[7]).isEqualTo(new BigDecimal("110.00"));
        assertThat(r[9]).isEqualTo("SUPPLIER_INCREASE");
        assertThat(CsvDatasetReader.typed(TableSpec.ColumnType.TIMESTAMPTZ, "2026-01-01T06:00:00Z"))
                .isEqualTo(OffsetDateTime.of(2026, 1, 1, 6, 0, 0, 0, ZoneOffset.UTC));
        assertThat(CsvDatasetReader.typed(TableSpec.ColumnType.INTEGER, "7")).isEqualTo(7L);
        assertThat(CsvDatasetReader.typed(TableSpec.ColumnType.BOOLEAN, "true")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void checksumsFileIsSha256sumCompatible() throws IOException {
        Path root = tmp.resolve("sums");
        Files.createDirectories(root.resolve("csv"));
        Files.writeString(root.resolve("csv/a.csv"), "abc\n");
        Map<String, String> sums = ChecksumService.checksums(root, ChecksumService.filesUnder(root.resolve("csv")));
        ChecksumService.writeChecksumsFile(root, sums);
        // echo 'abc' | sha256sum → edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb
        assertThat(Files.readString(root.resolve("checksums.sha256")))
                .isEqualTo("edeaaff3f1774ad2888673770c6d64097e391bc362d7d6fb34982ddf0efd18cb  csv/a.csv\n");
        assertThat(ChecksumService.readChecksumsFile(root.resolve("checksums.sha256"))).isEqualTo(sums);
        assertThat(ChecksumService.fingerprint(sums)).hasSize(64);
    }
}

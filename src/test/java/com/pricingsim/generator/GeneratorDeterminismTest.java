package com.pricingsim.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pricingsim.domain.model.CostRecord;
import com.pricingsim.generator.sink.CsvRowSink;
import com.pricingsim.io.CsvSupport;
import com.pricingsim.io.TableRegistry;

/**
 * Same seed → byte-identical CSV files; a different seed → different files (PLAN.md §11). Starter scale, written
 * through the real CSV sink.
 */
class GeneratorDeterminismTest {

    @TempDir
    Path tmp;

    @Test
    void sameSeedTwiceGivesByteIdenticalFiles() throws IOException {
        Path a = generate("starter", 20261022L, tmp.resolve("a"));
        Path b = generate("starter", 20261022L, tmp.resolve("b"));

        List<String> files = fileNames(a);
        assertThat(files).containsExactlyElementsOf(TableRegistry.loadOrder().stream().map(t -> t.fileName()).sorted()
                .toList()).hasSize(16);
        assertThat(fileNames(b)).isEqualTo(files);
        for (String file : files) {
            assertThat(Files.mismatch(a.resolve(file), b.resolve(file))).as(file).isEqualTo(-1L);
        }
    }

    @Test
    void differentSeedGivesDifferentFiles() throws IOException {
        Path a = generate("tiny", 20261022L, tmp.resolve("a"));
        Path c = generate("tiny", 20261023L, tmp.resolve("c"));
        for (String file : fileNames(a)) {
            assertThat(Files.mismatch(a.resolve(file), c.resolve(file))).as(file).isNotEqualTo(-1L);
        }
    }

    @Test
    void filesFollowTheCsvRules() throws IOException {
        Path dir = generate("tiny", 20261022L, tmp.resolve("rules"));
        for (String file : fileNames(dir)) {
            byte[] bytes = Files.readAllBytes(dir.resolve(file));
            String text = new String(bytes, StandardCharsets.UTF_8);
            assertThat(text).as(file).doesNotContain("\r").endsWith("\n");
            assertThat(bytes[0]).as(file + " has no BOM").isNotEqualTo((byte) 0xEF);
            String table = file.substring(0, file.length() - ".csv".length());
            assertThat(text.lines().findFirst()).as(file).hasValue(CsvSupport.headerLine(TableRegistry.byName(table)));
            // Rows are in primary-key order; PK columns lead every table (1 or 2 columns, never quoted).
            int pkColumns = TableRegistry.byName(table).primaryKey().size();
            List<String> keys = text.lines().skip(1)
                    .map(l -> String.join("\u0000", java.util.Arrays.asList(l.split(",", -1)).subList(0, pkColumns)))
                    .toList();
            assertThat(keys).as(file).isSorted().doesNotHaveDuplicates();
        }
        // The files read back into records, with nulls and decimals intact.
        List<CostRecord> rows = CsvSupport.read(TableRegistry.COST_RECORD, dir.resolve("cost_record.csv"));
        assertThat(rows).isNotEmpty().allSatisfy(c -> {
            assertThat(c.landedCost().scale()).isEqualTo(2);
            assertThat(c.landedCost()).isEqualByComparingTo(c.unitCost().add(c.freightCost()));
        });
        assertThat(rows.get(0).changePct()).isNull();
    }

    @Test
    void spillingToDiskGivesTheSameBytes() throws IOException {
        Path memory = generate("tiny", 20261022L, tmp.resolve("memory"));
        Path spilled = tmp.resolve("spilled");
        try (CsvRowSink sink = new CsvRowSink(spilled, 37)) {
            new DatasetGenerator().generate(TestSettings.forScale("tiny"), sink);
        }
        for (String file : fileNames(memory)) {
            assertThat(Files.mismatch(memory.resolve(file), spilled.resolve(file))).as(file).isEqualTo(-1L);
        }
    }

    private static Path generate(String scale, long seed, Path dir) {
        try (CsvRowSink sink = new CsvRowSink(dir)) {
            new DatasetGenerator().generate(TestSettings.forScale(scale, seed), sink);
        }
        return dir;
    }

    private static List<String> fileNames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}

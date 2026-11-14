package com.pricingsim.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writes {@code manifest.json} and {@code checksums.sha256} for a generated dataset (PLAN.md §11).
 *
 * <p>The checksums cover the generated data files only ({@code csv/} and {@code labels/}), so they are identical for
 * every run with the same seed and settings. The manifest also records how the data was produced (version, command
 * line, output path), which can differ between otherwise identical runs; that is why it is not in the checksums.
 * Each file entry is on one line, which keeps the manifest easy to read with {@code grep} in shell scripts.
 */
public final class ManifestWriter {

    public static final String MANIFEST_FILE = "manifest.json";
    public static final String PROVENANCE = "100% synthetic data generated from a fixed seed; no external, scraped or"
            + " personal data; brands, products and competitors are fictional (see docs/provenance.md).";

    /**
     * What produced the dataset.
     *
     * @param header ordered top-level manifest entries; each value is already JSON text
     */
    public record RunInfo(Map<String, String> header) {
        public RunInfo {
            header = new LinkedHashMap<>(header);
        }
    }

    /** One data file. {@code columns} is null for non-tabular files. */
    public record FileEntry(String path, long rows, Integer columns, String sha256) {
    }

    /** Summary read back from a manifest. */
    public record Summary(long seed, String scale, String fingerprint, Map<String, Long> rowsByPath) {
    }

    private ManifestWriter() {
    }

    /**
     * Checksums every file under {@code root/csv} and {@code root/labels}, writes {@code checksums.sha256} and
     * {@code manifest.json}, and returns the dataset fingerprint.
     *
     * @param rows    row count per relative path (as counted by the writer)
     * @param columns column count per relative path (CSV files)
     */
    public static String write(Path root, RunInfo info, Map<String, Long> rows, Map<String, Integer> columns) {
        List<Path> files = new ArrayList<>(ChecksumService.filesUnder(root.resolve("csv")));
        files.addAll(ChecksumService.filesUnder(root.resolve("labels")));
        Map<String, String> sums = ChecksumService.checksums(root, files);
        ChecksumService.writeChecksumsFile(root, sums);
        String fingerprint = ChecksumService.fingerprint(sums);

        StringBuilder sb = new StringBuilder("{\n");
        for (Map.Entry<String, String> e : info.header().entrySet()) {
            sb.append("  ").append(JsonText.quote(e.getKey())).append(": ").append(e.getValue()).append(",\n");
        }
        sb.append("  \"files\": [\n");
        int i = 0;
        for (Map.Entry<String, String> e : sums.entrySet()) {
            String path = e.getKey();
            Long n = rows.get(path);
            Integer c = columns.get(path);
            sb.append("    {\"path\": ").append(JsonText.quote(path))
                    .append(", \"rows\": ").append(n == null ? "null" : n.toString())
                    .append(", \"columns\": ").append(c == null ? "null" : c.toString())
                    .append(", \"sha256\": ").append(JsonText.quote(e.getValue())).append('}')
                    .append(++i < sums.size() ? ",\n" : "\n");
        }
        sb.append("  ],\n");
        sb.append("  \"dataset_fingerprint\": ").append(JsonText.quote(fingerprint)).append(",\n");
        sb.append("  \"provenance\": ").append(JsonText.quote(PROVENANCE)).append('\n');
        sb.append("}\n");
        try {
            Files.writeString(root.resolve(MANIFEST_FILE), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write manifest", e);
        }
        return fingerprint;
    }

    /** Reads the seed, scale name, fingerprint and per-file rows from {@code root/manifest.json}. */
    public static Summary read(Path root) {
        Path file = root.resolve(MANIFEST_FILE);
        try {
            JsonNode m = new ObjectMapper().readTree(file.toFile());
            Map<String, Long> rows = new LinkedHashMap<>();
            for (JsonNode f : m.get("files")) {
                rows.put(f.get("path").asText(), f.get("rows").isNull() ? null : f.get("rows").asLong());
            }
            return new Summary(m.get("seed").asLong(), m.get("scale").get("name").asText(),
                    m.get("dataset_fingerprint").asText(), rows);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file + " (run generate first)", e);
        }
    }

    /** Version of the running build: jar Implementation-Version, else {@code dev}. */
    public static String version() {
        String v = ManifestWriter.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /** Current git commit from {@code .git} in the working directory, or null when not available. */
    public static String gitCommit() {
        Path git = Path.of(".git");
        try {
            Path head = git.resolve("HEAD");
            if (!Files.isRegularFile(head)) {
                return null;
            }
            String ref = Files.readString(head, StandardCharsets.UTF_8).trim();
            if (!ref.startsWith("ref: ")) {
                return ref;
            }
            String name = ref.substring(5);
            Path loose = git.resolve(name);
            if (Files.isRegularFile(loose)) {
                return Files.readString(loose, StandardCharsets.UTF_8).trim();
            }
            Path packed = git.resolve("packed-refs");
            if (Files.isRegularFile(packed)) {
                for (String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
                    if (line.endsWith(" " + name)) {
                        return line.substring(0, line.indexOf(' '));
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** JSON text for a nullable string. */
    public static String str(String value) {
        return JsonText.quote(value);
    }
}

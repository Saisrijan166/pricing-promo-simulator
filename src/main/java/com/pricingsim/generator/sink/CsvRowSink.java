package com.pricingsim.generator.sink;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.pricingsim.io.CsvSupport;
import com.pricingsim.io.TableSpec;

/**
 * Writes each declared table to {@code <dir>/<table>.csv} per the PLAN.md §11 rules. Rows are formatted as they arrive
 * (a compact line is kept instead of the record) and written in primary-key order on {@link #close()}.
 *
 * <p>Memory stays bounded at full scale (PLAN.md §7.4): when a table buffers {@code spillThreshold} lines they are
 * sorted and spilled to a temporary run file, and {@link #close()} merges the runs (an external merge sort). The output
 * is byte-identical whether or not anything spilled. A duplicate primary key is a generator bug and fails the run.
 */
public final class CsvRowSink implements RowSink {

    /** Lines per table held in memory before a sorted run is spilled to disk. */
    public static final int DEFAULT_SPILL_THRESHOLD = 250_000;

    private record Line(String key, String text) {
    }

    private static final Comparator<Line> BY_KEY = Comparator.comparing(Line::key);

    private final Path directory;
    private final int spillThreshold;
    private final Map<String, TableBuffer> tables = new TreeMap<>();
    private Path spillDirectory;
    private boolean closed;

    public CsvRowSink(Path directory) {
        this(directory, DEFAULT_SPILL_THRESHOLD);
    }

    public CsvRowSink(Path directory, int spillThreshold) {
        this.directory = Objects.requireNonNull(directory, "directory");
        if (spillThreshold < 1) {
            throw new IllegalArgumentException("spillThreshold must be >= 1");
        }
        this.spillThreshold = spillThreshold;
    }

    public Path directory() {
        return directory;
    }

    @Override
    public void declare(TableSpec table) {
        requireOpen();
        tables.computeIfAbsent(table.name(), n -> new TableBuffer(table));
    }

    @Override
    public void accept(TableSpec table, Record row) {
        requireOpen();
        TableBuffer buffer = tables.computeIfAbsent(table.name(), n -> new TableBuffer(table));
        String[] fields = CsvSupport.formatRow(table, row);
        buffer.lines.add(new Line(CsvSupport.primaryKey(table, fields), CsvSupport.formatLine(fields)));
        buffer.count++;
        if (buffer.lines.size() >= spillThreshold) {
            spill(buffer);
        }
    }

    @Override
    public long count(TableSpec table) {
        TableBuffer buffer = tables.get(table.name());
        return buffer == null ? 0 : buffer.count;
    }

    @Override
    public boolean isDeclared(TableSpec table) {
        return tables.containsKey(table.name());
    }

    /** Path of the file a table is written to. */
    public Path fileFor(TableSpec table) {
        return directory.resolve(table.fileName());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            for (TableBuffer buffer : tables.values()) {
                if (buffer.runs.isEmpty()) {
                    buffer.lines.sort(BY_KEY);
                    write(buffer, buffer.lines.iterator());
                } else {
                    spill(buffer);
                    merge(buffer);
                }
                buffer.lines.clear();
            }
        } finally {
            deleteSpillDirectory();
        }
    }

    private void spill(TableBuffer buffer) {
        buffer.lines.sort(BY_KEY);
        try {
            if (spillDirectory == null) {
                spillDirectory = Files.createTempDirectory("pricing-sim-spill-");
            }
            Path run = spillDirectory.resolve(buffer.table.name() + "-" + buffer.runs.size() + ".run");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(run),
                    1 << 16))) {
                for (Line line : buffer.lines) {
                    writeString(out, line.key());
                    writeString(out, line.text());
                }
            }
            buffer.runs.add(run);
            buffer.lines.clear();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot spill " + buffer.table.name(), e);
        }
    }

    /** k-way merge of the sorted runs into the final CSV file. */
    private void merge(TableBuffer buffer) {
        List<RunReader> readers = new ArrayList<>();
        try {
            PriorityQueue<RunReader> queue = new PriorityQueue<>(Comparator.comparing((RunReader r) -> r.current.key())
                    .thenComparingInt(r -> r.index));
            for (int i = 0; i < buffer.runs.size(); i++) {
                RunReader reader = new RunReader(buffer.runs.get(i), i);
                readers.add(reader);
                if (reader.advance()) {
                    queue.add(reader);
                }
            }
            java.util.Iterator<Line> merged = new java.util.Iterator<>() {
                @Override
                public boolean hasNext() {
                    return !queue.isEmpty();
                }

                @Override
                public Line next() {
                    RunReader top = queue.poll();
                    Line line = top.current;
                    if (top.advance()) {
                        queue.add(top);
                    }
                    return line;
                }
            };
            write(buffer, merged);
        } finally {
            for (RunReader reader : readers) {
                reader.close();
            }
        }
    }

    private void write(TableBuffer buffer, java.util.Iterator<Line> lines) {
        Path file = fileFor(buffer.table);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer w = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), 1 << 16)) {
                w.write(CsvSupport.headerLine(buffer.table));
                w.write(CsvSupport.LINE_SEPARATOR);
                String previousKey = null;
                while (lines.hasNext()) {
                    Line line = lines.next();
                    if (line.key().equals(previousKey)) {
                        throw new IllegalStateException(buffer.table.name() + ": duplicate primary key "
                                + line.key().replace('\u0000', ','));
                    }
                    previousKey = line.key();
                    w.write(line.text());
                    w.write(CsvSupport.LINE_SEPARATOR);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    private void deleteSpillDirectory() {
        if (spillDirectory == null) {
            return;
        }
        try (Stream<Path> files = Files.list(spillDirectory)) {
            for (Path f : files.toList()) {
                Files.deleteIfExists(f);
            }
            Files.deleteIfExists(spillDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + spillDirectory, e);
        } finally {
            spillDirectory = null;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("sink is closed");
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static final class RunReader implements AutoCloseable {
        private final DataInputStream in;
        private final int index;
        private Line current;

        RunReader(Path run, int index) {
            try {
                this.in = new DataInputStream(new BufferedInputStream(Files.newInputStream(run), 1 << 16));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            this.index = index;
        }

        boolean advance() {
            try {
                String key = readString();
                if (key == null) {
                    current = null;
                    return false;
                }
                current = new Line(key, readString());
                return true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private String readString() throws IOException {
            int length;
            try {
                length = in.readInt();
            } catch (EOFException eof) {
                return null;
            }
            return new String(in.readNBytes(length), StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            try {
                in.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final class TableBuffer {
        private final TableSpec table;
        private final List<Line> lines = new ArrayList<>();
        private final List<Path> runs = new ArrayList<>();
        private long count;

        TableBuffer(TableSpec table) {
            this.table = table;
        }
    }
}

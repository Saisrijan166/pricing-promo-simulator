package com.pricingsim.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 checksums (PLAN.md §11). {@code checksums.sha256} uses the {@code sha256sum} format
 * ({@code <hex>␠␠<relative path>}, sorted by path), so {@code sha256sum -c checksums.sha256} verifies a dataset with
 * standard tools. The dataset fingerprint is the SHA-256 of the sorted {@code path:sha256} lines.
 */
public final class ChecksumService {

    public static final String CHECKSUMS_FILE = "checksums.sha256";

    private ChecksumService() {
    }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Checksums of the given files, keyed by path relative to {@code root} (forward slashes), sorted. */
    public static Map<String, String> checksums(Path root, List<Path> files) {
        Map<String, String> out = new TreeMap<>();
        for (Path f : files) {
            out.put(relative(root, f), sha256(f));
        }
        return out;
    }

    /** SHA-256 over the sorted {@code path:sha256} lines. */
    public static String fingerprint(Map<String, String> checksums) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(checksums).forEach((path, sha) -> sb.append(path).append(':').append(sha).append('\n'));
        return sha256(sb.toString());
    }

    /** Writes {@code checksums.sha256} into {@code root}. */
    public static Path writeChecksumsFile(Path root, Map<String, String> checksums) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(checksums).forEach((path, sha) -> sb.append(sha).append("  ").append(path).append('\n'));
        Path file = root.resolve(CHECKSUMS_FILE);
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
        return file;
    }

    /** Reads a {@code checksums.sha256} file back into path → sha256. */
    public static Map<String, String> readChecksumsFile(Path file) {
        Map<String, String> out = new TreeMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                int sep = line.indexOf("  ");
                out.put(line.substring(sep + 2), line.substring(0, sep));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        return out;
    }

    public static String relative(Path root, Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
    }

    /** Regular files under {@code dir} (recursively), sorted; empty if the directory does not exist. */
    public static List<Path> filesUnder(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.walk(dir)) {
            List<Path> files = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
            files.sort(null);
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
    }
}

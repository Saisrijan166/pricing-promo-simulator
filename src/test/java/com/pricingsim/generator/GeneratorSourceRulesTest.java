package com.pricingsim.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Determinism rules for generator and pricing sources (PLAN.md §7.1): no clock, no {@code Math.random}, no non-strict
 * {@code Math}, and no hash-ordered collections whose iteration order could leak into the output.
 */
class GeneratorSourceRulesTest {

    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/java/com/pricingsim/generator"),
            Path.of("src/main/java/com/pricingsim/pricing"));

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("\\b(Instant|LocalDate|LocalDateTime|ZonedDateTime|OffsetDateTime|Clock)\\.now\\b"),
            Pattern.compile("\\bSystem\\.currentTimeMillis\\b"),
            Pattern.compile("(?<!Strict)\\bMath\\.\\w"),
            Pattern.compile("\\bnew\\s+(HashMap|HashSet|ConcurrentHashMap)\\b"),
            Pattern.compile("\\b(Set|Map)\\.of\\("),
            Pattern.compile("\\bCollectors\\.(toSet|toMap|groupingBy)\\("),
            Pattern.compile("\\bThreadLocalRandom\\b|\\bSplittableRandom\\b|\\bnew\\s+SecureRandom\\b"));

    @Test
    void generatorAndPricingAvoidNonDeterministicApis() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String code = stripComment(lines.get(i));
                        for (Pattern p : FORBIDDEN) {
                            if (p.matcher(code).find()) {
                                violations.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
                            }
                        }
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    /** Drops line comments and Javadoc/block-comment lines, which may mention the forbidden APIs. */
    private static String stripComment(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
            return "";
        }
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}

package com.pricingsim.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import org.springframework.stereotype.Component;

import com.pricingsim.config.GeneratorProperties;
import com.pricingsim.io.JsonExporter;
import com.pricingsim.io.SqlExporter;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code export}: CSV → {@code json/} and/or {@code sql/} (PLAN.md §11). No database needed. */
@Component
@Command(name = "export", mixinStandardHelpOptions = true,
        description = "Export the generated CSV dataset as JSON (JSON Lines above 100k rows) and/or SQL.")
public class ExportCommand implements Callable<Integer> {

    @Mixin
    ScaleOptions scaleOptions = new ScaleOptions();

    @Option(names = "--formats", split = ",", paramLabel = "<json|sql>", defaultValue = "json,sql",
            description = "Formats to write (default: json,sql).")
    List<String> formats;

    @Spec
    CommandSpec spec;

    private final GeneratorProperties properties;

    public ExportCommand(GeneratorProperties properties) {
        this.properties = properties;
    }

    @Override
    public Integer call() {
        export(scaleOptions.outputDirectory(properties), formats, spec.commandLine().getOut());
        return 0;
    }

    static void export(Path dir, List<String> formats, PrintWriter out) {
        for (String f : formats) {
            switch (f.trim().toLowerCase(Locale.ROOT)) {
                case "json" -> {
                    JsonExporter.export(dir);
                    out.printf("Wrote %s%n", dir.resolve(JsonExporter.DIR));
                }
                case "sql" -> {
                    SqlExporter.export(dir);
                    out.printf("Wrote %s (schema.sql, seed.sql)%n", dir.resolve(SqlExporter.DIR));
                }
                default -> throw new IllegalArgumentException("Unknown format '" + f + "' (use json and/or sql)");
            }
        }
        out.flush();
    }
}

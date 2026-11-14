package com.pricingsim.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code pipeline_run} rows. A run ID is {@code <COMMAND>-<first 16 hex of the dataset fingerprint>}, so it is
 * reproducible and re-running a command on the same dataset replaces its earlier rows instead of piling them up. Times
 * here are wall-clock audit data in the database only; they never reach a checksummed file.
 */
public final class RunDao {

    public enum Command {
        LOAD, VALIDATE, DETECT, EVALUATE, RUN_ALL
    }

    private final JdbcTemplate jdbc;

    public RunDao(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public static String runId(Command command, String fingerprint) {
        return command.name() + "-" + fingerprint.substring(0, 16).toLowerCase(Locale.ROOT);
    }

    /** Deletes any earlier run with this ID (and its results), then records the run as RUNNING. */
    public String start(Command command, long seed, String scale, String fingerprint, String inputPath) {
        String runId = runId(command, fingerprint);
        jdbc.update("DELETE FROM detected_exception WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM validation_result WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM pipeline_run WHERE run_id = ?", runId);
        jdbc.update("INSERT INTO pipeline_run (run_id, command, seed, scale, dataset_fingerprint, input_path, status,"
                        + " started_at) VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?)",
                runId, command.name(), seed, scale, fingerprint, inputPath, now());
        return runId;
    }

    public void finish(String runId, boolean succeeded, String summaryJson) {
        jdbc.update("UPDATE pipeline_run SET status = ?, finished_at = ?, summary = ? WHERE run_id = ?",
                succeeded ? "SUCCEEDED" : "FAILED", now(), summaryJson, runId);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }
}

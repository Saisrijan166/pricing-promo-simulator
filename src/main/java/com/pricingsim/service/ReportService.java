package com.pricingsim.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricingsim.evaluation.EvaluationReportWriter;
import com.pricingsim.persistence.RunDao;
import com.pricingsim.validation.ValidationSummaryWriter;

/**
 * The latest validation summary and evaluation metrics: the files written by the most recent VALIDATE / EVALUATE run
 * (its {@code pipeline_run.input_path}). The evaluation report holds detection scores only, never label rows.
 */
@Service
@Profile("!offline")
public class ReportService {

    private final RunDao runs;
    private final ObjectMapper mapper;

    public ReportService(RunDao runs, ObjectMapper mapper) {
        this.runs = runs;
        this.mapper = mapper;
    }

    public JsonNode validationSummary() {
        return read(RunDao.Command.VALIDATE, ValidationSummaryWriter.JSON_FILE);
    }

    public JsonNode evaluation() {
        return read(RunDao.Command.EVALUATE, EvaluationReportWriter.FILE);
    }

    private JsonNode read(RunDao.Command command, String file) {
        Map<String, Object> run = runs.latest(command)
                .orElseThrow(() -> new NotFoundException("No " + command + " run yet (run: pricing-sim "
                        + command.name().toLowerCase(java.util.Locale.ROOT) + ")"));
        Path path = Path.of((String) run.get("input_path")).resolve(file);
        if (!Files.isRegularFile(path)) {
            throw new NotFoundException("Report file not found: " + path);
        }
        try {
            return mapper.readTree(path.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}

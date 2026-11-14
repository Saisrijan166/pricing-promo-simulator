package com.pricingsim.api;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.pricingsim.service.ReportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Profile("!offline")
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/validation-summary")
    @Operation(summary = "validation_summary.json of the latest validate run")
    public JsonNode validationSummary() {
        return reports.validationSummary();
    }

    @GetMapping("/evaluation")
    @Operation(summary = "evaluation_metrics.json of the latest evaluate run")
    public JsonNode evaluation() {
        return reports.evaluation();
    }
}

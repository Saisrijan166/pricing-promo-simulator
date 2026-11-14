package com.pricingsim.api;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pricingsim.api.dto.DecisionExplanationResponse;
import com.pricingsim.service.DecisionExplainService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;

@RestController
@Profile("!offline")
@RequestMapping("/api/v1/decisions")
@Tag(name = "Decisions")
public class DecisionController {

    private static final String DECISION_ID = "^DEC-\\d{4}-\\d{6}$";

    private final DecisionExplainService service;

    public DecisionController(DecisionExplainService service) {
        this.service = service;
    }

    @GetMapping("/{decisionId}")
    @Operation(summary = "The stored decision record (inputs, outcome, reasons, explanation)")
    public Map<String, Object> decision(@PathVariable @Pattern(regexp = DECISION_ID) String decisionId) {
        return service.decision(decisionId);
    }

    @GetMapping("/{decisionId}/explain")
    @Operation(summary = "Full traceability chain of a decision",
            description = "Inputs, cost record, observations (USED / EXCLUDED with age), markdowns (APPLIED / REJECTED"
                    + " with reason and rules), explanation steps S1-S12, the price-history rows it produced, status"
                    + " events, and detector findings linked to it (never ground-truth labels).")
    public DecisionExplanationResponse explain(@PathVariable @Pattern(regexp = DECISION_ID) String decisionId) {
        return service.explain(decisionId);
    }
}

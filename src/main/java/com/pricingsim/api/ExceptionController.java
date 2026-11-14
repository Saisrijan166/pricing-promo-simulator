package com.pricingsim.api;

import java.time.LocalDate;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pricingsim.api.dto.ExceptionView;
import com.pricingsim.api.dto.PageResponse;
import com.pricingsim.domain.enums.ExceptionCategory;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.service.ExceptionQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@RestController
@Profile("!offline")
@RequestMapping("/api/v1/exceptions")
@Tag(name = "Exceptions")
public class ExceptionController {

    private final ExceptionQueryService service;

    public ExceptionController(ExceptionQueryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Detected exceptions, filtered and paged (sorted by event_date, then exception_id)")
    public PageResponse<ExceptionView> list(
            @RequestParam(required = false) ExceptionCategory category,
            @RequestParam(required = false) IssueType issueType,
            @RequestParam(required = false) @Pattern(regexp = "^SKU-\\d{6}$") String skuId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return service.query(category, issueType, skuId, from, to, page, size);
    }
}

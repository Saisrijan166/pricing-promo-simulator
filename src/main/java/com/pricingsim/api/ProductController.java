package com.pricingsim.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pricingsim.api.dto.PriceHistoryView;
import com.pricingsim.service.DecisionExplainService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;

@RestController
@Profile("!offline")
@RequestMapping("/api/v1/products")
@Tag(name = "Products")
public class ProductController {

    private final DecisionExplainService service;

    public ProductController(DecisionExplainService service) {
        this.service = service;
    }

    @GetMapping("/{skuId}/price-history")
    @Operation(summary = "Daily list and shelf price with the source decision and change reason")
    public List<PriceHistoryView> priceHistory(
            @PathVariable @Pattern(regexp = "^SKU-\\d{6}$") String skuId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.priceHistory(skuId, from, to);
    }
}

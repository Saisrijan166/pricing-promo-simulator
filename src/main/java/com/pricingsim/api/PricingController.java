package com.pricingsim.api;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pricingsim.api.dto.SimulatePriceRequest;
import com.pricingsim.api.dto.SimulatePriceResponse;
import com.pricingsim.service.SimulationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@Profile("!offline")
@RequestMapping("/api/v1/pricing")
@Tag(name = "Pricing")
public class PricingController {

    private final SimulationService simulation;

    public PricingController(SimulationService simulation) {
        this.simulation = simulation;
    }

    @PostMapping("/simulate")
    @Operation(summary = "Run the pricing engine for a SKU on a date (read-only, nothing is persisted)",
            description = "Inputs come from the loaded data as of 06:00Z on asOfDate; overrides replace landed cost,"
                    + " stock, competitor prices or the markdowns considered. Without overrides, a stored decision's"
                    + " date reproduces that decision.")
    public SimulatePriceResponse simulate(@Valid @RequestBody SimulatePriceRequest request) {
        return simulation.simulate(request);
    }
}

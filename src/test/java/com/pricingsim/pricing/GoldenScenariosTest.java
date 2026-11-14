package com.pricingsim.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricingsim.domain.enums.Competitor;
import com.pricingsim.domain.enums.DecisionTrigger;
import com.pricingsim.domain.enums.PricePosition;

/**
 * 20 hand-computed scenarios from {@code golden/pricing_scenarios.json} (PLAN.md §12). Each scenario's {@code notes}
 * holds the arithmetic; the expected values were worked out by hand from PLAN.md §8, not copied from engine output.
 */
class GoldenScenariosTest {

    private static final PricingEngine ENGINE = new PricingEngine(PricingPolicy.defaults());

    static Stream<Arguments> scenarios() throws IOException {
        try (InputStream in = GoldenScenariosTest.class.getResourceAsStream("/golden/pricing_scenarios.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            List<Arguments> out = new ArrayList<>();
            for (JsonNode s : root.get("scenarios")) {
                out.add(Arguments.of(Named.of(s.get("name").asText(), s)));
            }
            assertThat(out).hasSize(20);
            return out.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void scenario(JsonNode scenario) {
        PricingResult r = ENGINE.price(context(scenario.get("input")));
        JsonNode e = scenario.get("expected");
        String why = scenario.get("notes").asText();

        assertThat(r.floorPrice()).as("floor: " + why).isEqualByComparingTo(e.get("floor").asText());
        assertThat(r.ceilingPrice()).as("ceiling").isEqualByComparingTo(e.get("ceiling").asText());
        if (e.get("competitorRef").isNull()) {
            assertThat(r.competitor().referencePrice()).as("competitor ref").isNull();
        } else {
            assertThat(r.competitor().referencePrice()).as("competitor ref")
                    .isEqualByComparingTo(e.get("competitorRef").asText());
        }
        assertThat(r.competitor().freshCount()).as("fresh count").isEqualTo(e.get("freshCount").asInt());
        assertThat(r.competitor().staleCount()).as("stale count").isEqualTo(e.get("staleCount").asInt());
        assertThat(r.finalListPrice()).as("final list price: " + why).isEqualByComparingTo(e.get("finalListPrice").asText());
        assertThat(r.expectedNetPrice()).as("net: " + why).isEqualByComparingTo(e.get("expectedNetPrice").asText());
        assertThat(r.markdownDiscountPct()).as("markdown pct").isEqualByComparingTo(e.get("markdownDiscountPct").asText());
        assertThat(r.marginPct()).as("margin: " + why).isEqualByComparingTo(e.get("marginPct").asText());
        assertThat(r.requiredMarginPct()).as("required margin").isEqualByComparingTo(e.get("requiredMarginPct").asText());
        assertThat(r.decisionType().name()).as("decision type").isEqualTo(e.get("decisionType").asText());
        assertThat(r.status().name()).as("status").isEqualTo(e.get("status").asText());
        assertThat(r.primaryReasonCode().name()).as("primary reason").isEqualTo(e.get("primaryReason").asText());
        List<String> codes = new ArrayList<>();
        e.get("reasonCodes").forEach(c -> codes.add(c.asText()));
        assertThat(r.reasonCodes()).extracting(Enum::name).as("reason codes").containsExactlyElementsOf(codes);
        Map<String, String> promos = new LinkedHashMap<>();
        e.get("promotions").properties().forEach(f -> promos.put(f.getKey(), f.getValue().asText()));
        Map<String, String> actual = new LinkedHashMap<>();
        r.promotions().forEach(p -> actual.put(p.promotionId(), p.reason().name()));
        assertThat(actual).as("promotions").isEqualTo(promos);
    }

    private static PricingContext context(JsonNode in) {
        boolean initial = "INITIAL".equals(in.get("trigger").asText());
        Contexts c = initial ? Contexts.initial() : Contexts.weekly();
        c.trigger(DecisionTrigger.valueOf(in.get("trigger").asText()))
                .mrp(in.get("mrp").asText())
                .minMargin(in.get("minMargin").asText())
                .minPromoMargin(in.get("minPromoMargin").asText())
                .position(PricePosition.valueOf(in.get("position").asText()))
                .landed(in.get("landed").asText())
                .prev(in.get("prev").isNull() ? null : in.get("prev").asText())
                .onHand(in.get("onHand").asInt());
        JsonNode s = in.get("sales");
        c.sales(s.get(0).asInt(), s.get(1).asInt(), s.get(2).asInt(), s.get(3).asInt());
        if (!in.get("map").isNull()) {
            c.map(in.get("map").asText());
        }
        if (!in.get("manual").isNull()) {
            c.manual(in.get("manual").asText());
        }
        for (JsonNode o : in.get("observations")) {
            Instant captured = Contexts.TS.minusSeconds(o.get("ageHours").asLong() * 3600);
            Instant ingested = o.has("ingestedHoursAfterDecision")
                    ? Contexts.TS.plusSeconds(o.get("ingestedHoursAfterDecision").asLong() * 3600)
                    : (captured.plusSeconds(3600).isAfter(Contexts.TS) ? captured : captured.plusSeconds(3600));
            c.observation(Competitor.valueOf(o.get("competitor").asText()), o.get("price").asText(), captured, ingested,
                    o.get("confidence").asText());
        }
        for (JsonNode p : in.get("promotions")) {
            String id = p.get("id").asText();
            String value = p.get("value").asText();
            Offers o = switch (p.get("type").asText()) {
                case "MARKDOWN_PCT" -> Offers.markdownPct(id, value);
                case "MARKDOWN_AMOUNT" -> Offers.markdownAmount(id, value);
                case "COUPON_PCT" -> Offers.couponPct(id, value);
                default -> Offers.couponAmount(id, value);
            };
            o.priority(p.get("priority").asInt());
            if (p.get("exclusive").asBoolean()) {
                o.exclusive();
            }
            if (!p.get("excludes").isNull()) {
                o.excludes(p.get("excludes").asText());
            }
            if (!p.get("maxStack").isNull()) {
                o.maxStack(p.get("maxStack").asInt());
            }
            if (!p.get("maxCombined").isNull()) {
                o.maxCombined(p.get("maxCombined").asText());
            }
            if (!p.get("minQty").isNull()) {
                o.minQty(p.get("minQty").asInt());
            }
            c.promotions(o.build());
        }
        assertThat(new BigDecimal(in.get("landed").asText())).isPositive();
        return c.build();
    }
}

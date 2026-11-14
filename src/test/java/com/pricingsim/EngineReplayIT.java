package com.pricingsim;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pricingsim.generator.DatasetWriter;
import com.pricingsim.generator.TestSettings;
import com.pricingsim.persistence.BulkLoader;
import com.pricingsim.persistence.PricingInputDao;
import com.pricingsim.persistence.Rows;
import com.pricingsim.pricing.PricingEngine;
import com.pricingsim.pricing.PricingPolicy;
import com.pricingsim.pricing.PricingResult;

/**
 * Every price is explainable and reproducible (PLAN.md §8, §12): 100 stored decisions of a clean starter dataset,
 * rebuilt from the database by {@link PricingInputDao} and re-run through {@link PricingEngine}, give the identical
 * final price, reasons, net price, margin and explanation.
 */
class EngineReplayIT {

    @TempDir
    Path tmp;

    @Test
    void storedDecisionsReplayIdentically() {
        Path dir = tmp.resolve("starter");
        DatasetWriter.write(TestSettings.clean("starter"), dir);
        DataSource ds = TestDatabases.fresh("replay");
        new BulkLoader(ds).load(dir);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        PricingInputDao dao = new PricingInputDao(jdbc);
        PricingEngine engine = new PricingEngine(PricingPolicy.defaults());

        List<Map<String, Object>> all = Rows.normalize(jdbc.queryForList("SELECT * FROM price_decision ORDER BY decision_id"));
        assertThat(all.size()).isGreaterThan(1000);
        int step = all.size() / 100;
        int replayed = 0;
        for (int i = 0; i < all.size() && replayed < 100; i += step) {
            Map<String, Object> d = all.get(i);
            PricingResult r = engine.price(dao.load((String) d.get("sku_id"), (LocalDate) d.get("effective_date"),
                    PricingInputDao.Overrides.NONE));
            String id = (String) d.get("decision_id");
            assertThat(r.finalListPrice()).as(id).isEqualByComparingTo((BigDecimal) d.get("final_list_price"));
            assertThat(r.primaryReasonCode().name()).as(id).isEqualTo(d.get("primary_reason_code"));
            assertThat(r.reasonCodesJoined()).as(id).isEqualTo(d.get("reason_codes"));
            assertThat(r.expectedNetPrice()).as(id).isEqualByComparingTo((BigDecimal) d.get("expected_net_price"));
            assertThat(r.marginPct()).as(id).isEqualByComparingTo((BigDecimal) d.get("margin_pct"));
            assertThat(r.floorPrice()).as(id).isEqualByComparingTo((BigDecimal) d.get("floor_price"));
            assertThat(r.decisionSource().name()).as(id).isEqualTo(d.get("decision_source"));
            assertThat(r.explanationJson()).as(id).isEqualTo(d.get("explanation"));
            replayed++;
        }
        assertThat(replayed).isEqualTo(100);
    }
}

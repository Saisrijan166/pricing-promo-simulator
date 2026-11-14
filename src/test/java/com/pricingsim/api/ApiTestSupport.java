package com.pricingsim.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.pricingsim.detection.ExceptionDetector;
import com.pricingsim.evaluation.EvaluationRunner;
import com.pricingsim.generator.DatasetWriter;
import com.pricingsim.generator.TestSettings;
import com.pricingsim.io.ManifestWriter;
import com.pricingsim.persistence.BulkLoader;
import com.pricingsim.pricing.PricingPolicy;
import com.pricingsim.validation.ValidationRunner;
import com.pricingsim.validation.ValidationSummaryWriter;

/**
 * Shared setup for the MockMvc ITs: one Spring context on its own H2 database, loaded once with the tiny dataset (1
 * anomaly per type) and processed by validate, detect and evaluate, so every endpoint has data.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class ApiTestSupport {

    private static Path dataset;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected DataSource dataSource;

    protected JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void loadOnce() {
        synchronized (ApiTestSupport.class) {
            if (dataset != null) {
                return;
            }
            try {
                Path dir = Files.createTempDirectory("pricing-sim-api-");
                DatasetWriter.write(TestSettings.forScale("tiny"), dir);
                new BulkLoader(dataSource).load(dir);
                ManifestWriter.Summary manifest = ManifestWriter.read(dir);
                ValidationSummaryWriter.write(dir, new ValidationRunner(dataSource, PricingPolicy.defaults())
                        .run(manifest, dir.toString()));
                new ExceptionDetector(dataSource, PricingPolicy.defaults()).run(manifest, dir);
                new EvaluationRunner(dataSource).run(dir);
                dataset = dir;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}

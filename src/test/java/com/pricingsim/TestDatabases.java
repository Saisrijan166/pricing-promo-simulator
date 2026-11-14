package com.pricingsim;

import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A fresh in-memory H2 database (PostgreSQL mode) migrated with the real Flyway scripts, without a Spring context.
 * Each call gets its own database, so tests that alter the schema cannot affect each other.
 */
public final class TestDatabases {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private TestDatabases() {
    }

    public static DataSource fresh(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + name + "-" + SEQ.incrementAndGet()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return ds;
    }
}

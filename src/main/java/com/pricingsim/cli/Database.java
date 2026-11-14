package com.pricingsim.cli;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;

/** Database access for commands that need it; DB-free commands run under the offline profile without a DataSource. */
final class Database {

    private Database() {
    }

    static DataSource require(ObjectProvider<DataSource> provider) {
        DataSource ds = provider.getIfAvailable();
        if (ds == null) {
            throw new IllegalStateException("This command needs the database. Start it with: docker compose up -d");
        }
        return ds;
    }
}

package com.cau.capstone8.backend.book;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Optional demo data has no Flyway history, so disabling demo never hides migrations. */
@Component
@Profile("demo")
public class DemoCatalogInitializer implements ApplicationRunner {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    public DemoCatalogInitializer(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }
    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        jdbc.execute("select pg_advisory_xact_lock(80320260912)");
        new ResourceDatabasePopulator(new ClassPathResource("db/demo/catalog.sql")).execute(dataSource);
    }
}

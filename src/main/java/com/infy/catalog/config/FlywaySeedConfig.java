package com.infy.catalog.config;

import org.flywaydb.core.api.Location;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Demo data is a separate Flyway location (db/seed), added only when catalog.seed-data=true.
 * "*:missing" lets an environment that once ran the seed start later without it.
 */
@Configuration
public class FlywaySeedConfig {

    @Bean
    FlywayConfigurationCustomizer seedDataLocation(CatalogProperties properties) {
        return configuration -> {
            if (properties.seedData()) {
                List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
                locations.add(new Location("classpath:db/seed"));
                configuration.locations(locations.toArray(Location[]::new));
            }
            configuration.ignoreMigrationPatterns("*:missing");
        };
    }
}

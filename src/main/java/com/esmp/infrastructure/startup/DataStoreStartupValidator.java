package com.esmp.infrastructure.startup;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

@Component
public class DataStoreStartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataStoreStartupValidator.class);

    private final List<HealthIndicator> healthIndicators;

    public DataStoreStartupValidator(List<HealthIndicator> healthIndicators) {
        this.healthIndicators = healthIndicators;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (HealthIndicator indicator : healthIndicators) {
            String name = indicator.getClass().getSimpleName();
            log.info("Startup validation: checking {}...", name);
            Health health = indicator.health();
            if (Status.DOWN.equals(health.getStatus())) {
                String details = health.getDetails().toString();
                log.error("Startup validation: {} is DOWN — {}", name, details);
                throw new IllegalStateException(
                        "Data store startup validation failed: " + name + " is DOWN — " + details);
            }
            log.info("Startup validation: {} is UP", name);
        }
        log.info("All data store connections verified — startup validation passed");
    }
}

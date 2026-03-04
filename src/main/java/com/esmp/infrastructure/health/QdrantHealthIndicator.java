package com.esmp.infrastructure.health;

import io.qdrant.client.QdrantClient;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class QdrantHealthIndicator implements HealthIndicator {

  private final QdrantClient qdrantClient;

  public QdrantHealthIndicator(QdrantClient qdrantClient) {
    this.qdrantClient = qdrantClient;
  }

  @Override
  public Health health() {
    try {
      qdrantClient.healthCheckAsync().get(3, TimeUnit.SECONDS);
      return Health.up().withDetail("qdrant", "reachable").build();
    } catch (Exception e) {
      return Health.down().withDetail("qdrant", "unreachable").withException(e).build();
    }
  }
}

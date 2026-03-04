package com.esmp.infrastructure.startup;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

class DataStoreStartupValidatorTest {

  @Test
  void whenAllIndicatorsReturnUp_runCompletesWithoutException() throws Exception {
    HealthIndicator indicator1 = () -> Health.up().build();
    HealthIndicator indicator2 = () -> Health.up().withDetail("db", "connected").build();

    DataStoreStartupValidator validator =
        new DataStoreStartupValidator(List.of(indicator1, indicator2));

    ApplicationArguments args = mock(ApplicationArguments.class);
    // Should not throw
    validator.run(args);
  }

  @Test
  void whenOneIndicatorReturnsDown_runThrowsIllegalStateException() {
    HealthIndicator upIndicator = () -> Health.up().build();
    HealthIndicator downIndicator =
        () -> Health.down().withDetail("error", "connection refused").build();

    DataStoreStartupValidator validator =
        new DataStoreStartupValidator(List.of(upIndicator, downIndicator));

    ApplicationArguments args = mock(ApplicationArguments.class);

    assertThatThrownBy(() -> validator.run(args)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void whenIndicatorIsDown_exceptionMessageContainsComponentName() {
    // Use a named inner class so getSimpleName returns a meaningful identifier
    HealthIndicator downIndicator = new NamedDownIndicator();

    DataStoreStartupValidator validator = new DataStoreStartupValidator(List.of(downIndicator));

    ApplicationArguments args = mock(ApplicationArguments.class);

    assertThatThrownBy(() -> validator.run(args))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NamedDownIndicator");
  }

  static class NamedDownIndicator implements HealthIndicator {
    @Override
    public Health health() {
      return Health.down().withDetail("error", "unreachable").build();
    }
  }
}

package com.esmp.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.esmp.scheduling.api.ModuleSchedule;
import com.esmp.scheduling.api.ScheduleResponse;
import com.esmp.scheduling.application.SchedulingService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link SchedulingService} covering SCHED-01 and SCHED-02 requirements.
 *
 * <p>Creates synthetic Neo4j data with three modules (alpha, beta, gamma) and cross-module
 * DEPENDS_ON edges to exercise topological wave ordering, composite scoring, and cycle detection.
 *
 * <p>Uses the static {@code setUpDone} guard to create data only once before the first test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class SchedulingServiceIntegrationTest {

  @Container
  static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:2026.01.4").withoutAuthentication();

  @Container
  static MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("esmp")
          .withUsername("esmp")
          .withPassword("esmp-test");

  @Container
  static GenericContainer<?> qdrant =
      new GenericContainer<>("qdrant/qdrant:latest")
          .withExposedPorts(6333, 6334)
          .waitingFor(Wait.forHttp("/healthz").forPort(6333));

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
    registry.add("spring.neo4j.authentication.username", () -> "neo4j");
    registry.add("spring.neo4j.authentication.password", neo4j::getAdminPassword);
    registry.add("spring.datasource.url", mysql::getJdbcUrl);
    registry.add("spring.datasource.username", mysql::getUsername);
    registry.add("spring.datasource.password", mysql::getPassword);
    registry.add("qdrant.host", qdrant::getHost);
    registry.add("qdrant.port", () -> qdrant.getMappedPort(6334));
  }

  @Autowired
  private SchedulingService schedulingService;

  @Autowired
  private Neo4jClient neo4jClient;

  /** Guards one-time setup so it only runs before the first test method. */
  private static boolean setUpDone = false;

  @BeforeEach
  void setUpOnce() {
    if (setUpDone) return;
    setUpDone = true;
    createSyntheticData();
  }

  // ---------------------------------------------------------------------------
  // Synthetic data setup
  // ---------------------------------------------------------------------------

  private void createSyntheticData() {
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    // Module "alpha" — 5 classes, low risk
    createClass("com.esmp.alpha.AlphaA", 0.1, 2);
    createClass("com.esmp.alpha.AlphaB", 0.2, 3);
    createClass("com.esmp.alpha.AlphaC", 0.15, 2);
    createClass("com.esmp.alpha.AlphaD", 0.25, 5);
    createClass("com.esmp.alpha.AlphaE", 0.3, 4);

    // Module "beta" — 5 classes, high risk
    createClass("com.esmp.beta.BetaA", 0.5, 10);
    createClass("com.esmp.beta.BetaB", 0.6, 15);
    createClass("com.esmp.beta.BetaC", 0.7, 12);
    createClass("com.esmp.beta.BetaD", 0.75, 18);
    createClass("com.esmp.beta.BetaE", 0.8, 20);

    // Module "gamma" — 3 classes, moderate risk
    createClass("com.esmp.gamma.GammaA", 0.2, 3);
    createClass("com.esmp.gamma.GammaB", 0.35, 5);
    createClass("com.esmp.gamma.GammaC", 0.4, 6);

    // DEPENDS_ON: beta -> alpha (beta depends on alpha), gamma -> alpha (gamma depends on alpha)
    // This means alpha has 2 dependents; alpha should be in wave 1, beta+gamma in wave 2
    createDependsOn("com.esmp.beta.BetaA", "com.esmp.alpha.AlphaA");
    createDependsOn("com.esmp.gamma.GammaA", "com.esmp.alpha.AlphaA");
  }

  private void createClass(String fqn, double enhancedRiskScore, int complexitySum) {
    String[] parts = fqn.split("\\.");
    String packageName = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 1));
    // Module = 3rd segment (index 2) of the package name: com.esmp.<module>
    String module = parts.length > 2 ? parts[2] : "";
    neo4jClient.query("""
        MERGE (c:JavaClass {fullyQualifiedName: $fqn})
        SET c.packageName = $pkg, c.module = $module, c.enhancedRiskScore = $risk, c.complexitySum = $cc, c.complexityMax = $cc
        """)
        .bind(fqn).to("fqn")
        .bind(packageName).to("pkg")
        .bind(module).to("module")
        .bind(enhancedRiskScore).to("risk")
        .bind(complexitySum).to("cc")
        .run();
  }

  private void createDependsOn(String sourceFqn, String targetFqn) {
    neo4jClient.query("""
        MATCH (a:JavaClass {fullyQualifiedName: $src})
        MATCH (b:JavaClass {fullyQualifiedName: $tgt})
        MERGE (a)-[:DEPENDS_ON]->(b)
        """)
        .bind(sourceFqn).to("src")
        .bind(targetFqn).to("tgt")
        .run();
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("testRecommendReturnsOrderedModules: flatRanking is non-empty and sorted by wave then score")
  void testRecommendReturnsOrderedModules() {
    ScheduleResponse response = schedulingService.recommend("");

    assertThat(response.waves()).isNotEmpty();
    assertThat(response.flatRanking()).isNotEmpty();

    // Verify sorting: waveNumber ASC then finalScore ASC
    List<ModuleSchedule> flat = response.flatRanking();
    for (int i = 1; i < flat.size(); i++) {
      ModuleSchedule prev = flat.get(i - 1);
      ModuleSchedule curr = flat.get(i);
      if (prev.waveNumber() == curr.waveNumber()) {
        assertThat(prev.finalScore())
            .as("Within same wave, scores should be ascending at index %d", i)
            .isLessThanOrEqualTo(curr.finalScore());
      } else {
        assertThat(prev.waveNumber())
            .as("Wave numbers should be ascending at index %d", i)
            .isLessThan(curr.waveNumber());
      }
    }
  }

  @Test
  @DisplayName("testRationaleContainsAllFields: each rationale mentions risk, dependents, commits, CC")
  void testRationaleContainsAllFields() {
    ScheduleResponse response = schedulingService.recommend("");

    for (ModuleSchedule ms : response.flatRanking()) {
      assertThat(ms.rationale())
          .as("rationale for module '%s' should mention risk", ms.module())
          .containsIgnoringCase("risk");
      assertThat(ms.rationale())
          .as("rationale for module '%s' should mention dependents", ms.module())
          .containsIgnoringCase("dependent");
      assertThat(ms.rationale())
          .as("rationale for module '%s' should mention commits", ms.module())
          .containsIgnoringCase("commit");
      assertThat(ms.rationale())
          .as("rationale for module '%s' should mention CC", ms.module())
          .containsIgnoringCase("CC");
    }
  }

  @Test
  @DisplayName("testTopologicalWaveOrdering: alpha in wave 1, beta and gamma in wave 2")
  void testTopologicalWaveOrdering() {
    ScheduleResponse response = schedulingService.recommend("");
    List<ModuleSchedule> flat = response.flatRanking();

    ModuleSchedule alpha = flat.stream()
        .filter(m -> "alpha".equals(m.module()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Module 'alpha' not found in scheduling response"));

    ModuleSchedule beta = flat.stream()
        .filter(m -> "beta".equals(m.module()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Module 'beta' not found in scheduling response"));

    ModuleSchedule gamma = flat.stream()
        .filter(m -> "gamma".equals(m.module()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Module 'gamma' not found in scheduling response"));

    assertThat(alpha.waveNumber())
        .as("alpha has no dependencies — must be in wave 1")
        .isEqualTo(1);

    assertThat(beta.waveNumber())
        .as("beta depends on alpha — must be in a later wave than alpha")
        .isGreaterThan(alpha.waveNumber());

    assertThat(gamma.waveNumber())
        .as("gamma depends on alpha — must be in a later wave than alpha")
        .isGreaterThan(alpha.waveNumber());
  }

  @Test
  @DisplayName("testCircularDependencyFallback: cycle modules get final wave, no NPE")
  void testCircularDependencyFallback() {
    // Create a circular dependency: alpha -> beta (already beta -> alpha from setup)
    createDependsOn("com.esmp.alpha.AlphaA", "com.esmp.beta.BetaA");

    try {
      ScheduleResponse response = schedulingService.recommend("");

      assertThat(response.flatRanking()).isNotEmpty();

      // All modules must have a wave assignment (no NPE, no missing modules)
      assertThat(response.flatRanking().stream().map(ModuleSchedule::module))
          .contains("alpha", "beta", "gamma");

      ModuleSchedule alpha = findModule(response, "alpha");
      ModuleSchedule beta = findModule(response, "beta");
      ModuleSchedule gamma = findModule(response, "gamma");

      // alpha and beta are in a cycle — they should be in the same (final) wave
      assertThat(alpha.waveNumber())
          .as("alpha is in a cycle with beta — should be in the final (cycle) wave")
          .isEqualTo(beta.waveNumber());

      // gamma depends on alpha which is in cycle, so gamma gets cycle wave or later
      assertThat(gamma.waveNumber())
          .as("gamma depends on a cycled module — should not be before the cycle wave")
          .isGreaterThanOrEqualTo(alpha.waveNumber());

    } finally {
      // Restore clean state for subsequent tests
      setUpDone = false;
    }
  }

  @Test
  @DisplayName("testScoreIncorporatesAllDimensions: all contributions >= 0 and sum equals finalScore")
  void testScoreIncorporatesAllDimensions() {
    ScheduleResponse response = schedulingService.recommend("");

    for (ModuleSchedule ms : response.flatRanking()) {
      assertThat(ms.riskContribution())
          .as("riskContribution for '%s' must be >= 0", ms.module())
          .isGreaterThanOrEqualTo(0.0);
      assertThat(ms.dependencyContribution())
          .as("dependencyContribution for '%s' must be >= 0", ms.module())
          .isGreaterThanOrEqualTo(0.0);
      assertThat(ms.frequencyContribution())
          .as("frequencyContribution for '%s' must be >= 0", ms.module())
          .isGreaterThanOrEqualTo(0.0);
      assertThat(ms.complexityContribution())
          .as("complexityContribution for '%s' must be >= 0", ms.module())
          .isGreaterThanOrEqualTo(0.0);

      double expectedFinalScore = ms.riskContribution() + ms.dependencyContribution()
          + ms.frequencyContribution() + ms.complexityContribution();
      assertThat(ms.finalScore())
          .as("finalScore for '%s' must equal sum of 4 contributions", ms.module())
          .isCloseTo(expectedFinalScore, within(0.001));
    }
  }

  @Test
  @DisplayName("testEmptyGraphReturnsEmptyResponse: clear graph, recommend returns empty lists")
  void testEmptyGraphReturnsEmptyResponse() {
    neo4jClient.query("MATCH (n) DETACH DELETE n").run();

    try {
      ScheduleResponse response = schedulingService.recommend("");

      assertThat(response.flatRanking())
          .as("flatRanking should be empty when graph is empty")
          .isEmpty();
      assertThat(response.waves())
          .as("waves should be empty when graph is empty")
          .isEmpty();
    } finally {
      // Restore data for any subsequent tests
      setUpDone = false;
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ModuleSchedule findModule(ScheduleResponse response, String moduleName) {
    return response.flatRanking().stream()
        .filter(m -> moduleName.equals(m.module()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Module '" + moduleName + "' not found"));
  }
}

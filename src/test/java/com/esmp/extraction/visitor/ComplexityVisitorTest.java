package com.esmp.extraction.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.esmp.extraction.parser.ClasspathLoader;
import com.esmp.extraction.parser.JavaSourceParser;
import com.esmp.extraction.visitor.ExtractionAccumulator.ClassWriteData;
import com.esmp.extraction.visitor.ExtractionAccumulator.MethodComplexityData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openrewrite.SourceFile;

/**
 * Unit tests for {@link ComplexityVisitor}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Cyclomatic complexity counting per method (baseline=1, increments for branches)
 *   <li>Class-level complexitySum and complexityMax aggregation
 *   <li>DB write detection via @Modifying, persist/merge/remove calls, and write SQL in @Query
 * </ul>
 */
class ComplexityVisitorTest {

  // ---------------------------------------------------------------------------
  // CC: baseline - method with no branches has CC=1
  // ---------------------------------------------------------------------------

  @Test
  void methodWithNoBranchesHasCC1() throws Exception {
    String source = """
        package com.example;
        public class Simple {
          public void doNothing() {
            System.out.println("hello");
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    MethodComplexityData data = findMethod(acc, "doNothing");
    assertThat(data).as("Expected complexity data for doNothing").isNotNull();
    assertThat(data.cyclomaticComplexity())
        .as("Method with no branches should have CC=1")
        .isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // CC: one if statement adds 1 to baseline
  // ---------------------------------------------------------------------------

  @Test
  void methodWithOneIfHasCC2() throws Exception {
    String source = """
        package com.example;
        public class Simple {
          public String check(int x) {
            if (x > 0) {
              return "positive";
            }
            return "non-positive";
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    MethodComplexityData data = findMethod(acc, "check");
    assertThat(data).as("Expected complexity data for check").isNotNull();
    assertThat(data.cyclomaticComplexity())
        .as("Method with one if should have CC=2")
        .isEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // CC: multiple branch types
  // ---------------------------------------------------------------------------

  @Test
  void methodWithMultipleBranchTypesHasCorrectCC() throws Exception {
    // Branches: if (1) + else-if is a nested J.If (1) + for (1) + while (1) = 4 branches + baseline
    // Expected CC = 5
    String source = """
        package com.example;
        import java.util.List;
        public class Complex {
          public int compute(List<String> items, int mode) {
            int result = 0;
            if (mode == 1) {
              result = 1;
            } else if (mode == 2) {
              result = 2;
            }
            for (int i = 0; i < 3; i++) {
              result += i;
            }
            int j = 0;
            while (j < result) {
              j++;
            }
            return result;
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    MethodComplexityData data = findMethod(acc, "compute");
    assertThat(data).as("Expected complexity data for compute").isNotNull();
    // if(1) + else-if nested J.If(1) + for(1) + while(1) + baseline(1) = 5
    assertThat(data.cyclomaticComplexity())
        .as("Method with if/else-if/for/while should have CC=5")
        .isEqualTo(5);
  }

  // ---------------------------------------------------------------------------
  // CC: ternary operator increments
  // ---------------------------------------------------------------------------

  @Test
  void ternaryOperatorIncrementsCC() throws Exception {
    String source = """
        package com.example;
        public class TernaryTest {
          public String label(boolean flag) {
            return flag ? "yes" : "no";
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    MethodComplexityData data = findMethod(acc, "label");
    assertThat(data).as("Expected complexity data for label").isNotNull();
    assertThat(data.cyclomaticComplexity())
        .as("Method with ternary should have CC=2 (baseline + ternary)")
        .isEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // CC: switch case
  // ---------------------------------------------------------------------------

  @Test
  void switchCasesIncrementCC() throws Exception {
    // switch with 3 explicit cases (no default counted) + baseline = 4
    String source = """
        package com.example;
        public class SwitchTest {
          public int grade(String level) {
            switch (level) {
              case "A": return 4;
              case "B": return 3;
              case "C": return 2;
              default: return 0;
            }
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    MethodComplexityData data = findMethod(acc, "grade");
    assertThat(data).as("Expected complexity data for grade").isNotNull();
    // 3 non-default cases + baseline = 4
    assertThat(data.cyclomaticComplexity())
        .as("Switch with 3 non-default cases + baseline should have CC=4")
        .isEqualTo(4);
  }

  // ---------------------------------------------------------------------------
  // CC: catch block increments
  // ---------------------------------------------------------------------------

  @Test
  void catchBlockIncrementsCC() throws Exception {
    String source = """
        package com.example;
        public class TryCatch {
          public void load(String path) {
            try {
              doLoad(path);
            } catch (RuntimeException e) {
              throw e;
            }
          }
          private void doLoad(String path) {}
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    MethodComplexityData data = findMethod(acc, "load");
    assertThat(data).as("Expected complexity data for load").isNotNull();
    // catch(1) + baseline(1) = 2
    assertThat(data.cyclomaticComplexity())
        .as("Method with one catch should have CC=2 (baseline + catch)")
        .isEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // CC: for-each loop increments
  // ---------------------------------------------------------------------------

  @Test
  void forEachLoopIncrementsCC() throws Exception {
    String source = """
        package com.example;
        import java.util.List;
        public class ForEachTest {
          public int sum(List<Integer> nums) {
            int total = 0;
            for (int n : nums) {
              total += n;
            }
            return total;
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    MethodComplexityData data = findMethod(acc, "sum");
    assertThat(data).as("Expected complexity data for sum").isNotNull();
    assertThat(data.cyclomaticComplexity())
        .as("Method with for-each should have CC=2 (baseline + for-each)")
        .isEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // CC: do-while increments
  // ---------------------------------------------------------------------------

  @Test
  void doWhileLoopIncrementsCC() throws Exception {
    String source = """
        package com.example;
        public class DoWhileTest {
          public int countdown(int n) {
            do {
              n--;
            } while (n > 0);
            return n;
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    MethodComplexityData data = findMethod(acc, "countdown");
    assertThat(data).as("Expected complexity data for countdown").isNotNull();
    assertThat(data.cyclomaticComplexity())
        .as("Method with do-while should have CC=2 (baseline + do-while)")
        .isEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // CC: class-level aggregates (complexitySum and complexityMax)
  // ---------------------------------------------------------------------------

  @Test
  void classAggregatesAreComputedCorrectly() throws Exception {
    // Method A: no branches => CC=1
    // Method B: 1 if => CC=2
    // Method C: 2 ifs => CC=3
    // Sum = 6, Max = 3
    String source = """
        package com.example;
        public class MultiMethod {
          public void methodA() {
            System.out.println("a");
          }
          public String methodB(boolean flag) {
            if (flag) return "yes";
            return "no";
          }
          public int methodC(int x, int y) {
            if (x > 0) {
              if (y > 0) return x + y;
            }
            return 0;
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    int sum = acc.getMethodComplexities().values().stream()
        .filter(m -> m.declaringClassFqn().equals("com.example.MultiMethod"))
        .mapToInt(MethodComplexityData::cyclomaticComplexity)
        .sum();
    int max = acc.getMethodComplexities().values().stream()
        .filter(m -> m.declaringClassFqn().equals("com.example.MultiMethod"))
        .mapToInt(MethodComplexityData::cyclomaticComplexity)
        .max()
        .orElse(0);

    assertThat(sum).as("complexitySum for MultiMethod").isEqualTo(6);
    assertThat(max).as("complexityMax for MultiMethod").isEqualTo(3);
  }

  // ---------------------------------------------------------------------------
  // DB writes: @Modifying annotation detection
  // ---------------------------------------------------------------------------

  @Test
  void methodWithModifyingAnnotationIsDetectedAsDbWrite() throws Exception {
    String source = """
        package com.example;
        import org.springframework.data.jpa.repository.Modifying;
        import org.springframework.data.jpa.repository.Query;
        public interface OrderRepository {
          @Modifying
          @Query("UPDATE Order o SET o.status = :status WHERE o.id = :id")
          int updateStatus(Long id, String status);
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    ClassWriteData writeData = acc.getClassWriteData().get("com.example.OrderRepository");
    assertThat(writeData).as("Expected write data for OrderRepository").isNotNull();
    assertThat(writeData.writeCount())
        .as("@Modifying method should be counted as DB write")
        .isGreaterThanOrEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // DB writes: persist/merge/remove call detection
  // ---------------------------------------------------------------------------

  @Test
  void methodCallingPersistIsDetectedAsDbWrite() throws Exception {
    String source = """
        package com.example;
        import javax.persistence.EntityManager;
        public class EntityManagerService {
          private EntityManager em;
          public void save(Object entity) {
            em.persist(entity);
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    ClassWriteData writeData = acc.getClassWriteData().get("com.example.EntityManagerService");
    assertThat(writeData).as("Expected write data for EntityManagerService").isNotNull();
    assertThat(writeData.writeCount())
        .as("Method calling em.persist() should be counted as DB write")
        .isGreaterThanOrEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // DB writes: @Query with DELETE/UPDATE keyword
  // ---------------------------------------------------------------------------

  @Test
  void queryAnnotationWithDeleteKeywordIsDetectedAsDbWrite() throws Exception {
    String source = """
        package com.example;
        import org.springframework.data.jpa.repository.Query;
        public interface ProductRepo {
          @Query("DELETE FROM Product p WHERE p.expired = true")
          void deleteExpired();
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    ClassWriteData writeData = acc.getClassWriteData().get("com.example.ProductRepo");
    assertThat(writeData).as("Expected write data for ProductRepo").isNotNull();
    assertThat(writeData.writeCount())
        .as("@Query with DELETE keyword should be counted as DB write")
        .isGreaterThanOrEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // DB writes: class with no writes
  // ---------------------------------------------------------------------------

  @Test
  void classWithNoDbWritesHasNoWriteData() throws Exception {
    String source = """
        package com.example;
        public class ReadOnlyService {
          public String getValue(String key) {
            return "value:" + key;
          }
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    ClassWriteData writeData = acc.getClassWriteData().get("com.example.ReadOnlyService");
    assertThat(writeData).as("Class with no DB writes should have no write data").isNull();
  }

  // ---------------------------------------------------------------------------
  // DB writes: multiple write methods produce correct count
  // ---------------------------------------------------------------------------

  @Test
  void multipleDbWriteMethodsProduceCorrectCount() throws Exception {
    String source = """
        package com.example;
        import org.springframework.data.jpa.repository.Modifying;
        import org.springframework.data.jpa.repository.Query;
        public interface BulkRepo {
          @Modifying
          @Query("UPDATE Item i SET i.status = 'ACTIVE'")
          int activateAll();

          @Modifying
          @Query("DELETE FROM Item i WHERE i.status = 'DELETED'")
          int purgeDeleted();

          @Modifying
          @Query("UPDATE Item i SET i.status = 'ARCHIVED' WHERE i.id = :id")
          int archiveOne(Long id);
        }
        """;

    ExtractionAccumulator acc = runVisitor(source);

    ClassWriteData writeData = acc.getClassWriteData().get("com.example.BulkRepo");
    assertThat(writeData).as("Expected write data for BulkRepo").isNotNull();
    // 3 @Modifying methods — each counted once
    assertThat(writeData.writeCount())
        .as("Three @Modifying methods should produce writeCount >= 3")
        .isGreaterThanOrEqualTo(3);
  }

  // ---------------------------------------------------------------------------
  // No state leaking between files
  // ---------------------------------------------------------------------------

  @Test
  void noStateLeakingBetweenSourceFiles() throws Exception {
    String fileA = """
        package com.example.a;
        public class FileA {
          public void onlyMethod() {}
        }
        """;
    String fileB = """
        package com.example.b;
        public class FileB {
          public void onlyMethod() {}
        }
        """;

    ExtractionAccumulator acc = runVisitor(fileA, fileB);

    // Both methods should exist independently and both have CC=1
    long aCount = acc.getMethodComplexities().values().stream()
        .filter(m -> m.declaringClassFqn().startsWith("com.example.a"))
        .count();
    long bCount = acc.getMethodComplexities().values().stream()
        .filter(m -> m.declaringClassFqn().startsWith("com.example.b"))
        .count();

    assertThat(aCount).as("FileA should have 1 method complexity entry").isEqualTo(1);
    assertThat(bCount).as("FileB should have 1 method complexity entry").isEqualTo(1);

    // Verify no cross-contamination: all CCs are exactly 1
    acc.getMethodComplexities().values().forEach(m ->
        assertThat(m.cyclomaticComplexity())
            .as("Method " + m.methodId() + " should have CC=1 (no branches)")
            .isEqualTo(1));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ExtractionAccumulator runVisitor(String... sources) throws Exception {
    ExtractionAccumulator acc = new ExtractionAccumulator();
    ComplexityVisitor visitor = new ComplexityVisitor();
    for (SourceFile source : parseJava(sources)) {
      visitor.visit(source, acc);
    }
    return acc;
  }

  private MethodComplexityData findMethod(ExtractionAccumulator acc, String simpleMethodName) {
    return acc.getMethodComplexities().values().stream()
        .filter(m -> m.methodId().contains("#" + simpleMethodName + "("))
        .findFirst()
        .orElse(null);
  }

  private List<SourceFile> parseJava(String... sources) throws IOException {
    Path tempDir = Files.createTempDirectory("complexity-visitor-test");
    List<Path> sourcePaths = new ArrayList<>();

    for (int i = 0; i < sources.length; i++) {
      Path srcFile = tempDir.resolve("Source" + i + ".java");
      Files.writeString(srcFile, sources[i]);
      sourcePaths.add(srcFile);
    }

    for (Path p : sourcePaths) {
      p.toFile().deleteOnExit();
    }
    tempDir.toFile().deleteOnExit();

    ClasspathLoader loader = new ClasspathLoader();
    JavaSourceParser parser = new JavaSourceParser(loader);
    String classpathFile = buildFullClasspathFile();
    return parser.parse(sourcePaths, tempDir, classpathFile);
  }

  private String buildFullClasspathFile() throws IOException {
    String javaClassPath = System.getProperty("java.class.path", "");
    String[] entries = javaClassPath.split(java.io.File.pathSeparator);
    StringBuilder sb = new StringBuilder();
    for (String entry : entries) {
      if (entry.endsWith(".jar") && !entry.isBlank()) {
        sb.append(entry).append("\n");
      }
    }
    if (sb.isEmpty()) {
      return "";
    }
    Path tempFile = Files.createTempFile("full-classpath", ".txt");
    tempFile.toFile().deleteOnExit();
    Files.writeString(tempFile, sb.toString().trim());
    return tempFile.toString();
  }
}

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

/**
 * Extracts text from legacy AdSuite documentation (PDF/DOC/DOCX) and structures
 * it into JSON section files for the DocumentIngestionService.
 *
 * Usage: gradle run (configured in build.gradle.kts)
 */
public class ExtractDocs {

    private static final Tika tika = new Tika();
    static {
        // Increase max string length from 100K default to 10MB
        tika.setMaxStringLength(10 * 1024 * 1024);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ExtractDocs <docs-dir> <output-dir>");
            System.exit(1);
        }

        Path docsDir = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);
        Files.createDirectories(outputDir);

        List<Path> docFiles;
        try (var stream = Files.list(docsDir)) {
            docFiles = stream
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return (name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx"))
                        && !name.startsWith("._");
                })
                .sorted()
                .toList();
        }

        System.out.println("Found " + docFiles.size() + " documents to process");

        for (Path docFile : docFiles) {
            System.out.println("\nProcessing: " + docFile.getFileName());
            try {
                String text = tika.parseToString(docFile);
                System.out.println("  Extracted " + text.length() + " chars");

                List<Section> sections = splitIntoSections(text);
                System.out.println("  Found " + sections.size() + " sections");

                String jsonFileName = docFile.getFileName().toString()
                    .replaceAll("\\.(pdf|doc|docx)$", ".json");
                Path outputFile = outputDir.resolve(jsonFileName);
                writeJson(outputFile, docFile.getFileName().toString(), sections);
                System.out.println("  Wrote: " + outputFile.getFileName());
            } catch (Exception e) {
                System.err.println("  ERROR: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("\nDone.");
    }

    /**
     * Splits extracted text into sections based on heading patterns.
     * German docs typically use numbered sections (1., 1.1, 2.3.1) or
     * ALL-CAPS headings.
     */
    static List<Section> splitIntoSections(String text) {
        List<Section> sections = new ArrayList<>();

        // Pattern for numbered sections like "1 Title", "1.1 Title", "2.3.1 Title"
        Pattern sectionPattern = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)*)\\s+([A-ZÄÖÜa-zäöüß][^\\n]{3,80})$",
            Pattern.MULTILINE
        );

        String[] lines = text.split("\n");
        String currentHeading = "PREAMBLE";
        StringBuilder currentBody = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                currentBody.append("\n");
                continue;
            }

            Matcher numMatch = sectionPattern.matcher(trimmed);

            if (numMatch.matches()) {
                // Save previous section
                String body = currentBody.toString().trim();
                if (!body.isEmpty() && body.length() > 30) {
                    sections.add(new Section(currentHeading, body));
                }
                currentHeading = numMatch.group(1) + " " + numMatch.group(2).trim();
                currentBody = new StringBuilder();
            } else {
                currentBody.append(trimmed).append("\n");
            }
        }

        // Last section
        String body = currentBody.toString().trim();
        if (!body.isEmpty() && body.length() > 30) {
            sections.add(new Section(currentHeading, body));
        }

        return sections;
    }

    static void writeJson(Path outputFile, String sourceDoc, List<Section> sections)
            throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"sourceDocument\": \"").append(escapeJson(sourceDoc)).append("\",\n");
        json.append("  \"sectionCount\": ").append(sections.size()).append(",\n");
        json.append("  \"totalChars\": ").append(
            sections.stream().mapToInt(s -> s.body.length()).sum()).append(",\n");
        json.append("  \"sections\": [\n");

        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            json.append("    {\n");
            json.append("      \"heading\": \"").append(escapeJson(s.heading)).append("\",\n");
            json.append("      \"charCount\": ").append(s.body.length()).append(",\n");
            // Truncate very long sections to 3000 chars to keep output manageable
            String bodyTrunc = s.body.length() > 3000
                ? s.body.substring(0, 3000) + "... [TRUNCATED]"
                : s.body;
            json.append("      \"body\": \"").append(escapeJson(bodyTrunc)).append("\"\n");
            json.append("    }");
            if (i < sections.size() - 1) json.append(",");
            json.append("\n");
        }

        json.append("  ]\n");
        json.append("}\n");
        Files.writeString(outputFile, json.toString());
    }

    static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    record Section(String heading, String body) {}
}

///usr/bin/env java --source 21 "$0" "$@"; exit $?
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Standalone Java 21 script to extract text from legacy AdSuite documentation.
 * Uses Apache Tika CLI to extract plain text, then structures it into sections.
 *
 * Usage: java ExtractDocs.java <tika-app.jar> <docs-dir> <output-dir>
 *
 * Output: One JSON file per document with section headings and body text.
 */
public class ExtractDocs {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java ExtractDocs.java <tika-app.jar> <docs-dir> <output-dir>");
            System.exit(1);
        }

        Path tikaJar = Path.of(args[0]);
        Path docsDir = Path.of(args[1]);
        Path outputDir = Path.of(args[2]);
        Files.createDirectories(outputDir);

        if (!Files.exists(tikaJar)) {
            System.err.println("Tika JAR not found: " + tikaJar);
            System.exit(1);
        }

        List<Path> docFiles;
        try (var stream = Files.list(docsDir)) {
            docFiles = stream
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx");
                })
                .sorted()
                .toList();
        }

        System.out.println("Found " + docFiles.size() + " documents to process");

        for (Path docFile : docFiles) {
            System.out.println("\nProcessing: " + docFile.getFileName());
            try {
                String text = extractWithTika(tikaJar, docFile);
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
            }
        }

        System.out.println("\nDone.");
    }

    static String extractWithTika(Path tikaJar, Path docFile) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "java", "-jar", tikaJar.toString(),
            "--text", docFile.toString()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        String text;
        try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            text = sb.toString();
        }

        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Tika exited with code " + exitCode);
        }
        return text;
    }

    /**
     * Splits extracted text into sections based on heading patterns.
     * German docs typically use numbered sections (1., 1.1, 2.3.1) or
     * ALL-CAPS headings.
     */
    static List<Section> splitIntoSections(String text) {
        List<Section> sections = new ArrayList<>();

        // Pattern for numbered sections like "1.", "1.1", "2.3.1", "1.1.1.1"
        // followed by a title on the same line
        Pattern sectionPattern = Pattern.compile(
            "(?m)^\\s*(\\d+(?:\\.\\d+)*)\\s+([A-ZÄÖÜ][^\\n]{3,80})$"
        );

        // Also detect ALL-CAPS lines as headings (common in older docs)
        Pattern capsHeading = Pattern.compile(
            "(?m)^\\s*([A-ZÄÖÜ][A-ZÄÖÜ\\s]{5,60})\\s*$"
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

            Matcher numMatch = sectionPattern.matcher(line);
            Matcher capsMatch = capsHeading.matcher(line);

            boolean isHeading = false;
            String newHeading = null;

            if (numMatch.matches()) {
                newHeading = numMatch.group(1) + " " + numMatch.group(2).trim();
                isHeading = true;
            } else if (capsMatch.matches() && trimmed.length() > 5) {
                // Only treat as heading if it's mostly uppercase
                long upperCount = trimmed.chars().filter(Character::isUpperCase).count();
                if (upperCount > trimmed.length() * 0.6) {
                    newHeading = trimmed;
                    isHeading = true;
                }
            }

            if (isHeading && newHeading != null) {
                // Save previous section
                String body = currentBody.toString().trim();
                if (!body.isEmpty() && body.length() > 30) {
                    sections.add(new Section(currentHeading, body));
                }
                currentHeading = newHeading;
                currentBody = new StringBuilder();
            } else {
                currentBody.append(trimmed).append("\n");
            }
        }

        // Don't forget the last section
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
        json.append("  \"sections\": [\n");

        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            json.append("    {\n");
            json.append("      \"heading\": \"").append(escapeJson(s.heading)).append("\",\n");
            // Truncate very long sections to 2000 chars
            String bodyTrunc = s.body.length() > 2000
                ? s.body.substring(0, 2000) + "..."
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

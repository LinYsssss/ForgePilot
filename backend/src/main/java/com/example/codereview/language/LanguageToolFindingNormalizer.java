package com.example.codereview.language;

import com.example.codereview.finding.FindingCandidate;
import com.example.codereview.language.java.JavaFindingNormalizer;
import com.example.codereview.language.javascript.JavascriptFindingNormalizer;
import com.example.codereview.language.python.PythonFindingNormalizer;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LanguageToolFindingNormalizer {

    private final JavaFindingNormalizer java = new JavaFindingNormalizer();
    private final PythonFindingNormalizer python = new PythonFindingNormalizer();
    private final JavascriptFindingNormalizer javascript = new JavascriptFindingNormalizer();

    public List<FindingCandidate> normalize(String commandId, String output, String sourceVersion) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        return switch (commandId) {
            case "java.pmd" -> java.parseSarif(output, sourceVersion);
            case "java.spotbugs" -> java.parseSpotBugsXml(output, sourceVersion);
            case "java.checkstyle" -> java.parseCheckstyleXml(output, sourceVersion);
            case "python.ruff" -> python.parseRuffJson(output, sourceVersion);
            case "python.bandit" -> python.parseBanditJson(output, sourceVersion);
            case "javascript.eslint" -> javascript.parseEslintJson(output, sourceVersion);
            case "javascript.semgrep" -> javascript.parseSemgrepJson(output, sourceVersion);
            case "javascript.typescript" -> javascript.parseTypescriptOutput(output, sourceVersion);
            default -> List.of();
        };
    }
}

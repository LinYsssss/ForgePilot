package com.example.codereview.language.python;

import com.example.codereview.finding.EvidenceType;
import com.example.codereview.finding.FindingCandidate;
import com.example.codereview.finding.FindingEvidence;
import com.example.codereview.finding.FindingSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

public final class PythonFindingNormalizer {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FindingCandidate> parseRuffJson(String json, String sourceVersion) {
        try {
            List<FindingCandidate> findings = new ArrayList<>();
            for (JsonNode result : mapper.readTree(json)) {
                String rule = required(result.path("code").asText(), "Ruff code");
                String message = required(result.path("message").asText(), "Ruff message");
                String file = required(result.path("filename").asText(), "Ruff filename");
                int start = result.path("location").path("row").asInt(1);
                int end = result.path("end_location").path("row").asInt(start);
                FindingSeverity severity = rule.startsWith("S") || rule.startsWith("B")
                        ? FindingSeverity.HIGH
                        : rule.startsWith("E") || rule.startsWith("F")
                                ? FindingSeverity.MEDIUM : FindingSeverity.LOW;
                findings.add(candidate(severity, rule, message, file, start, end, sourceVersion, message));
            }
            return List.copyOf(findings);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid Ruff JSON", ex);
        }
    }

    public List<FindingCandidate> parseBanditJson(String json, String sourceVersion) {
        try {
            List<FindingCandidate> findings = new ArrayList<>();
            for (JsonNode result : mapper.readTree(json).path("results")) {
                String rule = required(result.path("test_id").asText(), "Bandit test_id");
                String message = required(result.path("issue_text").asText(), "Bandit issue_text");
                String file = required(result.path("filename").asText(), "Bandit filename");
                int start = result.path("line_number").asInt(1);
                JsonNode range = result.path("line_range");
                int end = range.isArray() && !range.isEmpty() ? range.get(range.size() - 1).asInt(start) : start;
                FindingSeverity severity = switch (result.path("issue_severity").asText("MEDIUM")) {
                    case "HIGH" -> FindingSeverity.HIGH;
                    case "LOW" -> FindingSeverity.LOW;
                    default -> FindingSeverity.MEDIUM;
                };
                String excerpt = result.path("code").asText(message);
                findings.add(candidate(severity, rule, message, file, start, end, sourceVersion, excerpt));
            }
            return List.copyOf(findings);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid Bandit JSON", ex);
        }
    }

    public PythonValidationResult parsePytestJunit(String xml, String sourceVersion) {
        Document document = parseXml(xml);
        Element root = document.getDocumentElement();
        return new PythonValidationResult(
                sourceVersion,
                integer(root, "tests"),
                integer(root, "failures"),
                integer(root, "errors"),
                integer(root, "skipped"),
                decimal(root, "time"));
    }

    private static FindingCandidate candidate(
            FindingSeverity severity,
            String rule,
            String message,
            String file,
            int start,
            int end,
            String sourceVersion,
            String excerpt) {
        FindingEvidence evidence = FindingEvidence.create(
                EvidenceType.STATIC_ANALYZER, sourceVersion, file, start, end, excerpt, 1.0);
        return new FindingCandidate(
                severity,
                "python." + rule,
                rule,
                message,
                file,
                start,
                end,
                null,
                rule,
                sourceVersion,
                List.of(evidence));
    }

    private static Document parseXml(String xml) {
        try {
            // 与 JavaFindingNormalizer 同口径:解析的是沙箱工具输出这类不可信 XML,
            // 必须按 XXE 全关配置(禁 DOCTYPE、禁内外部实体、清空外部 DTD/Schema、
            // 关 XInclude 与实体展开)。少关一项,构造过的报告即可诱导后端读本地文件或外连。
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml == null ? "" : xml)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid Pytest JUnit XML", ex);
        }
    }

    private static int integer(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private static double decimal(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        return value == null || value.isBlank() ? 0.0 : Double.parseDouble(value);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}

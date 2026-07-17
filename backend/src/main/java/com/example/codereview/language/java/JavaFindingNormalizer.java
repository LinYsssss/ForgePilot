package com.example.codereview.language.java;

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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public final class JavaFindingNormalizer {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FindingCandidate> parsePmdXml(String xml, String sourceVersion) {
        Document document = parseXml(xml);
        List<FindingCandidate> findings = new ArrayList<>();
        NodeList files = document.getElementsByTagName("file");
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            NodeList violations = file.getElementsByTagName("violation");
            for (int j = 0; j < violations.getLength(); j++) {
                Element violation = (Element) violations.item(j);
                String rule = required(violation.getAttribute("rule"), "PMD rule");
                int start = integer(violation, "beginline", 1);
                int end = integer(violation, "endline", start);
                int priority = integer(violation, "priority", 3);
                String message = violation.getTextContent().trim();
                findings.add(candidate(
                        priority <= 2 ? FindingSeverity.HIGH : priority == 3 ? FindingSeverity.MEDIUM : FindingSeverity.LOW,
                        rule, message, file.getAttribute("name"), start, end, null, sourceVersion, message));
            }
        }
        return List.copyOf(findings);
    }

    public List<FindingCandidate> parseSpotBugsXml(String xml, String sourceVersion) {
        Document document = parseXml(xml);
        List<FindingCandidate> findings = new ArrayList<>();
        NodeList bugs = document.getElementsByTagName("BugInstance");
        for (int i = 0; i < bugs.getLength(); i++) {
            Element bug = (Element) bugs.item(i);
            String rule = required(bug.getAttribute("type"), "SpotBugs type");
            int priority = integer(bug, "priority", 2);
            Element line = firstChild(bug, "SourceLine");
            String file = line == null ? null : nullable(line.getAttribute("sourcepath"));
            Integer start = line == null ? null : integer(line, "start", 1);
            Integer end = line == null ? null : integer(line, "end", start);
            Element clazz = firstChild(bug, "Class");
            String symbol = clazz == null ? null : nullable(clazz.getAttribute("classname"));
            Element longMessage = firstChild(bug, "LongMessage");
            String message = longMessage == null ? rule : longMessage.getTextContent().trim();
            findings.add(candidate(
                    priority == 1 ? FindingSeverity.HIGH : priority == 2 ? FindingSeverity.MEDIUM : FindingSeverity.LOW,
                    rule, message, file, start, end, symbol, sourceVersion, message));
        }
        return List.copyOf(findings);
    }

    public List<FindingCandidate> parseCheckstyleXml(String xml, String sourceVersion) {
        Document document = parseXml(xml);
        List<FindingCandidate> findings = new ArrayList<>();
        NodeList files = document.getElementsByTagName("file");
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            NodeList errors = file.getElementsByTagName("error");
            for (int j = 0; j < errors.getLength(); j++) {
                Element error = (Element) errors.item(j);
                String source = required(error.getAttribute("source"), "Checkstyle source");
                String rule = source.substring(source.lastIndexOf('.') + 1);
                String severity = error.getAttribute("severity");
                String message = error.getAttribute("message");
                int line = integer(error, "line", 1);
                findings.add(candidate(
                        "error".equalsIgnoreCase(severity) ? FindingSeverity.MEDIUM : FindingSeverity.LOW,
                        rule, message, file.getAttribute("name"), line, line, null, sourceVersion, message));
            }
        }
        return List.copyOf(findings);
    }

    public List<FindingCandidate> parseSarif(String sarif, String sourceVersion) {
        try {
            JsonNode root = mapper.readTree(sarif);
            List<FindingCandidate> findings = new ArrayList<>();
            for (JsonNode run : root.path("runs")) {
                for (JsonNode result : run.path("results")) {
                    String rule = required(result.path("ruleId").asText(), "SARIF ruleId");
                    String message = result.path("message").path("text").asText(rule);
                    JsonNode physical = result.path("locations").path(0).path("physicalLocation");
                    String file = nullable(physical.path("artifactLocation").path("uri").asText());
                    JsonNode region = physical.path("region");
                    Integer start = region.has("startLine") ? region.path("startLine").asInt() : null;
                    Integer end = region.has("endLine") ? region.path("endLine").asInt() : start;
                    String excerpt = region.path("snippet").path("text").asText(message);
                    FindingSeverity severity = switch (result.path("level").asText("warning")) {
                        case "error" -> FindingSeverity.HIGH;
                        case "note", "none" -> FindingSeverity.LOW;
                        default -> FindingSeverity.MEDIUM;
                    };
                    findings.add(candidate(severity, rule, message, file, start, end, null, sourceVersion, excerpt));
                }
            }
            return List.copyOf(findings);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid SARIF output", ex);
        }
    }

    private static FindingCandidate candidate(
            FindingSeverity severity,
            String rule,
            String message,
            String file,
            Integer start,
            Integer end,
            String symbol,
            String sourceVersion,
            String excerpt) {
        FindingEvidence evidence = FindingEvidence.create(
                EvidenceType.STATIC_ANALYZER, sourceVersion, file, start, end, excerpt, 1.0);
        return new FindingCandidate(
                severity,
                "java." + rule,
                rule,
                message == null || message.isBlank() ? rule : message,
                file,
                start,
                end,
                symbol,
                rule,
                sourceVersion,
                List.of(evidence));
    }

    private static Document parseXml(String xml) {
        try {
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
            throw new IllegalArgumentException("invalid XML tool output", ex);
        }
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        Node node = nodes.item(0);
        return node instanceof Element element ? element : null;
    }

    private static int integer(Element element, String attribute, int fallback) {
        String value = element.getAttribute(attribute);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}

package dev.paperviz.parsing;

import dev.paperviz.domain.model.Enums.SectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Turns GROBID's TEI-XML into sections.
 *
 * Matching is done on local names throughout rather than with XPath, because
 * TEI is namespaced and local-name matching keeps this readable and immune to
 * prefix changes between GROBID versions.
 */
@Component
public class TeiParser {

    private static final Logger log = LoggerFactory.getLogger(TeiParser.class);

    public ParsedPaper parse(String tei) {
        Document doc = readDocument(tei);

        String title = firstElement(doc, "titleStmt")
                .flatMap(ts -> firstChildElement(ts, "title"))
                .map(this::textOf)
                .filter(s -> !s.isBlank())
                .orElse(null);

        String authors = extractAuthors(doc);

        List<ParsedSection> sections = new ArrayList<>();
        extractAbstract(doc).ifPresent(sections::add);
        sections.addAll(extractBodySections(doc));

        log.debug("parsed TEI: title={}, sections={}", title, sections.size());
        return new ParsedPaper(title, authors, sections);
    }

    private Document readDocument(String tei) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // GROBID output is ours, but this parser must never fetch a DTD or
            // expand an external entity regardless of what comes back.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);

            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(tei)));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "GROBID returned XML this parser could not read: " + e.getMessage(), e);
        }
    }

    private String extractAuthors(Document doc) {
        NodeList persNames = doc.getElementsByTagNameNS("*", "persName");
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < persNames.getLength(); i++) {
            Element persName = (Element) persNames.item(i);
            // Only header authors; names inside bibliography entries are citations.
            if (!isInsideHeader(persName)) {
                continue;
            }
            String forename = childText(persName, "forename");
            String surname = childText(persName, "surname");
            String full = (forename + " " + surname).trim();
            if (!full.isBlank()) {
                joiner.add(full);
            }
        }
        return joiner.length() == 0 ? null : joiner.toString();
    }

    private boolean isInsideHeader(Node node) {
        for (Node n = node; n != null; n = n.getParentNode()) {
            if ("teiHeader".equals(n.getLocalName())) {
                return true;
            }
            if ("listBibl".equals(n.getLocalName()) || "back".equals(n.getLocalName())) {
                return false;
            }
        }
        return false;
    }

    private Optional<ParsedSection> extractAbstract(Document doc) {
        return firstElement(doc, "abstract")
                .map(this::collectProse)
                .filter(text -> !text.isBlank())
                .map(text -> new ParsedSection(SectionType.ABSTRACT, "Abstract", text, null));
    }

    private List<ParsedSection> extractBodySections(Document doc) {
        List<ParsedSection> sections = new ArrayList<>();

        Optional<Element> body = firstElement(doc, "body");
        if (body.isEmpty()) {
            return sections;
        }

        for (Element div : childElements(body.get(), "div")) {
            String heading = firstChildElement(div, "head").map(this::textOf).orElse(null);
            String prose = collectProse(div);
            String latex = collectFormulas(div);

            // A heading with no content is a stub; skip it rather than emitting
            // an empty section the reader would render as a blank block.
            if (prose.isBlank() && latex == null) {
                continue;
            }

            sections.add(new ParsedSection(
                    SectionTypeClassifier.classify(heading),
                    heading,
                    prose,
                    latex));
        }
        return sections;
    }

    /** All paragraph text under this element, formulas excluded. */
    private String collectProse(Element root) {
        StringJoiner joiner = new StringJoiner("\n\n");
        for (Element p : descendantElements(root, "p")) {
            String text = textExcludingFormulas(p);
            if (!text.isBlank()) {
                joiner.add(text);
            }
        }
        return joiner.toString();
    }

    /** Display formulas, kept verbatim so the reader can hand them to KaTeX. */
    private String collectFormulas(Element root) {
        StringJoiner joiner = new StringJoiner("\n");
        for (Element f : descendantElements(root, "formula")) {
            String text = textOf(f);
            if (!text.isBlank()) {
                joiner.add(text);
            }
        }
        return joiner.length() == 0 ? null : joiner.toString();
    }

    private String textExcludingFormulas(Element element) {
        StringBuilder sb = new StringBuilder();
        appendText(element, sb);
        return normalise(sb.toString());
    }

    private void appendText(Node node, StringBuilder sb) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                sb.append(child.getNodeValue());
            } else if (child.getNodeType() == Node.ELEMENT_NODE
                    && !"formula".equals(child.getLocalName())) {
                appendText(child, sb);
            }
        }
    }

    private String textOf(Node node) {
        return normalise(node.getTextContent());
    }

    private String normalise(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private String childText(Element parent, String localName) {
        return firstChildElement(parent, localName).map(this::textOf).orElse("");
    }

    private Optional<Element> firstElement(Document doc, String localName) {
        NodeList list = doc.getElementsByTagNameNS("*", localName);
        return list.getLength() == 0 ? Optional.empty() : Optional.of((Element) list.item(0));
    }

    private Optional<Element> firstChildElement(Element parent, String localName) {
        List<Element> found = childElements(parent, localName);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private List<Element> childElements(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && localName.equals(child.getLocalName())) {
                result.add((Element) child);
            }
        }
        return result;
    }

    private List<Element> descendantElements(Element root, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList list = root.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < list.getLength(); i++) {
            result.add((Element) list.item(i));
        }
        return result;
    }
}

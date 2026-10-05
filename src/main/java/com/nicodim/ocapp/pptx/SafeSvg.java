package com.nicodim.ocapp.pptx;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Fail-closed validator for sanitized inline SVG at the Java trust boundary. */
final class SafeSvg {
    private static final Set<String> ELEMENTS = Set.of("svg", "g", "path", "rect", "circle", "ellipse", "line",
        "polyline", "polygon", "text", "tspan", "defs", "lineargradient", "radialgradient", "stop", "clippath");
    private static final Set<String> ATTRIBUTES = Set.of("xmlns", "viewbox", "preserveaspectratio", "x", "y", "x1",
        "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry", "width", "height", "d", "points", "transform",
        "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap",
        "stroke-linejoin", "stroke-dasharray", "stroke-dashoffset", "opacity", "font-family", "font-size",
        "font-style", "font-weight", "text-anchor", "dominant-baseline", "offset", "stop-color", "stop-opacity",
        "clip-path", "clip-rule", "id");

    private SafeSvg() { }

    static void validate(byte[] bytes) {
        if (bytes.length < 5 || bytes.length > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("SVG is invalid");
        String prefix = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (prefix.contains("<!doctype") || prefix.contains("<!entity")) throw new IllegalArgumentException("SVG active content is forbidden");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Element root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if (root == null || !"svg".equalsIgnoreCase(root.getLocalName())) throw new IllegalArgumentException("SVG root is invalid");
            validateElement(root);
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("SVG is malformed", ex); }
    }

    private static void validateElement(Element element) {
        String name = local(element).toLowerCase(Locale.ROOT);
        if (!ELEMENTS.contains(name)) throw new IllegalArgumentException("SVG element is unsupported");
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node attribute = element.getAttributes().item(i);
            String key = local(attribute).toLowerCase(Locale.ROOT);
            String qualified = attribute.getNodeName().toLowerCase(Locale.ROOT);
            String value = attribute.getNodeValue().trim().toLowerCase(Locale.ROOT);
            if (qualified.startsWith("on") || "style".equals(key) || "href".equals(key) || qualified.endsWith(":href")
                || !ATTRIBUTES.contains(key) || (!"xmlns".equals(key)
                && (value.contains("url(") || value.matches(".*(?:https?|data|file|javascript):.*"))))
                throw new IllegalArgumentException("SVG attribute is unsafe");
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) validateElement((Element) child);
            else if (child.getNodeType() != Node.TEXT_NODE && child.getNodeType() != Node.CDATA_SECTION_NODE
                && child.getNodeType() != Node.COMMENT_NODE) throw new IllegalArgumentException("SVG node is unsupported");
        }
    }

    private static String local(Node node) { return node.getLocalName() == null ? node.getNodeName() : node.getLocalName(); }
}

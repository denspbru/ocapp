package com.nicodim.ocapp.pptx;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.support.ConversionException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.w3c.dom.Element;

/** Fail-closed OPC manifest, content-type, and relationship validator. */
final class PptxPackageValidator {
    private PptxPackageValidator() { }
    record Stats(int entries, long uncompressedBytes) { }

    static Stats validateTemplate(byte[] bytes, ConverterProperties.Pptx.Template limits) {
        return validate(bytes, limits.getMaxEntries(), limits.getMaxEntryBytes(), limits.getMaxUncompressedBytes(),
            limits.getMaxCompressionRatio(), false, -1);
    }

    static Stats validateOutput(byte[] bytes, int expectedSlides) {
        long maximum = Math.max(1024L, Math.min(250L * 1024 * 1024, (long) bytes.length * 40 + 1024 * 1024));
        return validate(bytes, 20_000, 100L * 1024 * 1024, maximum, 2_000, true, expectedSlides);
    }

    private static Stats validate(byte[] bytes, int maxEntries, long maxEntry, long maxTotal,
                                  double maxRatio, boolean allowNotes, int expectedSlides) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw invalid();
        Map<String, byte[]> inspected = new HashMap<>();
        Set<String> names = new HashSet<>();
        int entries = 0, slideParts = 0, notesParts = 0; long total = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > maxEntries) throw tooLarge();
                String name = normalizePart(entry.getName());
                if (name == null || !names.add(name.toLowerCase(Locale.ROOT))) throw untrusted();
                long count = 0; ByteArrayOutputStream captured = inspect(name) ? new ByteArrayOutputStream() : null;
                int read; while ((read = input.read(buffer)) != -1) {
                    count = add(count, read); total = add(total, read);
                    if (count > maxEntry || total > maxTotal) throw tooLarge();
                    if (captured != null) captured.write(buffer, 0, read);
                }
                long declared = entry.getSize(), compressed = entry.getCompressedSize();
                if (declared > maxEntry || (declared >= 0 && declared != count)) throw invalid();
                if (count > 0 && (compressed <= 0 || count / (double) compressed > maxRatio)) throw tooLarge();
                if (captured != null) inspected.put(name, captured.toByteArray());
                if (name.matches("ppt/slides/slide[0-9]+\\.xml")) slideParts++;
                if (name.matches("ppt/notesSlides/notesSlide[0-9]+\\.xml")) notesParts++;
                if (!allowedPart(name, allowNotes)) throw content();
            }
        } catch (ConversionException ex) { throw ex; }
        catch (Exception ex) { throw invalid(); }
        if (!names.contains("[content_types].xml") || !names.contains("ppt/presentation.xml")) throw invalid();
        if (expectedSlides >= 0 && (slideParts != expectedSlides || notesParts > expectedSlides)) throw invalid();
        ContentTypes types = parseContentTypes(inspected.get("[Content_Types].xml"));
        for (String name : names) {
            if (name.endsWith("/") || name.endsWith(".rels") || name.equals("[content_types].xml")) continue;
            String type = types.typeFor(name);
            if (type == null || forbiddenType(type) || !typeMatchesPart(name, type, allowNotes)) throw content();
        }
        for (Map.Entry<String, byte[]> rel : inspected.entrySet()) if (rel.getKey().endsWith(".rels"))
            validateRelationships(rel.getKey(), rel.getValue(), names, allowNotes);
        return new Stats(entries, total);
    }

    private static void validateRelationships(String relName, byte[] xml, Set<String> names, boolean allowNotes) {
        var document = parse(xml); var nodes = document.getElementsByTagNameNS("*", "Relationship");
        String owner = ownerPart(relName); String base = owner.isEmpty() ? "" : owner.substring(0, Math.max(0, owner.lastIndexOf('/') + 1));
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i); String mode = e.getAttribute("TargetMode");
            String target = e.getAttribute("Target").trim(); String type = e.getAttribute("Type").toLowerCase(Locale.ROOT);
            if ("external".equalsIgnoreCase(mode)) {
                if (!allowNotes || !type.endsWith("/hyperlink")) throw content();
                URI external; try { external = URI.create(target); } catch (RuntimeException ex) { throw content(); }
                if (!("http".equalsIgnoreCase(external.getScheme()) || "https".equalsIgnoreCase(external.getScheme()))
                    || external.getHost() == null || external.getUserInfo() != null) throw content();
                continue;
            }
            if (forbiddenRelationship(type, allowNotes)) throw content();
            if (target.isEmpty() || target.indexOf('\\') >= 0 || target.indexOf(0) >= 0
                || target.matches("(?i).*%(00|2e|2f|5c).*") || target.startsWith("/")) throw content();
            URI uri; try { uri = URI.create(target); } catch (RuntimeException ex) { throw content(); }
            if (uri.isAbsolute() || uri.getScheme() != null || uri.getRawAuthority() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) throw content();
            String resolved = normalizePart(base + uri.getPath());
            if (resolved == null || !names.contains(resolved.toLowerCase(Locale.ROOT))) throw content();
        }
    }

    private static String ownerPart(String rel) {
        if ("_rels/.rels".equals(rel)) return "";
        int marker = rel.lastIndexOf("/_rels/");
        if (marker < 0 || !rel.endsWith(".rels")) throw content();
        return rel.substring(0, marker + 1) + rel.substring(marker + 7, rel.length() - 5);
    }

    private static boolean forbiddenRelationship(String type, boolean allowNotes) {
        return type.contains("audio") || type.contains("video") || type.contains("media") || type.contains("oleobject")
            || type.contains("activex") || type.contains("vbaproject") || type.endsWith("/package")
            || type.contains("customxml") || type.contains("comment") || (!allowNotes && type.contains("notes"));
    }

    private static boolean allowedPart(String name, boolean allowNotes) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith("/")) return true;
        if (n.equals("[content_types].xml") || n.equals("_rels/.rels") || n.matches("docprops/(core|app|custom)\\.xml")
            || n.matches("docprops/thumbnail\\.(png|jpe?g)")) return true;
        if (n.equals("ppt/presentation.xml") || n.equals("ppt/_rels/presentation.xml.rels")
            || n.matches("ppt/(presprops|viewprops|tablestyles)\\.xml")) return true;
        if (n.startsWith("ppt/slidemasters/") || n.startsWith("ppt/slidelayouts/") || n.startsWith("ppt/theme/")) return true;
        if (n.startsWith("ppt/slides/") || n.startsWith("ppt/fonts/")) return true;
        if (n.startsWith("ppt/media/")) return n.matches(".*\\.(png|jpe?g|gif|bmp|tiff?|svg|emf|wmf)");
        if (allowNotes && (n.startsWith("ppt/notesmasters/") || n.startsWith("ppt/notesslides/"))) return true;
        return false;
    }

    private static boolean typeMatchesPart(String name, String type, boolean allowNotes) {
        String n = name.toLowerCase(Locale.ROOT), t = type.toLowerCase(Locale.ROOT);
        if (n.startsWith("ppt/media/")) return t.startsWith("image/") || t.contains("image");
        if (n.startsWith("ppt/fonts/")) return t.contains("font") || t.contains("obfuscated-opentype");
        if (!allowNotes && n.contains("notes")) return false;
        return !forbiddenType(t);
    }

    private static boolean forbiddenType(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        return t.startsWith("audio/") || t.startsWith("video/") || t.contains("macroenabled") || t.contains("vbaproject")
            || t.contains("activex") || t.contains("oleobject") || t.endsWith(".package") || t.contains("embedded-package") || t.contains("customxml")
            || t.contains("comment") || t.equals("application/octet-stream");
    }

    private static ContentTypes parseContentTypes(byte[] xml) {
        if (xml == null) throw invalid(); var document = parse(xml);
        Map<String,String> defaults = new HashMap<>(), overrides = new HashMap<>();
        var ds = document.getElementsByTagNameNS("*", "Default");
        for (int i=0;i<ds.getLength();i++) { Element e=(Element)ds.item(i); defaults.put(e.getAttribute("Extension").toLowerCase(Locale.ROOT), e.getAttribute("ContentType")); }
        var os = document.getElementsByTagNameNS("*", "Override");
        for (int i=0;i<os.getLength();i++) { Element e=(Element)os.item(i); String p=e.getAttribute("PartName"); if (p.startsWith("/")) p=p.substring(1); overrides.put(p.toLowerCase(Locale.ROOT), e.getAttribute("ContentType")); }
        return new ContentTypes(defaults, overrides);
    }

    private static org.w3c.dom.Document parse(byte[] xml) {
        try { DocumentBuilderFactory f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true); f.setXIncludeAware(false); f.setExpandEntityReferences(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception ex) { throw invalid(); }
    }

    private static boolean inspect(String n) { return n.endsWith(".rels") || n.equals("[Content_Types].xml"); }
    private static String normalizePart(String raw) {
        if (raw == null || raw.isBlank() || raw.indexOf('\\') >= 0 || raw.indexOf(0) >= 0 || raw.startsWith("/") || raw.matches("(?i)^[a-z]:.*")) return null;
        try { String n=Path.of(raw).normalize().toString().replace('\\','/'); return n.equals("..") || n.startsWith("../") || n.startsWith("/") ? null : n; }
        catch (RuntimeException ex) { return null; }
    }
    private static long add(long a,long b) { if (b<0 || a>Long.MAX_VALUE-b) throw tooLarge(); return a+b; }
    private static ConversionException invalid() { return new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY,"PPTX_TEMPLATE_INVALID","Presentation package is invalid"); }
    private static ConversionException untrusted() { return new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY,"PPTX_TEMPLATE_UNTRUSTED","Presentation package is not trusted"); }
    private static ConversionException content() { return new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY,"PPTX_TEMPLATE_CONTENT_NOT_ALLOWED","Presentation package contains unsupported content"); }
    private static ConversionException tooLarge() { return new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE,"PPTX_TEMPLATE_TOO_LARGE","Presentation package exceeds resource limits"); }
    private record ContentTypes(Map<String,String> defaults, Map<String,String> overrides) {
        String typeFor(String name) { String n=name.toLowerCase(Locale.ROOT); String v=overrides.get(n); if(v!=null)return v; int dot=n.lastIndexOf('.'); return dot<0?null:defaults.get(n.substring(dot+1)); }
    }
}

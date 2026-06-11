package org.knime.serverconnector.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts searchable text from a workflow directory and tests for a
 * substring match. Used by deep search across HTTP, local-drive, and
 * workspace backends.
 *
 * <p>Two content kinds are supported today:
 * <ul>
 *   <li><b>NODE_TITLE</b> &mdash; per-node {@code settings.xml}
 *       {@code <entry key="name" .../>}. Holds the editable label
 *       (defaults to the factory's display name; updated when the user
 *       renames a node in the editor).
 *   <li><b>ANNOTATION</b> &mdash; workflow.knime
 *       {@code <config key="annotations">/<config key="annotation_N">/
 *       <entry key="text" .../>}. Holds the HTML body of each sticky
 *       note; tags and entities are stripped before matching.
 * </ul>
 *
 * <p>Parsing is pattern-based rather than full XML so the same code can
 * run on Java 8 (4.1.x variant) without dragging in an XML parser
 * dependency. The KNIME formats are stable enough that this is safe.
 */
public final class WorkflowContentIndex {

    public enum Field { NODE_TITLE, ANNOTATION }

    /** {@code <entry key="text" ... value="HTML">} inside annotations. */
    private static final Pattern ANNOT_TEXT = Pattern.compile(
            "<entry\\s+key=\"text\"[^>]*\\bvalue=\"([^\"]*)\"");

    /** {@code <entry key="name" ... value="...">} -- node title in settings.xml. */
    private static final Pattern NAME_ENTRY = Pattern.compile(
            "<entry\\s+key=\"name\"[^>]*\\bvalue=\"([^\"]*)\"");

    /** {@code <config key="annotations">} block opener. */
    private static final Pattern ANNOT_BLOCK_OPEN = Pattern.compile(
            "<config\\s+key=\"annotations\"[^>]*>");

    private WorkflowContentIndex() {}

    /**
     * Return true if any of the requested fields in {@code workflowDir}
     * contains {@code query} (case-insensitive substring).
     *
     * <p>Walks {@code workflow.knime} for annotations and every
     * per-node {@code settings.xml} for node titles. Returns false on
     * I/O failure (deep search is best-effort).
     */
    public static boolean matches(File workflowDir, String query, EnumSet<Field> fields) {
        if (query == null || query.isEmpty() || fields == null || fields.isEmpty()) {
            return false;
        }
        if (workflowDir == null || !workflowDir.isDirectory()) return false;
        String needle = query.toLowerCase();

        try {
            if (fields.contains(Field.ANNOTATION)) {
                File wfFile = new File(workflowDir, "workflow.knime");
                if (wfFile.isFile() && annotationHas(wfFile, needle)) return true;
            }
            if (fields.contains(Field.NODE_TITLE)) {
                return scanNodeTitles(workflowDir, needle);
            }
        } catch (IOException e) {
            // Best-effort: a corrupted or unreadable workflow shouldn't
            // crash the whole search. Log + move on.
            System.err.println("[ServerExplorer] deep-search read failed for "
                    + workflowDir + ": " + e);
        }
        return false;
    }

    /**
     * Look inside workflow.knime for any annotation whose text (after
     * HTML strip + entity decode) contains the needle.
     */
    private static boolean annotationHas(File wfFile, String needle) throws IOException {
        String content = readAll(wfFile);
        Matcher openM = ANNOT_BLOCK_OPEN.matcher(content);
        if (!openM.find()) return false;
        // Search annotation text entries only INSIDE the annotations block
        // -- otherwise we'd also match per-node "text" entries elsewhere.
        int blockStart = openM.end();
        int blockEnd = findMatchingConfigClose(content, openM.start());
        if (blockEnd < 0) blockEnd = content.length();

        Matcher m = ANNOT_TEXT.matcher(content.subSequence(blockStart, blockEnd));
        while (m.find()) {
            String raw = m.group(1);
            String plain = htmlToPlainText(raw);
            if (plain.toLowerCase().contains(needle)) return true;
        }
        return false;
    }

    /**
     * Walk subdirectories of the workflow looking for {@code settings.xml}
     * files; the first occurrence of {@code <entry key="name" ...>} in
     * each is the node title.
     */
    private static boolean scanNodeTitles(File workflowDir, String needle) throws IOException {
        File[] entries = workflowDir.listFiles();
        if (entries == null) return false;
        for (File entry : entries) {
            if (!entry.isDirectory()) continue;
            File settings = new File(entry, "settings.xml");
            if (!settings.isFile()) {
                // Component / metanode: recurse one level (rare; mostly
                // user-set titles still live at top level).
                if (scanNodeTitles(entry, needle)) return true;
                continue;
            }
            String content = readAll(settings);
            Matcher m = NAME_ENTRY.matcher(content);
            if (m.find()) {
                String name = m.group(1);
                if (name.toLowerCase().contains(needle)) return true;
            }
        }
        return false;
    }

    /**
     * Locate the closing tag for the {@code <config key="annotations">}
     * block opened at {@code openOffset}, accounting for nested
     * {@code <config>}s. Returns the offset just before the closing
     * {@code </config>} of that block, or -1 if not balanced.
     */
    private static int findMatchingConfigClose(String content, int openOffset) {
        // Walk forward token by token, tracking depth of nested <config> tags.
        Pattern tag = Pattern.compile("<(/?)config\\b[^>]*>");
        Matcher m = tag.matcher(content);
        if (!m.find(openOffset)) return -1;
        int depth = 1;  // we already entered the annotations config
        while (m.find()) {
            String slash = m.group(1);
            if (slash.isEmpty()) depth++;
            else {
                depth--;
                if (depth == 0) return m.start();
            }
        }
        return -1;
    }

    /** Strip HTML tags, decode common entities. */
    private static String htmlToPlainText(String s) {
        if (s == null || s.isEmpty()) return "";
        String decoded = decodeXmlEntities(s);
        // Strip tags. The KNIME annotation HTML is well-formed enough
        // that a non-greedy '<...>' eat is sufficient.
        String stripped = decoded.replaceAll("<[^>]+>", " ");
        return decodeXmlEntities(stripped); // decode again in case &lt;p&gt; was double-encoded
    }

    private static String decodeXmlEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        return s
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#10;", "\n")
                .replace("&#13;", "\r")
                .replace("&amp;", "&"); // last so we don't double-decode
    }

    private static String readAll(File f) throws IOException {
        // Workflow files are typically a few KB to a few MB at the top
        // end. Read into memory in one shot for simpler regex matching.
        byte[] bytes = Files.readAllBytes(f.toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Walk under {@code root} looking for workflow directories whose
     * content matches {@code query} in {@code fields}. Returns the
     * matched workflows as paths relative to {@code root} (with a
     * leading '/'). Use for the workspace branch's deep search.
     */
    public static Set<String> findMatches(File root, String query, EnumSet<Field> fields) {
        Set<String> out = new HashSet<>();
        if (root == null || !root.isDirectory()
                || query == null || query.isEmpty()
                || fields == null || fields.isEmpty()) {
            return out;
        }
        walk(root, root, query, fields, out);
        return out;
    }

    private static void walk(File base, File dir, String query,
                             EnumSet<Field> fields, Set<String> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (!entry.isDirectory() || entry.getName().startsWith(".")) continue;
            if (new File(entry, "workflow.knime").isFile()) {
                if (matches(entry, query, fields)) {
                    String rel = base.toPath().relativize(entry.toPath()).toString();
                    out.add("/" + rel.replace(File.separatorChar, '/'));
                }
                // Don't descend into a workflow's node guts.
                continue;
            }
            walk(base, entry, query, fields, out);
        }
    }

    /**
     * Convenience for command-line / debug: stream a quick summary of
     * matchable strings in {@code workflowDir}.
     */
    public static void dump(File workflowDir) throws IOException {
        File wfFile = new File(workflowDir, "workflow.knime");
        if (wfFile.isFile()) {
            String content = readAll(wfFile);
            Matcher openM = ANNOT_BLOCK_OPEN.matcher(content);
            if (openM.find()) {
                int blockEnd = findMatchingConfigClose(content, openM.start());
                if (blockEnd < 0) blockEnd = content.length();
                Matcher m = ANNOT_TEXT.matcher(content.subSequence(openM.end(), blockEnd));
                while (m.find()) {
                    System.out.println("[annot] " + htmlToPlainText(m.group(1)));
                }
            }
        }
        File[] entries = workflowDir.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                if (!entry.isDirectory()) continue;
                File settings = new File(entry, "settings.xml");
                if (!settings.isFile()) continue;
                String content = readAll(settings);
                Matcher m = NAME_ENTRY.matcher(content);
                if (m.find()) {
                    System.out.println("[title] " + entry.getName() + " -> " + m.group(1));
                }
            }
        }
    }
}

package tf.dpi.ai.runbook;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits sanitized runbook text into retrieval units. Splits on blank-line paragraph boundaries and packs paragraphs up
 * to {@code maxChars}; a single oversized paragraph is hard-split on line then character boundaries. Deterministic.
 */
public final class KnowledgeChunker {

    private final int maxChars;

    public KnowledgeChunker(int maxChars) {
        if (maxChars < 200) {
            throw new IllegalArgumentException("maxChars must be >= 200");
        }
        this.maxChars = maxChars;
    }

    public List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : text.strip().split("\\R\\s*\\R")) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > maxChars) {
                flush(cur, out);
                splitLarge(p, out);
                continue;
            }
            if (cur.length() + p.length() + 2 > maxChars) {
                flush(cur, out);
            }
            if (cur.length() > 0) {
                cur.append("\n\n");
            }
            cur.append(p);
        }
        flush(cur, out);
        return out;
    }

    private void splitLarge(String p, List<String> out) {
        StringBuilder cur = new StringBuilder();
        for (String line : p.split("\\R")) {
            String l = line;
            while (l.length() > maxChars) {
                flush(cur, out);
                out.add(l.substring(0, maxChars));
                l = l.substring(maxChars);
            }
            if (cur.length() + l.length() + 1 > maxChars) {
                flush(cur, out);
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(l);
        }
        flush(cur, out);
    }

    private static void flush(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }
}

package tf.dpi.ai.masking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Masks JSON documents structurally: values under sensitive keys are replaced wholesale, every other string
 * value goes through the regex {@link MaskingEngine}. Non-JSON input is masked as plain text. Fails closed.
 */
public final class StructuredLogMasker {

    private static final int MAX_DEPTH = 64;

    private final MaskingEngine engine;
    private final ObjectMapper mapper;

    public StructuredLogMasker(MaskingEngine engine, ObjectMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
    }

    /** Masks free text, treating it as JSON when it parses as a JSON object or array. */
    public String maskAuto(String input) {
        if (input == null || input.isBlank()) {
            return input == null ? "" : input;
        }
        String trimmed = input.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode root = mapper.readTree(input);
                return mapper.writeValueAsString(maskNode(root, 0));
            } catch (JsonProcessingException notJson) {
                // Not valid JSON (e.g. a log line starting with '['): fall through to text masking.
            }
        }
        return engine.maskText(input);
    }

    /** Masks a map of attributes (key/value pairs), applying sensitive-key rules to the keys. */
    public Map<String, String> maskAttributes(Map<String, String> attributes) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            out.put(e.getKey(), isSensitiveKey(e.getKey())
                    ? MaskingRule.placeholder(MaskingCategory.CUSTOM)
                    : maskAuto(e.getValue()));
        }
        return out;
    }

    private JsonNode maskNode(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) {
            throw new MaskingException("JSON nesting too deep to mask safely; content withheld");
        }
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                String key = engine.maskText(f.getKey());
                if (isSensitiveKey(f.getKey()) && !f.getValue().isNull()) {
                    result.put(key, MaskingRule.placeholder(MaskingCategory.CUSTOM));
                } else {
                    result.set(key, maskNode(f.getValue(), depth + 1));
                }
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            for (JsonNode child : node) {
                result.add(maskNode(child, depth + 1));
            }
            return result;
        }
        if (node.isTextual()) {
            return mapper.getNodeFactory().textNode(engine.maskText(node.asText()));
        }
        return node;
    }

    private boolean isSensitiveKey(String key) {
        return engine.policy().sensitiveJsonKeys().contains(key.toLowerCase(Locale.ROOT));
    }
}

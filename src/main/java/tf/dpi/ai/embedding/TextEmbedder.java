package tf.dpi.ai.embedding;

import java.util.List;

/** Embeds already-sanitized text. Callers must mask first; implementations never see raw sensitive content. */
public interface TextEmbedder {

    List<float[]> embedTexts(List<String> texts);
}

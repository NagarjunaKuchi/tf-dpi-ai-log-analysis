package tf.dpi.ai.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** Deterministic bag-of-words hashing embedding (normalised) so similar texts are close. Test use only. */
public class FakeEmbeddingModel implements EmbeddingModel {

    private final int dims;

    public FakeEmbeddingModel(int dims) {
        this.dims = dims;
    }

    public float[] embedText(String text) {
        float[] v = new float[dims];
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
            if (!w.isEmpty()) {
                v[Math.floorMod(w.hashCode(), dims)] += 1f;
            }
        }
        double n = 0;
        for (float f : v) {
            n += f * f;
        }
        n = Math.sqrt(n);
        if (n == 0) {
            v[0] = 1f;
            n = 1;
        }
        for (int i = 0; i < dims; i++) {
            v[i] /= (float) n;
        }
        return v;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> out = new ArrayList<>();
        int i = 0;
        for (String t : request.getInstructions()) {
            out.add(new Embedding(embedText(t), i++));
        }
        return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
        return embedText(document.getText());
    }

    @Override
    public int dimensions() {
        return dims;
    }
}

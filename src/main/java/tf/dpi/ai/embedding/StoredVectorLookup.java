package tf.dpi.ai.embedding;

import java.util.Collection;
import java.util.Map;

/** Lets the embedder skip chunks that already have a vector for the active model (duplicate-embedding guard). */
public interface StoredVectorLookup {

    /** chunk id -> content sha-256 of the stored vector, restricted to the given model name and version. */
    Map<String, String> storedHashes(Collection<String> chunkIds, String modelName, String modelVersion);
}

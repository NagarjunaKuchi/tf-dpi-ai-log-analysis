package tf.dpi.ai.ingestion;

import java.util.List;
import tf.dpi.ai.embedding.EmbeddedChunk;

/**
 * Atomically persists a batch: vectors, dead letters and the new checkpoint in ONE transaction. Either everything
 * is durable and the checkpoint moves, or nothing changes. This is what guarantees the checkpoint never passes a
 * record that is neither stored nor dead-lettered.
 */
public interface BatchCommitter {

    void commit(String streamName, List<EmbeddedChunk> chunks, List<DeadLetter> deadLetters, Checkpoint next);
}

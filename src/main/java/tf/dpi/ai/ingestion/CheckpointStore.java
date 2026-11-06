package tf.dpi.ai.ingestion;

import java.util.Optional;

public interface CheckpointStore {

    Optional<Checkpoint> load(String streamName);
}

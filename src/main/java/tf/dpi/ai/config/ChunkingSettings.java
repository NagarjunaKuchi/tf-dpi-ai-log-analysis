package tf.dpi.ai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import tf.dpi.ai.chunking.ChunkingProperties;

@ConfigurationProperties("dpi.chunking")
public record ChunkingSettings(
        @DefaultValue("512") int maxChunkTokens,
        @DefaultValue("60s") Duration maxGap,
        @DefaultValue("10m") Duration maxSpan,
        @DefaultValue("1") int overlapEvents,
        @DefaultValue("8") int maxFramesPerException) {

    public ChunkingProperties toProperties() {
        return new ChunkingProperties(maxChunkTokens, maxGap, maxSpan, overlapEvents, maxFramesPerException);
    }
}

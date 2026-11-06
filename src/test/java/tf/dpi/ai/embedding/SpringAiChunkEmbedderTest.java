package tf.dpi.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import tf.dpi.ai.exception.EmbeddingUnavailableException;
import tf.dpi.ai.exception.InvalidEmbeddingException;
import tf.dpi.ai.model.LogChunk;
import tf.dpi.ai.model.LogLevel;

class SpringAiChunkEmbedderTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private Map<String, String> stored = Map.of();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private SpringAiChunkEmbedder embedder(int dims, int batch, Duration timeout) {
        EmbeddingProperties props = new EmbeddingProperties("bge-m3", "1", dims, batch, timeout);
        return new SpringAiChunkEmbedder(model, props, (ids, m, v) -> stored, executor, new SimpleMeterRegistry());
    }

    private static LogChunk chunk(String id, String content) {
        return new LogChunk(id, "svc", LogLevel.ERROR, Instant.EPOCH, Instant.EPOCH, "", "", "", content, List.of("s"),
                Map.of());
    }

    private static List<float[]> vectors(int n, int dims) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new float[dims]);
        }
        return out;
    }

    @Test
    void embedsInConfiguredBatches() {
        when(model.embed(anyList())).thenAnswer(inv -> vectors(((List<?>) inv.getArgument(0)).size(), 4));
        List<EmbeddedChunk> out = embedder(4, 2, Duration.ofSeconds(5))
                .embed(List.of(chunk("a", "1"), chunk("b", "2"), chunk("c", "3")));
        assertThat(out).hasSize(3).allMatch(e -> !e.alreadyStored() && e.embedding().length == 4);
        verify(model, times(2)).embed(anyList()); // 2 + 1
    }

    @Test
    void skipsChunksWhoseIdenticalVectorIsAlreadyStored() {
        LogChunk c = chunk("a", "same content");
        stored = Map.of("a", SpringAiChunkEmbedder.sha256("same content"));
        List<EmbeddedChunk> out = embedder(4, 8, Duration.ofSeconds(5)).embed(List.of(c));
        assertThat(out.get(0).alreadyStored()).isTrue();
        verify(model, never()).embed(anyList());
    }

    @Test
    void reEmbedsWhenContentChangedSinceStored() {
        stored = Map.of("a", "hash-of-old-content");
        when(model.embed(anyList())).thenReturn(vectors(1, 4));
        List<EmbeddedChunk> out = embedder(4, 8, Duration.ofSeconds(5)).embed(List.of(chunk("a", "new content")));
        assertThat(out.get(0).alreadyStored()).isFalse();
    }

    @Test
    void rejectsWrongDimension() {
        when(model.embed(anyList())).thenReturn(vectors(1, 3));
        assertThatThrownBy(() -> embedder(4, 8, Duration.ofSeconds(5)).embed(List.of(chunk("a", "x"))))
                .isInstanceOf(InvalidEmbeddingException.class).hasMessageContaining("expected 4");
    }

    @Test
    void rejectsWrongVectorCountAndNonFiniteValues() {
        when(model.embed(anyList())).thenReturn(vectors(2, 4));
        assertThatThrownBy(() -> embedder(4, 8, Duration.ofSeconds(5)).embed(List.of(chunk("a", "x"))))
                .isInstanceOf(InvalidEmbeddingException.class);
        List<float[]> nan = new ArrayList<>();
        nan.add(new float[] {Float.NaN, 0, 0, 0});
        when(model.embed(anyList())).thenReturn(nan);
        assertThatThrownBy(() -> embedder(4, 8, Duration.ofSeconds(5)).embed(List.of(chunk("b", "y"))))
                .isInstanceOf(InvalidEmbeddingException.class).hasMessageContaining("non-finite");
    }

    @Test
    void modelFailureBecomesEmbeddingUnavailable() {
        when(model.embed(anyList())).thenThrow(new IllegalStateException("connection refused"));
        assertThatThrownBy(() -> embedder(4, 8, Duration.ofSeconds(5)).embed(List.of(chunk("a", "x"))))
                .isInstanceOf(EmbeddingUnavailableException.class);
    }

    @Test
    void slowModelTimesOut() {
        when(model.embed(anyList())).thenAnswer(inv -> {
            Thread.sleep(2000);
            return vectors(1, 4);
        });
        assertThatThrownBy(() -> embedder(4, 8, Duration.ofMillis(100)).embed(List.of(chunk("a", "x"))))
                .isInstanceOf(EmbeddingUnavailableException.class).hasMessageContaining("timed out");
    }

    @Test
    void emptyInputDoesNothing() {
        assertThat(embedder(4, 8, Duration.ofSeconds(1)).embed(List.of())).isEmpty();
        verify(model, never()).embed(anyList());
    }
}

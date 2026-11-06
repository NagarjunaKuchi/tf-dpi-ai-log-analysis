package tf.dpi.ai.embedding;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.embedding.EmbeddingModel;
import tf.dpi.ai.exception.EmbeddingUnavailableException;
import tf.dpi.ai.exception.InvalidEmbeddingException;
import tf.dpi.ai.model.LogChunk;

/**
 * Batch embedding through Spring AI's {@link EmbeddingModel} (self-hosted Ollama or an OpenAI-compatible vLLM).
 * Calls run on a small bounded executor so each batch can be timed out; vectors are validated (count, dimension,
 * finite values) before they can reach the database.
 */
public class SpringAiChunkEmbedder implements ChunkEmbedder, TextEmbedder {

    private final EmbeddingModel model;
    private final EmbeddingProperties props;
    private final StoredVectorLookup lookup;
    private final ExecutorService executor;
    private final Counter generated;
    private final Counter skipped;
    private final Counter failures;
    private final Timer latency;

    public SpringAiChunkEmbedder(EmbeddingModel model, EmbeddingProperties props, StoredVectorLookup lookup,
            ExecutorService boundedExecutor, MeterRegistry meters) {
        this.model = model;
        this.props = props;
        this.lookup = lookup;
        this.executor = boundedExecutor;
        this.generated = Counter.builder("dpi.embeddings.generated").register(meters);
        this.skipped = Counter.builder("dpi.embeddings.skipped.duplicate").register(meters);
        this.failures = Counter.builder("dpi.embeddings.failures").register(meters);
        this.latency = Timer.builder("dpi.embeddings.latency").register(meters);
    }

    @Override
    public List<EmbeddedChunk> embed(List<LogChunk> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        List<String> ids = chunks.stream().map(LogChunk::chunkId).toList();
        Map<String, String> stored = lookup.storedHashes(ids, props.modelName(), props.modelVersion());

        EmbeddedChunk[] result = new EmbeddedChunk[chunks.size()];
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            LogChunk c = chunks.get(i);
            String sha = sha256(c.content());
            if (sha.equals(stored.get(c.chunkId()))) {
                result[i] = new EmbeddedChunk(c, sha, null);
                skipped.increment();
            } else {
                result[i] = new EmbeddedChunk(c, sha, null);
                pending.add(i);
            }
        }

        for (int from = 0; from < pending.size(); from += props.batchSize()) {
            List<Integer> batch = pending.subList(from, Math.min(from + props.batchSize(), pending.size()));
            List<String> texts = batch.stream().map(i -> chunks.get(i).content()).toList();
            List<float[]> vectors = embedBatch(texts);
            for (int j = 0; j < batch.size(); j++) {
                int idx = batch.get(j);
                result[idx] = new EmbeddedChunk(chunks.get(idx), result[idx].contentSha256(), vectors.get(j));
            }
            generated.increment(batch.size());
        }
        return List.of(result);
    }

    /** Embeds raw (already sanitized) texts in configured batches; used by runbook ingestion. */
    @Override
    public List<float[]> embedTexts(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += props.batchSize()) {
            out.addAll(embedBatch(texts.subList(from, Math.min(from + props.batchSize(), texts.size()))));
        }
        generated.increment(texts.size());
        return out;
    }

    private List<float[]> embedBatch(List<String> texts) {
        Future<List<float[]>> future = executor.submit(() -> latency.recordCallable(() -> model.embed(texts)));
        List<float[]> vectors;
        try {
            vectors = future.get(props.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            failures.increment(texts.size());
            throw new EmbeddingUnavailableException("Embedding model timed out", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new EmbeddingUnavailableException("Interrupted while embedding", e);
        } catch (ExecutionException e) {
            failures.increment(texts.size());
            throw new EmbeddingUnavailableException("Embedding model call failed", e.getCause());
        } catch (java.util.concurrent.RejectedExecutionException e) {
            failures.increment(texts.size());
            throw new EmbeddingUnavailableException("Embedding executor saturated", e);
        }
        validate(vectors, texts.size());
        return vectors;
    }

    private void validate(List<float[]> vectors, int expected) {
        if (vectors == null || vectors.size() != expected) {
            failures.increment(expected);
            throw new InvalidEmbeddingException("Embedding model returned an unexpected number of vectors");
        }
        for (float[] v : vectors) {
            if (v == null || v.length != props.dimensions()) {
                failures.increment(expected);
                throw new InvalidEmbeddingException("Embedding dimension mismatch: expected " + props.dimensions()
                        + " but got " + (v == null ? "null" : v.length));
            }
            for (float f : v) {
                if (!Float.isFinite(f)) {
                    failures.increment(expected);
                    throw new InvalidEmbeddingException("Embedding contains non-finite values");
                }
            }
        }
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package com.example.rca.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Spring AI adapter for embeddings; keeps repeated RCA-query embeddings in a small bounded cache. */
@Service
public class SpringAiEmbeddingService implements EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(SpringAiEmbeddingService.class);
    private final EmbeddingModel model;
    private final String modelName;
    private final int dimensions;
    private final String queryPrefix;
    private final String documentPrefix;
    private final Map<String, float[]> queryCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) { return size() > 32; }
            });

    public SpringAiEmbeddingService(EmbeddingModel model,
                                    @Value("${spring.ai.ollama.embedding.model:rca-nomic-embed-v1.5}") String modelName,
                                    @Value("${rca.vector.embedding-dimensions:768}") int dimensions,
                                    @Value("${rca.vector.query-prefix:search_query: }") String queryPrefix,
                                    @Value("${rca.vector.document-prefix:search_document: }") String documentPrefix) {
        this.model = model;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.queryPrefix = queryPrefix;
        this.documentPrefix = documentPrefix;
        if (dimensions < 1) throw new IllegalStateException("The configured embedding model returned an invalid vector dimension");
        log.info("Embedding service configured: model={} dimensions={} startupWarmup=false queryPrefix={} documentPrefix={}",
                modelName, dimensions, !queryPrefix.isEmpty(), !documentPrefix.isEmpty());
    }

    @Override public String modelName() { return modelName; }
    @Override public int dimensions() { return dimensions; }

    @Override
    public float[] embedQuery(String text) {
        String input = queryPrefix + Objects.toString(text, " ");
        String key = cacheKey(input);
        float[] cached = queryCache.get(key);
        if (cached != null) {
            log.info("Query embedding cache hit: model={} dimensions={} cacheEntries={}", modelName, dimensions, queryCache.size());
            return cached;
        }
        long started = System.nanoTime();
        float[] vector = embed(input);
        queryCache.put(key, vector);
        log.info("Query embedding generated: model={} textChars={} elapsedMs={} cacheEntries={}", modelName,
                text == null ? 0 : text.length(), (System.nanoTime() - started) / 1_000_000, queryCache.size());
        return vector;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        long started = System.nanoTime();
        try {
            List<String> inputs = texts.stream().map(text -> documentPrefix + Objects.toString(text, " ")).toList();
            List<float[]> result = model.embed(inputs);
            if (result.size() != texts.size())
                throw new IllegalStateException("Embedding model returned " + result.size() + " vectors for " + texts.size() + " inputs");
            for (float[] vector : result) validate(vector);
            log.info("Embedding batch generated: model={} inputs={} elapsedMs={}", modelName, texts.size(),
                    (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (Exception e) {
            log.error("Embedding batch failed: model={} inputs={} elapsedMs={}", modelName, texts.size(),
                    (System.nanoTime() - started) / 1_000_000, e);
            throw new IllegalStateException("Could not create embeddings from the configured local embedding model", e);
        }
    }

    private float[] embed(String text) {
        long started = System.nanoTime();
        try {
            float[] vector = model.embed(text == null || text.isBlank() ? " " : text);
            validate(vector);
            log.info("Embedding generated: model={} textChars={} elapsedMs={}", modelName, text == null ? 0 : text.length(),
                    (System.nanoTime() - started) / 1_000_000);
            return vector;
        } catch (Exception e) {
            log.error("Embedding failed: model={} textChars={} elapsedMs={}", modelName, text == null ? 0 : text.length(),
                    (System.nanoTime() - started) / 1_000_000, e);
            throw new IllegalStateException("Could not create an embedding from the configured local embedding model", e);
        }
    }

    private void validate(float[] vector) {
        if (vector == null || vector.length != dimensions)
            throw new IllegalStateException("Embedding dimension mismatch: expected " + dimensions + " but received " +
                    (vector == null ? "null" : vector.length));
    }

    private static String cacheKey(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create query embedding cache key", e);
        }
    }
}

package com.example.rca.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.DriverManager;
import java.util.List;

/** Persistent cosine retrieval using PostgreSQL pgvector and a local Spring AI embedding model. */
@Component
public class PgVectorStore implements VectorStore {
    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String embeddingModelName;
    private final String tableName;
    private final int dimensions;
    private final int embeddingBatchSize;
    private final EmbeddingService embeddings;

    public PgVectorStore(@Value("${rca.vector.jdbc-url:jdbc:postgresql://localhost:5432/rca}") String jdbcUrl,
                         @Value("${rca.vector.username:rca}") String username,
                         @Value("${rca.vector.password:rca}") String password,
                         @Value("${spring.ai.ollama.embedding.model:rca-nomic-embed-v1.5}") String embeddingModelName,
                         @Value("${rca.vector.embedding-batch-size:16}") int embeddingBatchSize,
                         EmbeddingService embeddings) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.embeddingModelName = embeddingModelName;
        this.embeddingBatchSize = Math.max(1, embeddingBatchSize);
        this.embeddings = embeddings;
        this.dimensions = embeddings.dimensions();
        if (dimensions < 1) throw new IllegalStateException("The configured Ollama embedding model returned an invalid vector dimension");
        String modelSuffix = embeddingModelName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        this.tableName = "rca_vectors_" + dimensions + "_" + modelSuffix;
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (id text PRIMARY KEY, content text NOT NULL, embedding vector(" + dimensions + "))");
            statement.execute("CREATE INDEX IF NOT EXISTS " + tableName + "_hnsw_idx ON " + tableName + " USING hnsw (embedding vector_cosine_ops)");
            log.info("pgvector initialized: table={} dimensions={} embeddingModel={}", tableName, dimensions, embeddingModelName);
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize mandatory pgvector store: " + e.getMessage(), e);
        }
    }

    @Override
    public void upsert(String id, String text) {
        upsertAll(List.of(new Entry(id, text)));
    }

    @Override
    public void upsertAll(List<Entry> entries) {
        if (entries.isEmpty()) return;
        String sql = "INSERT INTO " + tableName + " (id,content,embedding) VALUES(?,?,?::vector) " +
                "ON CONFLICT(id) DO UPDATE SET content=EXCLUDED.content,embedding=EXCLUDED.embedding";
        int completed = 0;
        for (int offset = 0; offset < entries.size(); offset += embeddingBatchSize) {
            long batchStarted = System.nanoTime();
            var batch = entries.subList(offset, Math.min(offset + embeddingBatchSize, entries.size()));
            var inputs = batch.stream().map(entry -> clip(entry.text())).toList();
            List<float[]> vectors;
            try {
                vectors = embeddings.embedBatch(inputs);
            } catch (Exception e) {
                throw new IllegalStateException("Could not create local embeddings for batch starting at " + completed +
                        "; verify that Ollama and its configured embedding model are available", e);
            }
            try (var connection = connection(); var statement = connection.prepareStatement(sql)) {
                connection.setAutoCommit(false);
                for (int i = 0; i < batch.size(); i++) {
                    float[] vector = vectors.get(i);
                    if (vector.length != dimensions) {
                        throw new IllegalStateException("Embedding dimension mismatch: expected " + dimensions + " but received " + vector.length);
                    }
                    var entry = batch.get(i);
                    statement.setString(1, entry.id());
                    statement.setString(2, entry.text());
                    statement.setString(3, vectorText(vector));
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (Exception e) {
                throw new IllegalStateException("Could not write pgvector batch starting at " + completed, e);
            }
            completed += batch.size();
            log.info("Vector sync progress: completed={} total={} embeddingModel={} batchMs={}", completed, entries.size(),
                    embeddingModelName, (System.nanoTime() - batchStarted) / 1_000_000);
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        try (var connection = connection(); var statement = connection.prepareStatement(
                "DELETE FROM " + tableName + " WHERE id LIKE ? ESCAPE '\\'")) {
            statement.setString(1, escapeLike(prefix) + "%");
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Could not delete pgvector documents", e);
        }
    }

    @Override
    public List<Match> search(String query, int limit) {
        long searchStarted = System.nanoTime();
        float[] vector = embeddings.embedQuery(query);
        long embeddingMs = (System.nanoTime() - searchStarted) / 1_000_000;
        String sql = "SELECT id,content,1-(embedding <=> ?::vector) AS score FROM " + tableName +
                " ORDER BY embedding <=> ?::vector LIMIT ?";
        long databaseStarted = System.nanoTime();
        try (var connection = connection(); var statement = connection.prepareStatement(sql)) {
            String value = vectorText(vector);
            statement.setString(1, value);
            statement.setString(2, value);
            statement.setInt(3, limit);
            try (var results = statement.executeQuery()) {
                var matches = new java.util.ArrayList<Match>();
                while (results.next()) matches.add(new Match(results.getString(1), results.getString(2), results.getDouble(3)));
                log.info("Vector search complete: limit={} matches={} embeddingMs={} databaseMs={} totalMs={}", limit,
                        matches.size(), embeddingMs, (System.nanoTime() - databaseStarted) / 1_000_000,
                        (System.nanoTime() - searchStarted) / 1_000_000);
                return List.copyOf(matches);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not search pgvector", e);
        }
    }

    private static String clip(String input) {
        if (input == null || input.isBlank()) return " ";
        return input.length() > 20000 ? input.substring(0, 20000) : input;
    }

    private java.sql.Connection connection() throws java.sql.SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static String vectorText(float[] vector) {
        var value = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) value.append(',');
            value.append(vector[i]);
        }
        return value.append(']').toString();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

package com.example.rca.service;

import java.util.List;

public interface VectorStore {
    void upsert(String id, String text);
    default void upsertAll(List<Entry> entries) {
        for (Entry entry : entries) upsert(entry.id(), entry.text());
    }
    List<Match> search(String query, int limit);
    default void deletePrefix(String prefix) {}
    record Entry(String id, String text) {}
    record Match(String id, String text, double score) {}
}

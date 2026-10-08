package com.example.rca.service;

import java.util.List;
import java.util.Set;

public interface VectorStore {
    void upsert(String id, String text);
    default void upsertAll(List<Entry> entries) {
        for (Entry entry : entries) upsert(entry.id(), entry.text());
    }
    List<Match> search(String query, int limit);
    default List<Match> search(String query, int limit, String namespace) {
        return search(query, limit).stream().filter(match -> match.id().startsWith(namespace + ":")).toList();
    }
    default Set<String> idsForNamespace(String namespace) { return Set.of(); }
    default void deletePrefix(String prefix) {}
    record Entry(String id, String text) {}
    record Match(String id, String text, double score) {}
}

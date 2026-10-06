package com.example.rca.service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory lexical store retained for isolated unit tests; production uses mandatory PgVectorStore. */
public class HashVectorStore implements VectorStore {
    private final Map<String, String> documents = new ConcurrentHashMap<>();
    @Override public void upsert(String id, String text) { documents.put(id, text); }
    @Override public void deletePrefix(String prefix) { documents.keySet().removeIf(id->id.startsWith(prefix)); }
    @Override public List<Match> search(String query, int limit) {
        var q = vector(query);
        return documents.entrySet().stream().map(e -> new Match(e.getKey(), e.getValue(), cosine(q, vector(e.getValue()))))
                .filter(m -> m.score() > 0).sorted(Comparator.comparingDouble(Match::score).reversed()).limit(limit).toList();
    }
    private static Map<String, Integer> vector(String text) {
        var counts = new HashMap<String, Integer>();
        for (var token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_.$]+")) if (token.length() > 1) counts.merge(token, 1, Integer::sum);
        return counts;
    }
    private static double cosine(Map<String,Integer> a, Map<String,Integer> b) {
        double dot=0, aa=0, bb=0;
        for (var v:a.values()) aa+=v*v;
        for (var v:b.values()) bb+=v*v;
        for (var e:a.entrySet()) dot+=e.getValue()*b.getOrDefault(e.getKey(),0);
        return aa==0||bb==0 ? 0 : dot/Math.sqrt(aa*bb);
    }
}

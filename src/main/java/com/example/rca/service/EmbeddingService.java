package com.example.rca.service;

import java.util.List;

/** Provider-neutral embedding boundary used by vector persistence and RCA retrieval. */
public interface EmbeddingService {
    String modelName();
    int dimensions();
    float[] embedQuery(String text);
    List<float[]> embedBatch(List<String> texts);
}

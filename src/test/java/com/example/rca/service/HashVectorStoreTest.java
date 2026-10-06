package com.example.rca.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HashVectorStoreTest {
    @Test void retrievesTextWithSharedTerms() {
        var store=new HashVectorStore(); store.upsert("a","Optional owner lookup missing entity"); store.upsert("b","unrelated networking timeout");
        assertEquals("a",store.search("missing owner Optional",1).get(0).id());
    }
}

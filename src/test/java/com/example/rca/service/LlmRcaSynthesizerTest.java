package com.example.rca.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmRcaSynthesizerTest {
    @Test void extractsObjectFromMarkdownFenceAndPreamble() {
        assertEquals("{\"description\":\"text with } brace\"}",
                LlmRcaSynthesizer.extractJson("Result:\n```json\n{\"description\":\"text with } brace\"}\n```"));
    }

    @Test void rejectsTruncatedJsonInsteadOfPassingItToJackson() {
        assertThrows(IllegalArgumentException.class, () -> LlmRcaSynthesizer.extractJson("{\"description\":"));
    }

    @Test void rejectsMalformedUnifiedDiffHunkCounts() {
        String malformed = "diff --git a/Example.java b/Example.java\n" +
                "--- a/Example.java\n+++ b/Example.java\n" +
                "@@ -10,1 +10,3 @@\n" +
                "-        return total / itemCount;\n" +
                "+        if (itemCount == 0) return 0;\n" +
                "         return total / itemCount;\n";
        assertEquals(false, LlmRcaSynthesizer.hasValidHunks(malformed));
    }

    @Test void acceptsWellFormedUnifiedDiffHunkCounts() {
        String valid = "diff --git a/Example.java b/Example.java\n" +
                "--- a/Example.java\n+++ b/Example.java\n" +
                "@@ -10,1 +10,2 @@\n" +
                "-        return total / itemCount;\n" +
                "+        if (itemCount == 0) return 0;\n" +
                "+        return total / itemCount;\n";
        assertEquals(true, LlmRcaSynthesizer.hasValidHunks(valid));
    }
}

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
}

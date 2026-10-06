package com.example.rca.parser;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class JavaAstParserTest {
    @Test void extractsTypeAndMethodCallNames() {
        var types=new JavaAstParser().parse(Path.of("Demo.java"),"Demo.java","package demo; class Demo { int run(){ return helper(); } int helper(){ return 1; } }");
        assertEquals("Demo",types.get(0).name());
        assertEquals("run",types.get(0).methods().get(0).name());
        assertTrue(types.get(0).methods().get(0).calls().contains("helper"));
    }
}

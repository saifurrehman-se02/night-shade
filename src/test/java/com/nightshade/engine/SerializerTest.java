package com.nightshade.engine;

import com.nightshade.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SerializerTest {

    @Test
    void applyMappingSkipsStringsCommentsAndExternalDotCalls() {
        List<String> lines = List.of(
            "public class Test {",
            "  void run() {",
            "    String s = \"count\"; int count = 1; // count",
            "    externalLib.process();",
            "  }",
            "}"
        );
        SourceFile source = new SourceFile("Test.java", lines);
        // Only "count" is in the mapping — externalLib.process() is NOT our method
        Map<String, String> mapping = Map.of("count", "v_aaaaaaa");

        Serializer serializer = new Serializer();
        List<String> out = serializer.applyMapping(source, mapping);
        String joined = String.join("\n", out);

        assertAll("applyMapping invariants",
            () -> assertTrue(joined.contains("\"count\""), "Strings should not be renamed"),
            () -> assertTrue(joined.contains("// count"), "Comments should not be renamed"),
            () -> assertTrue(joined.contains("int v_aaaaaaa = 1"), "Identifiers should be renamed"),
            () -> assertTrue(joined.contains("externalLib.process()"), "External dot calls not in mapping should not be renamed")
        );
    }

    @Test
    void applyMappingRenamesOwnMethodsAfterDot() {
        List<String> lines = List.of(
            "public class Test {",
            "  void compute(int n) {",
            "    Test t = new Test();",
            "    t.compute(10);",
            "  }",
            "}"
        );
        SourceFile source = new SourceFile("Test.java", lines);
        // "compute" IS in the mapping — it's our own method
        Map<String, String> mapping = Map.of("compute", "v_xsjumvk");

        Serializer serializer = new Serializer();
        List<String> out = serializer.applyMapping(source, mapping);
        String joined = String.join("\n", out);

        assertAll("own method renaming",
            () -> assertTrue(joined.contains("void v_xsjumvk(int n)"), "Method declaration should be renamed"),
            () -> assertTrue(joined.contains("t.v_xsjumvk(10)"), "Own method call should be renamed")
        );
    }
}

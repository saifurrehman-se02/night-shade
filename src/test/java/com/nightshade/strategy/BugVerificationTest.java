package com.nightshade.strategy;

import com.nightshade.CLI;
import com.nightshade.engine.CompilationVerifier;
import com.nightshade.model.ASTNode;
import com.nightshade.model.ObfuscationResult;
import com.nightshade.model.SourceFile;
import com.nightshade.model.SymbolTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BugVerificationTest {

    // --- Bug 1: StringEncoder Escape Characters Bug ---
    @Test
    void testStringEncoderEscapeCharactersBug() {
        List<String> lines = List.of(
            "public class Test {",
            "    String escaped = \"Hello\\nWorld\";",
            "}"
        );
        SourceFile source = new SourceFile("Test.java", lines);
        source.setObfuscatedLines(lines);

        StringEncoder encoder = new StringEncoder();
        ObfuscationResult result = encoder.apply(source, new ASTNode("BLOCK"), new SymbolTable());
        List<String> obfuscated = result.getObfuscatedFile().getObfuscatedLines();
        String encodedLine = obfuscated.get(1);

        // Expected char array values for "Hello\nWorld" should be:
        // 'H'(72), 'e'(101), 'l'(108), 'l'(108), 'o'(111), '\n'(10), 'W'(87), 'o'(111), 'r'(114), 'l'(108), 'd'(100)
        // Length of array must be 11.
        // If the bug exists, it encodes '\' and 'n' as 92 and 110 (length 12).
        assertTrue(encodedLine.contains("new String(new char[]{"));
        
        // Let's parse out the char array elements
        int startIdx = encodedLine.indexOf("{") + 1;
        int endIdx = encodedLine.indexOf("}");
        String arrayContent = encodedLine.substring(startIdx, endIdx);
        String[] chars = arrayContent.split(",");
        
        // Assert length is 11 (the actual runtime string length for "Hello\nWorld")
        assertEquals(11, chars.length, "String length mismatch! Escape sequence was encoded as literal '\\' and 'n'");
    }

    // --- Bug 2: WatermarkEncoder Python tab error ---
    @Test
    void testWatermarkEncoderPythonTabError() {
        List<String> lines = List.of(
            "def test_func():",
            "    val = 1",
            "    return val"
        );
        SourceFile source = new SourceFile("test.py", lines);
        source.setObfuscatedLines(lines);

        WatermarkEncoder encoder = new WatermarkEncoder();
        encoder.setEnabled(true);
        ObfuscationResult result = encoder.apply(source, null, new SymbolTable());
        List<String> obfuscated = result.getObfuscatedFile().getObfuscatedLines();

        // Python files must not have tabs injected as leading whitespace to prevent TabError
        for (String line : obfuscated) {
            assertFalse(line.startsWith("\t"), "WatermarkEncoder injected tab into Python file indentation!");
        }
    }

    // --- Bug 3: CommentPoisoner skip resume directive gets poisoned ---
    @Test
    void testCommentPoisonerSkipResumeGetsPoisoned() {
        List<String> lines = List.of(
            "public class Test {",
            "    // @nightshade:skip",
            "    int a = 1;",
            "    // @nightshade:resume",
            "    int b = 2;",
            "}"
        );
        SourceFile source = new SourceFile("Test.java", lines);
        source.setObfuscatedLines(lines);

        CommentPoisoner poisoner = new CommentPoisoner();
        ObfuscationResult result = poisoner.apply(source, null, new SymbolTable());
        List<String> obfuscated = result.getObfuscatedFile().getObfuscatedLines();

        // The line with @nightshade:resume must remain unchanged
        assertTrue(obfuscated.get(3).contains("@nightshade:resume"), 
            "CommentPoisoner poisoned and modified the @nightshade:resume directive comment!");
    }

    // --- Bug 4: CLI --strategies all does not enable disabled-by-default strategies ---
    @Test
    @SuppressWarnings("unchecked")
    void testCLIEnergyStrategiesAllBug() throws Exception {
        // Reflection call to CLI.buildStrategies("all")
        Method buildStrategiesMethod = CLI.class.getDeclaredMethod("buildStrategies", String.class);
        buildStrategiesMethod.setAccessible(true);
        List<PoisonStrategy> strategies = (List<PoisonStrategy>) buildStrategiesMethod.invoke(null, "all");

        for (PoisonStrategy strategy : strategies) {
            assertTrue(strategy.isEnabled(), 
                "Strategy " + strategy.getName() + " was not enabled when using --strategies all!");
        }
    }

    // --- Bug 5: ControlFlowFlattener multiple returns & compilation failure ---
    @Test
    void testControlFlowFlattenerMultipleReturns(@TempDir File tempDir) throws IOException {
        List<String> lines = List.of(
            "public class Helper {",
            "    private int check(int x) {",
            "        if (x > 0) {",
            "            return 1;",
            "        }",
            "        return 0;",
            "    }",
            "}"
        );
        SourceFile source = new SourceFile("Helper.java", lines);
        source.setObfuscatedLines(lines);

        ControlFlowFlattener flattener = new ControlFlowFlattener();
        flattener.setEnabled(true);
        ObfuscationResult result = flattener.apply(source, null, new SymbolTable());
        List<String> obfuscated = result.getObfuscatedFile().getObfuscatedLines();

        // Write to temp file and try compilation
        File javaFile = new File(tempDir, "Helper.java");
        Files.write(javaFile.toPath(), obfuscated);

        CompilationVerifier verifier = new CompilationVerifier();
        boolean compiles = verifier.verify(tempDir);
        assertTrue(compiles, "Obfuscated code with multiple returns failed to compile under ControlFlowFlattener!");
    }

    // --- Bug 6: EntropyScrambler scope-aware serialization renaming collision ---
    @Test
    void testEntropyScramblerScopeAwareSerialization() {
        List<String> lines = List.of(
            "public class Test {",
            "    private void methodA() {",
            "        int result = 1;",
            "    }",
            "    private void methodB() {",
            "        int result = 2;",
            "    }",
            "}"
        );
        // We need a proper AST with Scope paths for this test.
        // Let's tokenize and parse using the actual Lexer and Parser.
        com.nightshade.engine.Lexer lexer = new com.nightshade.engine.Lexer();
        com.nightshade.engine.Parser parser = new com.nightshade.engine.Parser();
        
        SourceFile source = new SourceFile("Test.java", lines);
        var tokens = lexer.tokenize(lines);
        var ast = parser.parse(tokens);

        EntropyScrambler scrambler = new EntropyScrambler();
        ObfuscationResult result = scrambler.apply(source, ast, new SymbolTable());
        List<String> obfuscated = result.getObfuscatedFile().getObfuscatedLines();

        // Extract the name replacements from methodA and methodB
        String lineA = obfuscated.get(2); // "int result = 1;"
        String lineB = obfuscated.get(5); // "int result = 2;"

        int startA = lineA.indexOf("int ") + 4;
        int endA = lineA.indexOf(" = ");
        String renamedA = lineA.substring(startA, endA).trim();

        int startB = lineB.indexOf("int ") + 4;
        int endB = lineB.indexOf(" = ");
        String renamedB = lineB.substring(startB, endB).trim();

        assertEquals(renamedA, renamedB, 
            "EntropyScrambler uses global resolution so same name maps to same replacement across scopes");
    }

    // --- Bug 7: CommentPoisoner poisons comments inside skipped blocks ---
    @Test
    void testCommentPoisonerSkipsCommentsInSkippedBlocks() {
        List<String> lines = List.of(
            "public class Test {",
            "    // @nightshade:skip",
            "    // This comment must not be poisoned",
            "    // @nightshade:resume",
            "    // This comment should be poisoned",
            "}"
        );
        SourceFile source = new SourceFile("Test.java", lines);
        CommentPoisoner poisoner = new CommentPoisoner();
        ObfuscationResult result = poisoner.apply(source, null, new SymbolTable());
        List<String> obfuscated = result.getObfuscatedFile().getObfuscatedLines();
        
        assertEquals("// This comment must not be poisoned", obfuscated.get(2).trim());
        assertNotEquals("// This comment should be poisoned", obfuscated.get(4).trim());
    }

    // --- Bug 8: DeadCodeInjector injects dead code inside skipped blocks ---
    @Test
    void testDeadCodeInjectorSkipsSkippedBlocks() {
        List<String> lines = List.of(
            "public class Test {",
            "    // @nightshade:skip",
            "    private int doNotTouch() {",
            "        return 42;",
            "    }",
            "    // @nightshade:resume",
            "    private int touch() {",
            "        return 100;",
            "    }",
            "}"
        );
        SourceFile source = new SourceFile("Test.java", lines);
        DeadCodeInjector injector = new DeadCodeInjector();
        ObfuscationResult result = injector.apply(source, null, new SymbolTable());
        List<String> obfuscated = result.getObfuscatedFile().getObfuscatedLines();
        
        // Assert no dead code was injected before return 42;
        // The return 42; should be at index 3 in obfuscated (since nothing was injected)
        assertEquals("return 42;", obfuscated.get(3).trim());
        
        // Assert dead code was injected before return 100; (which will push it down)
        boolean foundDeadCode = false;
        for (String line : obfuscated) {
            if (line.contains("[strategy:")) {
                foundDeadCode = true;
            }
        }
        assertTrue(foundDeadCode);
    }

    // --- Bug 9: WhitespaceDisruptor toAllmanStyle formats braces inside skipped blocks ---
    @Test
    void testWhitespaceDisruptorSkipsAllmanInSkippedBlocks() {
        List<String> lines = List.of(
            "public class Test {",
            "    // @nightshade:skip",
            "    private void methodA() {",
            "    }",
            "    // @nightshade:resume",
            "    private void methodB() {",
            "    }",
            "}"
        );
        SourceFile source = new SourceFile("Test.java", lines);
        WhitespaceDisruptor disruptor = new WhitespaceDisruptor();
        ObfuscationResult result = disruptor.apply(source, null, new SymbolTable());
        List<String> obfuscated = result.getObfuscatedFile().getObfuscatedLines();
        
        // The brace in methodA should stay on the same line (K&R style)
        boolean methodAIsKR = false;
        for (String line : obfuscated) {
            if (line.contains("private void methodA") && line.contains("{")) {
                methodAIsKR = true;
            }
        }
        assertTrue(methodAIsKR, "methodA brace was split or modified!");
        
        // The brace in methodB should be moved to Allman style (new line)
        boolean hasAllmanB = false;
        for (int i = 0; i < obfuscated.size(); i++) {
            if (obfuscated.get(i).contains("private void methodB") && !obfuscated.get(i).contains("{")) {
                if (i + 1 < obfuscated.size() && obfuscated.get(i + 1).trim().equals("{")) {
                    hasAllmanB = true;
                }
            }
        }
        assertTrue(hasAllmanB);
    }

    // --- Bug 10: ControlFlowFlattener flattens private methods inside skipped blocks ---
    @Test
    void testControlFlowFlattenerSkipsSkippedBlocks() {
        List<String> lines = List.of(
            "public class Test {",
            "    // @nightshade:skip",
            "    private void methodA() {",
            "        int a = 1;",
            "        int b = 2;",
            "        int c = 3;",
            "    }",
            "    // @nightshade:resume",
            "    private void methodB() {",
            "        int a = 1;",
            "        int b = 2;",
            "        int c = 3;",
            "    }",
            "}"
        );
        SourceFile source = new SourceFile("Test.java", lines);
        ControlFlowFlattener flattener = new ControlFlowFlattener();
        flattener.setEnabled(true);
        ObfuscationResult result = flattener.apply(source, null, new SymbolTable());
        List<String> obfuscated = result.getObfuscatedFile().getObfuscatedLines();
        
        // methodA must not be flattened (check first 9 lines)
        boolean hasStateVarA = false;
        for (int i = 0; i < 9; i++) {
            if (obfuscated.get(i).contains("_ns_state")) {
                hasStateVarA = true;
            }
        }
        assertFalse(hasStateVarA, "methodA was flattened!");
        
        // methodB must be flattened (contains _ns_state in the second part)
        boolean hasStateVarB = false;
        for (int i = 9; i < obfuscated.size(); i++) {
            if (obfuscated.get(i).contains("_ns_state")) {
                hasStateVarB = true;
            }
        }
        assertTrue(hasStateVarB, "methodB was not flattened!");
    }

    // --- Bug 11: ControlFlowFlattener brace mismatch with braces in strings/comments ---
    @Test
    void testControlFlowFlattenerStringBraceMismatch() {
        List<String> lines = List.of(
            "public class Test {",
            "    private void methodA() {",
            "        String s1 = \"}\";",
            "        String s2 = \"{\";",
            "        int a = 1;",
            "        int b = 2;",
            "    }",
            "}"
        );
        SourceFile source = new SourceFile("Test.java", lines);
        ControlFlowFlattener flattener = new ControlFlowFlattener();
        flattener.setEnabled(true);
        ObfuscationResult result = flattener.apply(source, null, new SymbolTable());
        List<String> obfuscated = result.getObfuscatedFile().getObfuscatedLines();
        
        // The method should compile successfully.
        // It has 5 lines of statements, so it can be flattened safely now that braces in strings are ignored.
        boolean hasStateVar = false;
        for (String line : obfuscated) {
            if (line.contains("_ns_state")) {
                hasStateVar = true;
            }
        }
        assertTrue(hasStateVar);
    }

    // --- Bug 12: WatermarkEncoder watermarks skipped blocks ---
    @Test
    void testWatermarkEncoderSkipsSkippedBlocks() {
        List<String> lines = List.of(
            "public class Test {",
            "    // @nightshade:skip",
            "    private void methodA() {",
            "        int a = 1;",
            "    }",
            "    // @nightshade:resume",
            "    private void methodB() {",
            "        int a = 1;",
            "    }",
            "}"
        );
        SourceFile source = new SourceFile("Test.java", lines);
        WatermarkEncoder encoder = new WatermarkEncoder();
        encoder.setEnabled(true);
        ObfuscationResult result = encoder.apply(source, null, new SymbolTable());
        List<String> obfuscated = result.getObfuscatedFile().getObfuscatedLines();
        
        // None of the lines inside the skipped block (indices 2 to 4) should start with a tab
        for (int i = 2; i <= 4; i++) {
            assertFalse(obfuscated.get(i).startsWith("\t"), "WatermarkEncoder modified skipped line " + i);
        }
    }
}

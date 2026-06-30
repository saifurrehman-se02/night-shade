package com.nightshade.engine;

import com.nightshade.model.ObfuscationResult;
import com.nightshade.model.SourceFile;
import com.nightshade.strategy.*;
import com.nightshade.util.LogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilationSafetyTest {

    private ObfuscationEngine engine;

    @BeforeEach
    void setUp() {
        Lexer lexer = new Lexer();
        Parser parser = new Parser();
        Serializer serializer = new Serializer();
        EntropyCalculator entropyCalc = new EntropyCalculator();
        LogService logService = new LogService();

        // Enable ALL strategies
        List<PoisonStrategy> strategies = Arrays.asList(
            new EntropyScrambler(),
            new DeadCodeInjector(),
            new CommentPoisoner(),
            new StringEncoder(),
            new WhitespaceDisruptor(),
            new SemanticInverter(),
            new ControlFlowFlattener(),
            new WatermarkEncoder()
        );

        strategies.forEach(s -> s.setEnabled(true));

        engine = new ObfuscationEngine(
            strategies, lexer, parser, serializer, entropyCalc, logService, 0.99
        );
    }

    @Test
    void testFullPipelineCompilationSafety() throws IOException {
        String testSource = """
            import java.util.List;
            import java.util.stream.Collectors;
            import java.util.Arrays;

            public class ComplexClass {
                private String data;

                public ComplexClass(String data) {
                    this.data = data;
                }

                public List<String> processData(List<Integer> numbers) {
                    // This is a test comment
                    String prefix = "Num: ";
                    return numbers.stream()
                        .filter(n -> n > 0)
                        .map(n -> prefix + n)
                        .collect(Collectors.toList());
                }

                public void loopTest() {
                    for (int i = 0; i < 10; i++) {
                        if (i % 2 == 0) {
                            System.out.println("Even: " + i);
                        } else {
                            System.out.println("Odd: " + i);
                        }
                    }
                }
            }
            """;

        SourceFile file = new SourceFile("src/main/java/com/example/ComplexClass.java", Arrays.asList(testSource.split("\n")));

        List<ObfuscationResult> results = engine.process(List.of(file));
        assertEquals(1, results.size());

        ObfuscationResult result = results.get(0);
        List<String> obfuscatedLines = result.getObfuscatedFile().getObfuscatedLines();

        // Write to temp file
        File tempDir = Files.createTempDirectory("nightshade-test").toFile();
        File sourceFile = new File(tempDir, "ComplexClass.java");

        try (FileWriter writer = new FileWriter(sourceFile)) {
            for (String line : obfuscatedLines) {
                writer.write(line + "\n");
            }
        }

        // Compile using system JavaCompiler with diagnostics
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "JavaCompiler is not available (are you running a JRE instead of JDK?)");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjects(sourceFile);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, null, null, compilationUnits);
            boolean success = task.call();
            if (!success) {
                StringBuilder errors = new StringBuilder("Obfuscated code failed to compile:\n");
                for (var d : diagnostics.getDiagnostics()) {
                    errors.append(d.getMessage(null)).append("\n");
                }
                errors.append("\n--- Obfuscated source ---\n");
                for (int i = 0; i < obfuscatedLines.size(); i++) {
                    errors.append(String.format("%3d: %s%n", i + 1, obfuscatedLines.get(i)));
                }
                errors.append("--- End source ---\n");
                assertTrue(false, errors.toString());
            }
        }
    }
}

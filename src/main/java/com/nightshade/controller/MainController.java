package com.nightshade.controller;

import com.nightshade.engine.*;
import com.nightshade.model.*;
import com.nightshade.strategy.*;
import com.nightshade.util.FileUtil;
import com.nightshade.util.LogService;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Main controller — wires the full UI to the ObfuscationEngine pipeline.
 *
 * Threading contract:
 * - ALL engine work runs in a background Task (never on FX thread).
 * - Platform.runLater() is used only here to update UI from the task.
 * - The progress bar and log list are bound to the LogService's
 * ObservableList which marshals its own updates.
 *
 * OOP: OBSERVER pattern — logView is bound to LogService.getEntries()
 * so any background log() call automatically updates the ListView.
 */
public class MainController implements Initializable {

    // ── FXML Injections ────────────────────────────────────────────────────
    @FXML
    private TextField inputPathField;
    @FXML
    private TextField outputPathField;
    @FXML
    private Button browseInputBtn;
    @FXML
    private Button browseOutputBtn;

    /**
     * TreeView stores canonical absolute paths as item values.
     * A custom cell factory renders only the file name with an icon.
     * This avoids complex path reconstruction and works correctly on
     * all platforms including Windows (which uses backslash separators).
     */
    @FXML
    private TreeView<String> fileTreeView;

    @FXML
    private CheckBox cbEntropy;
    @FXML
    private CheckBox cbDeadCode;
    @FXML
    private CheckBox cbComments;
    @FXML
    private CheckBox cbStrings;
    @FXML
    private CheckBox cbWhitespace;
    @FXML
    private CheckBox cbSemantic;
    @FXML
    private CheckBox cbControlFlow;
    @FXML
    private CheckBox cbWatermark;

    @FXML
    private ProgressBar progressBar;
    @FXML
    private Label entropyLabel;
    @FXML
    private Button runBtn;
    @FXML
    private Label runBtnIcon;
    @FXML
    private Label runBtnText;
    @FXML
    private Label statusLabel;

    private RotateTransition iconRotate;
    private ScaleTransition iconPulse;

    @FXML
    private TextArea sourceView;
    @FXML
    private TextArea poisonedView;

    @FXML
    private HBox statsBar;
    @FXML
    private Label statFiles;
    @FXML
    private Label statRenamed;
    @FXML
    private Label statDead;
    @FXML
    private Label statComments;
    @FXML
    private Label statStrings;
    @FXML
    private Label statEntropy;
    @FXML
    private Label statTime;
    @FXML
    private Button openOutputBtn;
    @FXML
    private Button aboutBtn;

    @FXML
    private ListView<String> logView;

    // ── Internal state ─────────────────────────────────────────────────────
    private final LogService logService = new LogService();
    private volatile List<ObfuscationResult> lastResults = new ArrayList<>();
    private volatile File lastOutputDir;
    private Timeline progressPulse;
    private Task<Void> activeTask;

    // ── Initialization ─────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Bind log view to observable log entries
        logView.setItems(logService.getEntries());

        // Auto-scroll log to bottom on new entries
        logService.getEntries().addListener((javafx.collections.ListChangeListener<String>) c -> {
            Platform.runLater(() -> {
                if (!logView.getItems().isEmpty()) {
                    logView.scrollTo(logView.getItems().size() - 1);
                }
            });
        });

        // Custom log cell factory — color by level
        logView.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(item);
                if (item.contains("[ERROR]"))
                    setStyle("-fx-text-fill: #FF4444;");
                else if (item.contains("[DONE]"))
                    setStyle("-fx-text-fill: #4CAF50;");
                else if (item.contains("[DEBUG]"))
                    setStyle("-fx-text-fill: #555555;");
                else
                    setStyle("-fx-text-fill: #707070;");
            }
        });

        // TreeView stores canonical absolute paths; only display file/dir name with icon.
        // Explicit generic type avoids diamond inference issues in anonymous classes.
        fileTreeView.setCellFactory(tv -> new TreeCell<String>() {
            @Override
            protected void updateItem(String path, boolean empty) {
                super.updateItem(path, empty);
                // Always reset styling for recycled cells
                setStyle("-fx-text-fill: #A5A5B5; -fx-font-size: 11px;");
                if (empty || path == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                    setTooltip(null);
                } else {
                    File f = new File(path);
                    String icon = f.isDirectory()
                            ? String.valueOf(Character.toChars(0x1F4C1))
                            : String.valueOf(Character.toChars(0x1F4C4));
                    setText(icon + " " + f.getName());
                    // Tooltip shows full path on hover — useful when name is truncated
                    Tooltip tip = new Tooltip(f.getAbsolutePath());
                    tip.setShowDelay(javafx.util.Duration.millis(400));
                    setTooltip(tip);
                }
            }
        });

        // Guarantee TextArea text is visible regardless of CSS cascade issues.
        // Inline setStyle() has highest priority and always wins over external CSS.
        sourceView.setStyle(
            "-fx-control-inner-background: #07070B;" +
            "-fx-text-fill: #D0D0DF;" +
            "-fx-font-family: 'Consolas', 'Courier New', monospace;" +
            "-fx-font-size: 12px;"
        );
        poisonedView.setStyle(
            "-fx-control-inner-background: #0F0909;" +
            "-fx-text-fill: #FF9E59;" +
            "-fx-font-family: 'Consolas', 'Courier New', monospace;" +
            "-fx-font-size: 12px;"
        );

        // After the scene is rendered, apply CSS lookup to force the .content
        // region background AND the actual text fill via node-level override.
        // This is the definitive fix for JavaFX TextArea dark theme visibility.
        Platform.runLater(() -> {
            applyTextAreaDarkTheme(sourceView,  "#07070B", "#D0D0DF");
            applyTextAreaDarkTheme(poisonedView, "#0F0909", "#FF9E59");
        });

        // File tree click → load source view.
        // We store absolute paths in the TreeItem values, so File construction is direct.
        fileTreeView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null && newVal.getValue() != null) {
                        loadFileIntoView(new File(newVal.getValue()));
                    }
                });

        logService.log("Nightshade v3.5.0 ready. Select an input directory to begin.");
        logService.log(
                "8 strategies loaded: Entropy, DeadCode, Comments, Strings, Whitespace, Semantic, ControlFlow, Watermark");

        applyHoverAnimations();
    }

    // ── Browse buttons ─────────────────────────────────────────────────────

    @FXML
    private void onBrowseInput() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Input Directory");
        File dir = chooser.showDialog(runBtn.getScene().getWindow());
        if (dir != null) {
            // Set text first (does NOT trigger tree build — no textProperty listener)
            inputPathField.setText(dir.getAbsolutePath());
            String parent = dir.getParent();
            if (parent != null) {
                outputPathField.setText(parent + File.separator + "_nightshade_output");
            } else {
                outputPathField.setText(dir.getAbsolutePath() + File.separator + "_nightshade_output");
            }
            // Build tree exactly once
            buildFileTree(dir);
            logService.log("Input set: " + dir.getAbsolutePath());
        }
    }

    @FXML
    private void onBrowseOutput() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Output Directory");
        File dir = chooser.showDialog(runBtn.getScene().getWindow());
        if (dir != null) {
            outputPathField.setText(dir.getAbsolutePath());
        }
    }

    // ── File Tree ──────────────────────────────────────────────────────────

    /**
     * Builds the file tree rooted at the given directory.
     * Each TreeItem stores the canonical absolute path of the file/dir.
     * Must be called on the FX thread.
     */
    private void buildFileTree(File root) {
        TreeItem<String> rootItem = new TreeItem<>(root.getAbsolutePath());
        rootItem.setExpanded(true);
        addTreeItems(rootItem, root);
        fileTreeView.setRoot(rootItem);
        // Expand all directory nodes so files are immediately visible
        expandAll(rootItem);
        // Clear code views when a new directory is loaded
        sourceView.clear();
        poisonedView.clear();
    }

    /** Recursively expands all TreeItems in the tree. */
    private void expandAll(TreeItem<String> item) {
        if (item == null) return;
        item.setExpanded(true);
        for (TreeItem<String> child : item.getChildren()) {
            expandAll(child);
        }
    }

    private void addTreeItems(TreeItem<String> parent, File dir) {
        File[] files = dir.listFiles();
        if (files == null)
            return;
        Arrays.sort(files, Comparator.comparing(f -> (f.isDirectory() ? "0" : "1") + f.getName().toLowerCase()));
        for (File f : files) {
            String name = f.getName();
            // Skip common generated/vcs directories only
            if (Set.of(".git", "target", "node_modules", "__pycache__", "build").contains(name))
                continue;
            TreeItem<String> item = new TreeItem<>(f.getAbsolutePath());
            if (f.isDirectory()) {
                addTreeItems(item, f);
                // Always add the directory so user can see structure
                parent.getChildren().add(item);
            } else {
                parent.getChildren().add(item);
            }
        }
    }

    /**
     * Loads the given file into the source view and, if available, the poisoned view.
     * Called directly on the FX thread from the selection listener - no Platform.runLater needed.
     */
    private void loadFileIntoView(File file) {
        if (file == null || !file.exists()) {
            logService.logError("File not found: " + (file == null ? "null" : file.getAbsolutePath()));
            sourceView.clear();
            poisonedView.clear();
            return;
        }
        if (file.isDirectory()) {
            // Directory selected - do not clear panels, just ignore
            return;
        }

        logService.log("Loading file: " + file.getName());

        // Read file with explicit UTF-8 encoding (handles all source files safely)
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = br.readLine()) != null) {
                    if (!first) sb.append('\n');
                    sb.append(line);
                    first = false;
                }
            }
            String content = sb.toString();
            logService.log("Loaded " + content.length() + " chars from " + file.getName());

            sourceView.setText(content);
            // Defer scroll reset to after the TextArea completes its layout pass.
            // Calling setScrollTop(0) immediately after setText() is ignored because
            // the layout hasn't processed the new content yet.
            Platform.runLater(() -> {
                sourceView.setScrollTop(0);
                sourceView.setScrollLeft(0);
            });

            // Force dark theme on the TextArea after text is set
            applyTextAreaDarkTheme(sourceView, "#07070B", "#D0D0DF");

        } catch (Exception e) {
            sourceView.clear();
            logService.logError("Could not load source file: " + e.getMessage());
        }

        // Look for matching poisoned result using case-insensitive path comparison
        String selectedCanonical = file.getAbsolutePath();
        boolean poisonedFound = false;
        for (ObfuscationResult r : lastResults) {
            String resultPath = r.getOriginalFile().getAbsolutePath();
            if (selectedCanonical.equalsIgnoreCase(resultPath)) {
                List<String> obfLines = r.getObfuscatedFile().getObfuscatedLines();
                poisonedView.setText(String.join("\n", obfLines));
                poisonedView.setScrollTop(0);
                poisonedView.setScrollLeft(0);
                applyTextAreaDarkTheme(poisonedView, "#0F0909", "#FF9E59");
                poisonedFound = true;
                break;
            }
        }
        if (!poisonedFound) {
            poisonedView.clear();
        }
    }

    /**
     * Definitively applies a dark background and light text to a JavaFX TextArea.
     *
     * JavaFX TextArea is a compound control: the visible text lives inside an inner
     * ".content" Region node, not on the TextArea root itself.  Simply calling
     * setStyle() on the TextArea changes the outer border/background, but the
     * internal content region keeps Modena's defaults (white bg, dark text).
     *
     * The only reliable fix is to:
     *   1. Set -fx-text-fill on the TextArea itself (controls the TextInputControl
     *      property that the skin reads for text color).
     *   2. After applyCss() + layout(), lookup ".content" and set its background.
     *   3. Look up every javafx.scene.text.Text node inside the TextArea and
     *      directly set its fill - this bypasses all CSS specificity issues.
     */
    private void applyTextAreaDarkTheme(TextArea ta, String bgHex, String fgHex) {
        // Step 1: set -fx-text-fill and background on the control itself
        ta.setStyle(
            "-fx-text-fill: " + fgHex + ";" +
            "-fx-control-inner-background: " + bgHex + ";" +
            "-fx-highlight-fill: rgba(255,165,0,0.3);" +
            "-fx-highlight-text-fill: " + fgHex + ";" +
            "-fx-font-family: 'Consolas', 'Courier New', monospace;" +
            "-fx-font-size: 12px;"
        );

        // Step 2: force the .content Region's background
        ta.applyCss();
        ta.layout();
        javafx.scene.Node contentNode = ta.lookup(".content");
        if (contentNode != null) {
            contentNode.setStyle(
                "-fx-background-color: " + bgHex + ";" +
                "-fx-text-fill: " + fgHex + ";"
            );
        }

        // Step 3: find every Text node inside the TextArea and set its fill directly
        // This is the nuclear option that bypasses all CSS cascade issues entirely.
        javafx.scene.paint.Color fgColor = javafx.scene.paint.Color.web(fgHex);
        for (javafx.scene.Node node : ta.lookupAll(".text")) {
            if (node instanceof javafx.scene.text.Text) {
                ((javafx.scene.text.Text) node).setFill(fgColor);
            }
        }
    }


    // ── Run ────────────────────────────────────────────────────────────────

    @FXML
    private void onRunClicked() {
        String inputPath = inputPathField.getText().trim();
        String outputPath = outputPathField.getText().trim();

        if (inputPath.isEmpty()) {
            showAlert("Select Input", "Please select an input directory first.");
            return;
        }
        File inputDir = new File(inputPath);
        if (!inputDir.exists() || !inputDir.isDirectory()) {
            showAlert("Invalid Input", "Input path does not exist: " + inputPath);
            return;
        }
        if (outputPath.isEmpty()) {
            String parent = inputDir.getParent();
            if (parent != null) {
                outputPath = parent + File.separator + "_nightshade_output";
            } else {
                outputPath = inputDir.getAbsolutePath() + File.separator + "_nightshade_output";
            }
            outputPathField.setText(outputPath);
        }
        final File outputDir = new File(outputPath);
        final String finalOutputPath = outputPath;

        // Build strategy list from checkboxes
        List<PoisonStrategy> strategies = buildSelectedStrategies();
        if (strategies.isEmpty()) {
            showAlert("No Strategies", "Please enable at least one strategy.");
            return;
        }

        // Disable UI during run
        setRunning(true);
        logService.clear();
        startProgressPulse();
        startRunBtnAnimation();

        final long startTime = System.currentTimeMillis();

        activeTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                FileWalker walker = new FileWalker();
                List<SourceFile> files = walker.walk(inputDir);

                if (files.isEmpty()) {
                    logService.logError("No .java/.py/.js files found in: " + inputPath);
                    return null;
                }

                Lexer lexer = new Lexer();
                Parser parser = new Parser();
                Serializer serializer = new Serializer();
                EntropyCalculator calc = new EntropyCalculator();
                ObfuscationEngine engine = new ObfuscationEngine(
                        strategies, lexer, parser, serializer, calc, logService, 0.65);

                List<ObfuscationResult> results = engine.process(files, (curr, tot) -> updateProgress(curr, tot));

                // Write output files
                FileUtil fileUtil = new FileUtil();
                for (ObfuscationResult r : results) {
                    fileUtil.write(r, inputDir, outputDir);
                }
                fileUtil.writeRunLog(results, outputDir);

                long elapsed = System.currentTimeMillis() - startTime;
                lastResults = results;
                lastOutputDir = outputDir;

                // Update UI on FX thread
                Platform.runLater(() -> updateStats(results, elapsed));

                return null;
            }
        };

        activeTask.progressProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                double target = newVal.doubleValue();
                if (target >= 0) {
                    Platform.runLater(() -> {
                        Timeline tl = new Timeline(
                            new KeyFrame(Duration.millis(200), new KeyValue(progressBar.progressProperty(), target))
                        );
                        tl.play();
                    });
                }
            }
        });

        activeTask.setOnSucceeded(e -> {
            stopProgressPulse();
            stopRunBtnAnimation();
            setRunning(false);
            statusLabel.setText("Complete ✓");
            statusLabel.setStyle("-fx-text-fill: #4CAF50;");
            progressBar.setProgress(1.0);

            // Refresh currently selected file so poisoned view updates
            TreeItem<String> selectedItem = fileTreeView.getSelectionModel().getSelectedItem();
            if (selectedItem != null && selectedItem.getValue() != null) {
                loadFileIntoView(new File(selectedItem.getValue()));
            }
        });

        activeTask.setOnFailed(e -> {
            stopProgressPulse();
            stopRunBtnAnimation();
            setRunning(false);
            statusLabel.setText("Error ✗");
            statusLabel.setStyle("-fx-text-fill: #FF4444;");
            progressBar.setProgress(0);
            Throwable ex = activeTask.getException();
            logService.logError("Task failed: " + (ex != null ? ex.getMessage() : "Unknown error"));
        });

        Thread thread = new Thread(activeTask);
        thread.setDaemon(true);
        thread.start();
    }

    private List<PoisonStrategy> buildSelectedStrategies() {
        List<PoisonStrategy> list = new ArrayList<>();
        if (cbEntropy.isSelected())
            list.add(new EntropyScrambler());
        if (cbDeadCode.isSelected())
            list.add(new DeadCodeInjector());
        if (cbComments.isSelected())
            list.add(new CommentPoisoner());
        if (cbStrings.isSelected())
            list.add(new StringEncoder());
        if (cbWhitespace.isSelected())
            list.add(new WhitespaceDisruptor());
        if (cbSemantic != null && cbSemantic.isSelected())
            list.add(new SemanticInverter());
        if (cbControlFlow != null && cbControlFlow.isSelected())
            list.add(new ControlFlowFlattener());
        if (cbWatermark != null && cbWatermark.isSelected())
            list.add(new WatermarkEncoder());
        return list;
    }

    private void updateStats(List<ObfuscationResult> results, long elapsed) {
        int totalRenamed = results.stream().mapToInt(ObfuscationResult::getRenamedIdentifiers).sum();
        int totalDead = results.stream().mapToInt(ObfuscationResult::getDeadBlocksInjected).sum();
        int totalComments = results.stream().mapToInt(ObfuscationResult::getCommentsPoisoned).sum();
        int totalStrings = results.stream().mapToInt(ObfuscationResult::getStringsEncoded).sum();
        double avgEntropy = results.stream().mapToDouble(ObfuscationResult::getEntropyScore).average().orElse(0.0);

        statFiles.setText(String.valueOf(results.size()));
        statRenamed.setText(String.valueOf(totalRenamed));
        statDead.setText(String.valueOf(totalDead));
        statComments.setText(String.valueOf(totalComments));
        statStrings.setText(String.valueOf(totalStrings));
        statEntropy.setText(String.format("%.3f", avgEntropy));
        statTime.setText(elapsed + "ms");

        entropyLabel.setText(String.format("Entropy: %.3f", avgEntropy));
        progressBar.setProgress(avgEntropy);

        statsBar.setVisible(true);
        statsBar.setManaged(true);

        // Animate statsBar parent background fade in
        FadeTransition ftBar = new FadeTransition(Duration.millis(200), statsBar);
        ftBar.setFromValue(0.0);
        ftBar.setToValue(1.0);
        ftBar.play();

        // Staggered slide-up + fade-in of metrics cards
        int delay = 0;
        for (Node node : statsBar.getChildren()) {
            if (node.getStyleClass().contains("stat-item-outer")) {
                node.setOpacity(0.0);
                node.setTranslateY(15.0);

                FadeTransition fade = new FadeTransition(Duration.millis(300), node);
                fade.setFromValue(0.0);
                fade.setToValue(1.0);

                TranslateTransition translate = new TranslateTransition(Duration.millis(300), node);
                translate.setFromY(15.0);
                translate.setToY(0.0);

                ParallelTransition pt = new ParallelTransition(fade, translate);
                pt.setDelay(Duration.millis(delay));
                pt.play();

                delay += 70;
            } else if (node == openOutputBtn) {
                node.setOpacity(0.0);
                FadeTransition fadeBtn = new FadeTransition(Duration.millis(300), node);
                fadeBtn.setFromValue(0.0);
                fadeBtn.setToValue(1.0);
                fadeBtn.setDelay(Duration.millis(delay + 100));
                fadeBtn.play();
            }
        }
    }

    // ── Progress Pulse Animation ───────────────────────────────────────────

    private void startProgressPulse() {
        progressBar.setProgress(-1); // indeterminate
        progressPulse = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(progressBar.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(600), new KeyValue(progressBar.opacityProperty(), 0.4)),
                new KeyFrame(Duration.millis(1200), new KeyValue(progressBar.opacityProperty(), 1.0)));
        progressPulse.setCycleCount(Animation.INDEFINITE);
        progressPulse.play();
    }

    private void stopProgressPulse() {
        if (progressPulse != null) {
            progressPulse.stop();
            progressBar.setOpacity(1.0);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void setRunning(boolean running) {
        runBtn.setDisable(running);
        browseInputBtn.setDisable(running);
        browseOutputBtn.setDisable(running);
        cbEntropy.setDisable(running);
        cbDeadCode.setDisable(running);
        cbComments.setDisable(running);
        cbStrings.setDisable(running);
        cbWhitespace.setDisable(running);
        if (cbSemantic != null) cbSemantic.setDisable(running);
        if (cbControlFlow != null) cbControlFlow.setDisable(running);
        if (cbWatermark != null) cbWatermark.setDisable(running);
        if (running) {
            statusLabel.setText("Running...");
            statusLabel.setStyle("-fx-text-fill: #FFA500;");
        }
    }

    @FXML
    private void onClearLog() {
        logService.clear();
    }

    @FXML
    private void onOpenOutput() {
        if (lastOutputDir != null && lastOutputDir.exists()) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(lastOutputDir);
                } else {
                    String os = System.getProperty("os.name").toLowerCase();
                    if (os.contains("win")) {
                        Runtime.getRuntime().exec(new String[]{"explorer.exe", lastOutputDir.getAbsolutePath()});
                    } else if (os.contains("mac")) {
                        Runtime.getRuntime().exec(new String[]{"open", lastOutputDir.getAbsolutePath()});
                    } else {
                        Runtime.getRuntime().exec(new String[]{"xdg-open", lastOutputDir.getAbsolutePath()});
                    }
                }
            } catch (Exception e) {
                logService.logError("Could not open output dir: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onAboutClicked() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About Nightshade v3.5.0");
        alert.setHeaderText("Nightshade — LLM Training Data Poisoning Engine");
        alert.setContentText(
                "Version: 3.5.0\n\n" +
                        "Authors:\n" +
                        "  Ibrahim Salman (25-SE-33)\n" +
                        "  Saif-ur-Rehman (25-SE-05)\n\n" +
                        "Course: OOP Lab — UET Taxila\n\n" +
                        "Research:\n" +
                        "  \u2022 arXiv:2512.15468 — Variable renaming MI disruption\n" +
                        "  \u2022 MinHash+LSH near-dedup bypass (String Encoding)\n" +
                        "  \u2022 BPE tokenizer fingerprint disruption (Whitespace)\n\n" +
                        "Inspired by Nightshade & Glaze (UChicago) —\n" +
                        "first open-source CODE poisoning tool.\n\n" +
                        "MIT License — https://github.com/ibrahim-nightshade/nightshade");
        try {
            alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/com/nightshade/css/nightshade.css").toExternalForm()
            );
            alert.getDialogPane().getStyleClass().add("custom-dialog");
        } catch (Exception ignored) {}
        alert.showAndWait();
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        try {
            alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/com/nightshade/css/nightshade.css").toExternalForm()
            );
            alert.getDialogPane().getStyleClass().add("custom-dialog");
        } catch (Exception ignored) {}
        alert.showAndWait();
    }

    // ── Visual Animations & Interactions ──────────────────────────────────

    private void startRunBtnAnimation() {
        if (runBtnIcon == null) return;

        iconRotate = new RotateTransition(Duration.millis(1000), runBtnIcon);
        iconRotate.setFromAngle(0);
        iconRotate.setToAngle(360);
        iconRotate.setCycleCount(Animation.INDEFINITE);
        iconRotate.setInterpolator(Interpolator.LINEAR);
        iconRotate.play();

        iconPulse = new ScaleTransition(Duration.millis(500), runBtnIcon);
        iconPulse.setFromX(1.0);
        iconPulse.setFromY(1.0);
        iconPulse.setToX(1.25);
        iconPulse.setToY(1.25);
        iconPulse.setAutoReverse(true);
        iconPulse.setCycleCount(Animation.INDEFINITE);
        iconPulse.play();

        if (runBtnText != null) {
            runBtnText.setText("POISONING PIPELINE...");
        }
    }

    private void stopRunBtnAnimation() {
        if (iconRotate != null) {
            iconRotate.stop();
        }
        if (iconPulse != null) {
            iconPulse.stop();
        }
        if (runBtnIcon != null) {
            runBtnIcon.setRotate(0);
            runBtnIcon.setScaleX(1.0);
            runBtnIcon.setScaleY(1.0);
        }
        if (runBtnText != null) {
            runBtnText.setText("RUN NIGHTSHADE");
        }
    }

    private void addHoverScaleAnimation(Node node) {
        if (node == null) return;
        node.setOnMouseEntered(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(120), node);
            st.setToX(1.025);
            st.setToY(1.025);
            st.play();
        });
        node.setOnMouseExited(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(120), node);
            st.setToX(1.0);
            st.setToY(1.0);
            st.play();
        });
    }

    private void applyHoverAnimations() {
        addHoverScaleAnimation(runBtn);
        addHoverScaleAnimation(browseInputBtn);
        addHoverScaleAnimation(browseOutputBtn);
        addHoverScaleAnimation(aboutBtn);
        addHoverScaleAnimation(openOutputBtn);

        for (Node node : statsBar.getChildren()) {
            if (node.getStyleClass().contains("stat-item-outer")) {
                addHoverScaleAnimation(node);
            }
        }
    }
}

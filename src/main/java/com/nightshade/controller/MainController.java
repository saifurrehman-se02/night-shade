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
import java.io.File;
import java.net.URL;
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
        logView.setCellFactory(lv -> new ListCell<>() {
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

        // File tree click → load source view
        fileTreeView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> {
                    if (newVal != null && newVal.isLeaf()) {
                        onFileSelected(newVal.getValue());
                    }
                });

        // Sync scroll between before/after views
        ChangeListener<Number> syncScroll = (obs, ov, nv) -> {
            // intentionally left empty — TextArea scrollbars sync via
            // scroll position binding setup below
        };

        setupScrollSync();

        logService.log("Nightshade v3.5.0 ready. Select an input directory to begin.");
        logService.log(
                "8 strategies loaded: Entropy, DeadCode, Comments, Strings, Whitespace, Semantic, ControlFlow, Watermark");

        applyHoverAnimations();
    }

    private void setupScrollSync() {
        // Sync vertical scroll between before/after views
        sourceView.scrollTopProperty().bindBidirectional(poisonedView.scrollTopProperty());
        // Sync horizontal scroll between before/after views
        sourceView.scrollLeftProperty().bindBidirectional(poisonedView.scrollLeftProperty());
    }

    // ── Browse buttons ─────────────────────────────────────────────────────

    @FXML
    private void onBrowseInput() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Input Directory");
        File dir = chooser.showDialog(runBtn.getScene().getWindow());
        if (dir != null) {
            inputPathField.setText(dir.getAbsolutePath());
            String parent = dir.getParent();
            if (parent != null) {
                outputPathField.setText(parent + File.separator + "_nightshade_output");
            } else {
                outputPathField.setText(dir.getAbsolutePath() + File.separator + "_nightshade_output");
            }
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

    private void buildFileTree(File root) {
        TreeItem<String> rootItem = new TreeItem<>(root.getName());
        rootItem.setExpanded(true);
        addTreeItems(rootItem, root);
        fileTreeView.setRoot(rootItem);
    }

    private void addTreeItems(TreeItem<String> parent, File dir) {
        File[] files = dir.listFiles();
        if (files == null)
            return;
        Arrays.sort(files, Comparator.comparing(f -> (f.isDirectory() ? "0" : "1") + f.getName()));
        for (File f : files) {
            String name = f.getName();
            if (Set.of(".git", "target", "node_modules", "__pycache__", "build").contains(name))
                continue;
            TreeItem<String> item = new TreeItem<>(f.isDirectory() ? "📁 " + name : "📄 " + name);
            if (f.isDirectory()) {
                addTreeItems(item, f);
            }
            parent.getChildren().add(item);
        }
    }

    private void onFileSelected(String displayName) {
        String cleanName = displayName.replace("📄 ", "").replace("📁 ", "");
        String inputDir = inputPathField.getText();
        if (inputDir.isEmpty())
            return;

        // Find the file in the input directory tree
        findAndLoadFile(new File(inputDir), cleanName);
    }

    private void findAndLoadFile(File dir, String filename) {
        File[] files = dir.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            if (f.isDirectory()) {
                findAndLoadFile(f, filename);
            } else if (f.getName().equals(filename)) {
                try {
                    List<String> lines = new java.util.ArrayList<>();
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.FileReader(f))) {
                        String line;
                        while ((line = br.readLine()) != null)
                            lines.add(line);
                    }
                    Platform.runLater(() -> {
                        sourceView.setText(String.join("\n", lines));

                        // If we have results, show the poisoned version too
                        for (ObfuscationResult r : lastResults) {
                            if (r.getOriginalFile().getFileName().equals(filename)) {
                                poisonedView.setText(
                                        String.join("\n", r.getObfuscatedFile().getObfuscatedLines()));
                                break;
                            }
                        }
                    });
                } catch (Exception e) {
                    logService.logError("Could not load file: " + e.getMessage());
                }
                return;
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

        activeTask = new Task<>() {
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

                delay += 70; // Stagger delay
            } else if (node == openOutputBtn) {
                // Fade in open output button at the end of the cards
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
                        "  • arXiv:2512.15468 — Variable renaming MI disruption\n" +
                        "  • MinHash+LSH near-dedup bypass (String Encoding)\n" +
                        "  • BPE tokenizer fingerprint disruption (Whitespace)\n\n" +
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

    // ── Visual Animations & Interactions (Taste Skill) ────────────────────

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

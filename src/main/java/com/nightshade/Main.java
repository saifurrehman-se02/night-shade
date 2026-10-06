package com.nightshade;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/**
 * Nightshade v3.5.0 — LLM Training Data Poisoning Engine
 *
 * Entry point. If CLI args are present, delegates to CLI mode.
 * Otherwise launches the JavaFX GUI.
 *
 * 
 * https://github.com/devhms/nightshade
 */
public class Main extends Application {

    public static final String APP_TITLE = "Nightshade v3.5.0 | Code Obfuscation Engine";
    public static final String APP_VERSION = "3.5.0";

    @Override
    public void start(Stage stage) throws IOException {
        URL fxmlUrl = getClass().getResource("/com/nightshade/fxml/main.fxml");
        if (fxmlUrl == null) {
            throw new IOException("FXML resource not found: /com/nightshade/fxml/main.fxml");
        }
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Scene scene = new Scene(loader.load(), 1280, 800);

        stage.setMaximized(true);
        stage.setResizable(true);
        scene.setFill(javafx.scene.paint.Color.web("#0B0C12"));

        Scale scale = new Scale(1, 1);
        scale.setPivotX(0);
        scale.setPivotY(0);
        ((javafx.scene.control.ScrollPane) scene.getRoot()).getContent().getTransforms().add(scale);

        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown()) {
                if (e.getCode() == KeyCode.EQUALS || e.getCode() == KeyCode.ADD) {
                    scale.setX(scale.getX() * 1.1); scale.setY(scale.getY() * 1.1); e.consume();
                } else if (e.getCode() == KeyCode.MINUS || e.getCode() == KeyCode.SUBTRACT) {
                    scale.setX(scale.getX() / 1.1); scale.setY(scale.getY() / 1.1); e.consume();
                } else if (e.getCode() == KeyCode.DIGIT0 || e.getCode() == KeyCode.NUMPAD0) {
                    scale.setX(1.0); scale.setY(1.0); e.consume();
                }
            }
        });

        // Apply dark terminal theme
        URL cssUrl = getClass().getResource("/com/nightshade/css/nightshade.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        stage.setTitle(APP_TITLE);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setScene(scene);

        // App icon (amber N on dark background — generated at build)
        try {
            Image icon = new Image(
                Objects.requireNonNull(
                    getClass().getResourceAsStream("/com/nightshade/assets/app-icon.png")
                )
            );
            stage.getIcons().add(icon);
        } catch (Exception ignored) {
            // Icon optional — app works fine without it
        }

        stage.show();

        if (screenshotPath != null) {
            PauseTransition pause = new PauseTransition(Duration.millis(800));
            pause.setOnFinished(event -> {
                try {
                    WritableImage snapshot = scene.snapshot(null);
                    File file = new File(screenshotPath);
                    if (file.getParentFile() != null) {
                        file.getParentFile().mkdirs();
                    }
                    ImageIO.write(SwingFXUtils.fromFXImage(snapshot, null), "png", file);
                    System.out.println("[INFO] Screenshot saved to " + file.getAbsolutePath());
                } catch (Exception e) {
                    System.err.println("[ERROR] Failed to save screenshot: " + e.getMessage());
                } finally {
                    Platform.exit();
                    System.exit(0);
                }
            });
            pause.play();
        }
    }

    public static String screenshotPath = null;

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--screenshot")) {
            if (args.length > 1) {
                screenshotPath = args[1];
            } else {
                screenshotPath = "screenshot.png";
            }
            try {
                launch(args);
            } catch (UnsupportedOperationException | NoClassDefFoundError e) {
                System.out.println("[ERROR] Headless environment, cannot take GUI screenshot: " + e.getMessage());
                System.exit(1);
            }
        } else if (args.length > 0) {
            CLI.run(args);
        } else {
            try {
                launch(args);
            } catch (UnsupportedOperationException | NoClassDefFoundError e) {
                System.out.println("[INFO] GUI unavailable (headless environment). Showing CLI help:");
                System.out.println();
                CLI.run(new String[]{"--help"});
            }
        }
    }
}

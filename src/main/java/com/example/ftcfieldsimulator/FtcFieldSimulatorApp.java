package com.example.ftcfieldsimulator;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import com.example.ftcfieldsimulator.UdpPositionListener.CircleData;
import com.example.ftcfieldsimulator.UdpPositionListener.KeyValueData;
import com.example.ftcfieldsimulator.UdpPositionListener.LineData;
import com.example.ftcfieldsimulator.UdpPositionListener.PositionData;
import com.example.ftcfieldsimulator.UdpPositionListener.TextData;
import com.example.ftcfieldsimulator.UdpPositionListener.UdpMessageData;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FtcFieldSimulatorApp extends Application {

    private FieldDisplay fieldDisplay;
    private ControlPanel controlPanel;
    private FieldKeyValueTable keyValueTable;
    private FieldStatusDisplay fieldStatusDisplay;
    private Robot robot;
    private RecordingManager recordingManager;
    private UdpPositionListener udpListener;
    private Thread udpListenerThread;
    private Label instructionLabel;
    private Stage primaryStage;
    private PlotDisplayWindow plotDisplayWindow;
    private UdpPlotListener udpPlotListener;
    private Thread udpPlotListenerThread;
    private List<CurvePoint> currentPath = new ArrayList<>();
    private boolean isCreatingPath = false;
    private Map<String, LineData> namedLinesToDraw = new HashMap<>();
    private final Object namedLinesLock = new Object();

    public static final double FIELD_WIDTH_INCHES = 144.0;
    public static final double FIELD_HEIGHT_INCHES = 144.0;
    private static final int FIELD_DISPLAY_WIDTH_PIXELS = 800;
    private static final int FIELD_DISPLAY_HEIGHT_PIXELS = 800;
    private static final String FIELD_IMAGE_PATH = "/decode_field.png";
    private static final String ROBOT_IMAGE_PATH = "/robot.png";
    public static final double FIELD_IMAGE_ALPHA = 0.3;
    public static final double BACKGROUND_ALPHA = 0.1;
    public static final double ROBOT_START_FIELD_X = 0.0;
    public static final double ROBOT_START_FIELD_Y = 0.0;
    public static final double ROBOT_START_HEADING_DEGREES = 0.0;
    private static final int UDP_LISTENER_PORT = 7777;

    public static void main(String[] args) {
        Application.launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("FTC Field Simulator");

        double controlPanelWidth = FIELD_DISPLAY_WIDTH_PIXELS * ControlPanel.PREFERRED_WIDTH_RATIO_TO_FIELD;
        double rightPanelWidth = controlPanelWidth * 0.9;
        double totalAppWidth = FIELD_DISPLAY_WIDTH_PIXELS + controlPanelWidth + rightPanelWidth;
        double totalAppHeight = FIELD_DISPLAY_HEIGHT_PIXELS;

        this.recordingManager = new RecordingManager(this::processUdpDataAndUpdateUI, this::onPlaybackFinished);
        recordingManager.setOnProgressUpdate(index -> {
            if (controlPanel != null && controlPanel.getTimelineSlider() != null && !controlPanel.getTimelineSlider().isValueChanging()) {
                controlPanel.getTimelineSlider().setValue(index);
            }
            updateTimeLapsedDisplay();
        });

        this.robot = new Robot(ROBOT_START_FIELD_X, ROBOT_START_FIELD_Y, ROBOT_START_HEADING_DEGREES, ROBOT_IMAGE_PATH);
        fieldDisplay = new FieldDisplay(FIELD_DISPLAY_WIDTH_PIXELS, FIELD_DISPLAY_HEIGHT_PIXELS, FIELD_WIDTH_INCHES, FIELD_HEIGHT_INCHES, FIELD_IMAGE_PATH, robot, BACKGROUND_ALPHA, FIELD_IMAGE_ALPHA);
        fieldDisplay.setNamedLinesMap(namedLinesToDraw);
        fieldDisplay.setRobotTextMessage(null);

        controlPanel = new ControlPanel(controlPanelWidth);
        keyValueTable = new FieldKeyValueTable(rightPanelWidth);
        fieldStatusDisplay = new FieldStatusDisplay();
        instructionLabel = new Label("Create a new path or select a point to edit its parameters.");
        instructionLabel.setPadding(new Insets(5));
        instructionLabel.setMaxWidth(Double.MAX_VALUE);
        instructionLabel.setAlignment(Pos.CENTER);
        HBox instructionPane = new HBox(instructionLabel);
        instructionPane.setAlignment(Pos.CENTER);
        instructionPane.setStyle("-fx-background-color: #CFD8DC;");

        VBox rightPanel = new VBox();
        VBox.setVgrow(keyValueTable, Priority.ALWAYS);
        rightPanel.getChildren().addAll(keyValueTable, fieldStatusDisplay);

        BorderPane mainLayout = new BorderPane();
        mainLayout.setLeft(controlPanel);
        mainLayout.setCenter(fieldDisplay);
        mainLayout.setRight(rightPanel);
        mainLayout.setBottom(instructionPane);

        double instructionPaneHeight = 30;
        Scene scene = new Scene(mainLayout, totalAppWidth, totalAppHeight + instructionPaneHeight);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleSceneKeyPress);

        setupControlPanelActions(primaryStage);
        setupRecordingControlActions();

        startUdpPositionListener();
        startUdpPlotListener();
        primaryStage.setScene(scene);
        primaryStage.setResizable(true);
        primaryStage.show();
        primaryStage.setOnCloseRequest(event -> stopApp());

        updateUIFromRobotState();
        updateTimeLapsedDisplay();

        controlPanel.setReplayMode(false);
    }

    private void updateUIFromRobotState() {
        if (robot != null && fieldDisplay != null) {
            double displayHeading = robot.getHeadingDegrees() % 360;
            if (displayHeading < 0) displayHeading += 360;
            if (fieldStatusDisplay != null) {
                fieldStatusDisplay.updateRobotStatus(robot.getXInches(), robot.getYInches(), displayHeading);
            }
            fieldDisplay.drawCurrentState();
        }
    }

    private void showPlotDisplay() {
        if (plotDisplayWindow == null) {
            plotDisplayWindow = new PlotDisplayWindow(primaryStage);
        }
        plotDisplayWindow.show();
    }

    private void handleUdpPlotData(PlotDataEvent dataEvent) {
        if (dataEvent == null) return;
        Platform.runLater(() -> {
            if (plotDisplayWindow != null && plotDisplayWindow.isShowing()) {
                PlotDisplay display = plotDisplayWindow.getPlotDisplay();
                if (display != null) {
                    display.addPlotEvent(dataEvent);
                }
            }
        });
    }

    private void setupControlPanelActions(Stage ownerStage) {
        controlPanel.setOnClearTrailAction(event -> {
            fieldDisplay.clearTrail();
            fieldDisplay.drawCurrentState();
            instructionLabel.setText("Robot trail cleared.");
        });
        controlPanel.setOnClearNamedLinesAction(event -> {
            clearAllNamedLines();
            instructionLabel.setText("All custom lines cleared.");
        });
        controlPanel.setOnShowPlotAction(event -> showPlotDisplay());
    }

    private void updateTimeLapsedDisplay() {
        if (controlPanel == null || recordingManager == null) return;
        long timeLapsedMs = recordingManager.getCurrentState() == RecordingManager.PlaybackState.RECORDING ? recordingManager.getCurrentRecordingDuration() : recordingManager.getCurrentEventTimeLapsed();
        controlPanel.updateTimeLapsed(timeLapsedMs);
    }

    private void setupRecordingControlActions() {
        controlPanel.setOnOpenAction(e -> handleOpenRecording());
        controlPanel.setOnSaveAction(e -> handleSaveRecording());

        controlPanel.setOnRecordAction(e -> {
            RecordingManager.PlaybackState recordingState = recordingManager.getCurrentState();
            if (recordingState == RecordingManager.PlaybackState.RECORDING) {
                recordingManager.stopRecording();
                controlPanel.setRecording(false);
                if (recordingManager.hasRecording()) {
                    controlPanel.setPlaybackControlsDisabled(false);
                    if (recordingManager.getTotalEvents() > 0) {
                        controlPanel.getTimelineSlider().setMax(recordingManager.getTotalEvents() - 1);
                    }
                    controlPanel.setSaveButtonDisabled(false);
                }
            } else {
                recordingManager.startRecording();
                controlPanel.setRecording(true);
                controlPanel.setPlaying(false);
                controlPanel.setPlaybackControlsDisabled(true);
                controlPanel.setSaveButtonDisabled(true);
            }
            updateTimeLapsedDisplay();
        });

        controlPanel.setOnPlayPauseAction(e -> {
            RecordingManager.PlaybackState playbackState = recordingManager.getCurrentState();
            if (playbackState == RecordingManager.PlaybackState.PLAYING) {
                recordingManager.pause();
                controlPanel.setPlaying(false);
            } else if (playbackState == RecordingManager.PlaybackState.IDLE || playbackState == RecordingManager.PlaybackState.PAUSED) {
                if (recordingManager.hasRecording()) {
                    recordingManager.play();
                    controlPanel.setPlaying(true);
                }
            }
            updateTimeLapsedDisplay();
        });

        controlPanel.setOnForwardAction(e -> {
            if (recordingManager.hasRecording() && recordingManager.getCurrentState() != RecordingManager.PlaybackState.RECORDING) {
                recordingManager.stepForward();
                controlPanel.setPlaying(false);
            }
            updateTimeLapsedDisplay();
        });

        controlPanel.setOnReverseAction(e -> {
            if (recordingManager.hasRecording() && recordingManager.getCurrentState() != RecordingManager.PlaybackState.RECORDING) {
                recordingManager.stepBackward();
                controlPanel.setPlaying(false);
            }
            updateTimeLapsedDisplay();
        });

        // Switched from valueChangingProperty to valueProperty to allow for real-time scrubbing.
        controlPanel.getTimelineSlider().valueProperty().addListener((obs, oldValue, newValue) -> {
            // We only seek when the user is directly interacting with the slider (e.g., dragging it).
            // This check prevents the listener from firing when the slider's value is updated programmatically during playback.
            if (controlPanel.getTimelineSlider().isPressed()) {
                RecordingManager.PlaybackState state = recordingManager.getCurrentState();
                if (state != RecordingManager.PlaybackState.PLAYING && state != RecordingManager.PlaybackState.RECORDING) {
                    int newIndex = newValue.intValue();
                    // To avoid redundant calls, we only seek if the index has actually changed.
                    if (newIndex != oldValue.intValue()) {
                        recordingManager.seekTo(newIndex);
                    }
                }
            }
        });

        controlPanel.setOnInstantReplayAction(e -> {
            recordingManager.loadFromLiveBuffer();
            controlPanel.setReplayMode(true);
            boolean hasRecording = recordingManager.hasRecording();
            controlPanel.setPlaybackControlsDisabled(!hasRecording);
            controlPanel.setSaveButtonDisabled(!hasRecording);
            if (hasRecording) {
                controlPanel.getTimelineSlider().setMax(recordingManager.getTotalEvents() > 0 ? recordingManager.getTotalEvents() - 1 : 0);
                controlPanel.getTimelineSlider().setValue(0);
                recordingManager.seekTo(0);
            } else {
                controlPanel.getTimelineSlider().setMax(0);
            }
            updateTimeLapsedDisplay();
            instructionLabel.setText("Reviewing last 10 minutes. Press \"Return to Live\" to exit.");
        });

        controlPanel.setOnReturnToLiveAction(e -> {
            recordingManager.stopPlayback();
            recordingManager.loadRecording(new ArrayList<>());
            controlPanel.setReplayMode(false);
            controlPanel.setPlaybackControlsDisabled(true);
            controlPanel.setSaveButtonDisabled(true);
            controlPanel.getTimelineSlider().setMax(0);
            controlPanel.getTimelineSlider().setValue(0);
            fieldDisplay.clearTrail();
            clearAllNamedLines();
            fieldDisplay.drawCurrentState();
            updateTimeLapsedDisplay();
            instructionLabel.setText("Returned to live view.");
        });
    }

    private void handleSaveRecording() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Recording");
        fileChooser.setInitialFileName("recording.rec");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Recording Files", "*.rec"));
        File file = primaryStage != null ? fileChooser.showSaveDialog(primaryStage) : null;
        if (file == null) {
            instructionLabel.setText("Save cancelled.");
            return;
        }
        ArrayList<RecordingManager.RecordedEvent> events = recordingManager.getRecordedSession();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (RecordingManager.RecordedEvent event : events) {
                String line = formatEventToString(event);
                if (line != null) {
                    writer.write(line);
                    writer.newLine();
                }
            }
            instructionLabel.setText("Recording saved: " + file.getName());
        } catch (IOException e) {
            instructionLabel.setText("Error saving recording: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String formatEventToString(RecordingManager.RecordedEvent event) {
        String payload; UdpMessageData data = event.messageData;
        if (data instanceof PositionData) { PositionData d = (PositionData) data; payload = String.format(Locale.US,"pos:%.3f,%.3f,%.3f", d.x, d.y, d.heading); }
        else if (data instanceof CircleData) { CircleData d = (CircleData) data; payload = String.format(Locale.US,"cir:%.3f,%.3f", d.radiusInches, d.heading); }
        else if (data instanceof LineData) { LineData d = (LineData) data; payload = String.format(Locale.US,"line:%s,%.3f,%.3f,%.3f,%.3f,%d", d.name, d.x1, d.y1, d.x2, d.y2, d.styleCode); }
        else if (data instanceof TextData) { TextData d = (TextData) data; payload = "txt:" + d.text; }
        else if (data instanceof KeyValueData) { KeyValueData kv = (KeyValueData) data; payload = String.format("kv:%s,%s", kv.key, kv.value); }
        else { return null; }
        return event.timestamp + "|" + payload;
    }

    private void handleOpenRecording() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Recording");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Recording Files", "*.rec"));
        File file = primaryStage != null ? fileChooser.showOpenDialog(primaryStage) : null;
        if (file == null) { instructionLabel.setText("Open cancelled."); return; }
        ArrayList<RecordingManager.RecordedEvent> loadedEvents = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] lineParts = line.split("\\|", 2);
                if (lineParts.length != 2) continue;
                long timestamp = Long.parseLong(lineParts[0]);
                String payload = lineParts[1];
                UdpMessageData parsedData = null;
                if (payload.startsWith("pos:")) { String c = payload.substring(4); String[] p = c.split(","); if (p.length == 3) parsedData = new PositionData(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])); }
                else if (payload.startsWith("cir:")) { String c = payload.substring(4); String[] p = c.split(","); if (p.length == 2) parsedData = new CircleData(Double.parseDouble(p[0]), Double.parseDouble(p[1])); }
                else if (payload.startsWith("line:")) { String c = payload.substring(5); String[] p = c.split(",", 6); if (p.length == 6) parsedData = new LineData(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]), Integer.parseInt(p[5]));}
                else if (payload.startsWith("txt:")) { parsedData = new TextData(payload.substring(4));}
                else if (payload.startsWith("kv:")) { String c = payload.substring(3); String[] p = c.split(",", 2); if (p.length == 2) parsedData = new KeyValueData(p[0], p[1]);}
                if (parsedData != null) loadedEvents.add(new RecordingManager.RecordedEvent(timestamp, parsedData));
            }
            recordingManager.loadRecording(loadedEvents);
            controlPanel.setPlaybackControlsDisabled(loadedEvents.isEmpty());
            if (!loadedEvents.isEmpty()) {
                controlPanel.getTimelineSlider().setMax(recordingManager.getTotalEvents() - 1);
            }
            controlPanel.setSaveButtonDisabled(loadedEvents.isEmpty());
            controlPanel.setPlaying(false);
            instructionLabel.setText("Opened: " + file.getName());
            updateTimeLapsedDisplay();
        } catch (Exception e) {
            instructionLabel.setText("Error opening recording: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processUdpDataAndUpdateUI(UdpMessageData messageData) {
        if (messageData == null) return;
        if (messageData instanceof PositionData) {
            PositionData p = (PositionData) messageData;
            if (robot != null) {
                fieldDisplay.addTrailDot(robot.getXInches(), robot.getYInches());
                robot.setPosition(p.x, p.y, p.heading);
            }
        } else if (messageData instanceof CircleData) {
            CircleData c = (CircleData) messageData;
            if (fieldDisplay != null) fieldDisplay.addDebugCircle(robot.getXInches(), robot.getYInches(), c.radiusInches, c.heading, Color.rgb(255, 165, 0, 0.7));
        } else if (messageData instanceof LineData) {
            LineData l = (LineData) messageData;
            synchronized (namedLinesLock) { namedLinesToDraw.put(l.name, l); }
        } else if (messageData instanceof TextData) {
            TextData t = (TextData) messageData;
            if (fieldDisplay != null) fieldDisplay.setRobotTextMessage(t.text);
        } else if (messageData instanceof KeyValueData) {
            KeyValueData kv = (KeyValueData) messageData;
            if (keyValueTable != null) {
                keyValueTable.updateValue(kv.key, kv.value);
            }
        }
        if (messageData instanceof PositionData) {
            updateUIFromRobotState();
        } else {
            fieldDisplay.drawCurrentState();
        }
    }

    private void handleUdpMessage(UdpMessageData messageData) {
        if (messageData == null) return;
        recordingManager.addLiveEvent(messageData);
        RecordingManager.PlaybackState state = recordingManager.getCurrentState();
        if (state != RecordingManager.PlaybackState.PLAYING && state != RecordingManager.PlaybackState.PAUSED) {
            Platform.runLater(() -> processUdpDataAndUpdateUI(messageData));
        }
    }

    private void onPlaybackFinished() {
        Platform.runLater(() -> {
            if (controlPanel != null) {
                controlPanel.setPlaying(false);
            }
            updateTimeLapsedDisplay();
        });
    }

    private void startUdpPositionListener() {
        try {
            udpListener = new UdpPositionListener(UDP_LISTENER_PORT, this::handleUdpMessage);
            udpListenerThread = new Thread(udpListener, "UdpListenerThread");
            udpListenerThread.setDaemon(true);
            udpListenerThread.start();
            System.out.println("UDP Listener started on port " + UDP_LISTENER_PORT);
        } catch (Exception e) {
            instructionLabel.setText("ERROR: UDP Listener start failed on " + UDP_LISTENER_PORT);
            e.printStackTrace();
        }
    }

    private void startUdpPlotListener() {}

    private void stopApp() {
        if (udpListener != null) udpListener.stopListener();
        if (udpPlotListener != null) udpPlotListener.stopListener();
        if(recordingManager != null) recordingManager.stopPlayback();
        Platform.exit();
        System.exit(0);
    }

    private void clearAllNamedLines() {
        synchronized (namedLinesLock) {
            namedLinesToDraw.clear();
        }
        if (fieldDisplay != null) {
            fieldDisplay.drawCurrentState();
        }
    }

    private void handleSceneKeyPress(KeyEvent event) {}
}

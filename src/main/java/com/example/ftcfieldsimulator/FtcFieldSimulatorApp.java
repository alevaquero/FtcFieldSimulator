package com.example.ftcfieldsimulator;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox; // NEW IMPORT
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ButtonBar;

import com.example.ftcfieldsimulator.UdpPositionListener.CircleData;
import com.example.ftcfieldsimulator.UdpPositionListener.KeyValueData;
import com.example.ftcfieldsimulator.UdpPositionListener.LineData;
import com.example.ftcfieldsimulator.UdpPositionListener.PositionData;
import com.example.ftcfieldsimulator.UdpPositionListener.TextData;
import com.example.ftcfieldsimulator.UdpPositionListener.UdpMessageData;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FtcFieldSimulatorApp extends Application {

    // --- UI and State Components ---
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
    private Map<TextField, String> textFieldPreviousValues = new HashMap<>();

    // --- Configuration Constants (omitted for brevity, no changes here) ---
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
    private static final int ROBOT_LISTENER_PORT = 6666;
    private static final double ROBOT_MOVE_INCREMENT_INCHES = 2.0;
    private static final double ROBOT_TURN_INCREMENT_DEGREES = 5.0;
    private static final double MASTER_DEFAULT_MOVE_SPEED = 0.4;
    private static final double MASTER_DEFAULT_TURN_SPEED = 0.4;
    private static final double MASTER_DEFAULT_FOLLOW_DISTANCE = 10.0;
    private static final double MASTER_DEFAULT_SLOW_DOWN_TURN_RADIANS = Math.toRadians(60);
    private static final double MASTER_DEFAULT_SLOW_DOWN_TURN_AMOUNT = 0.6;


    public static void main(String[] args) {
        Application.launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("FTC Field Simulator");

        double controlPanelWidth = FIELD_DISPLAY_WIDTH_PIXELS * ControlPanel.PREFERRED_WIDTH_RATIO_TO_FIELD;
        double rightPanelWidth = controlPanelWidth * 0.9; // Make right panel slightly smaller
        double totalAppWidth = FIELD_DISPLAY_WIDTH_PIXELS + controlPanelWidth + rightPanelWidth;
        double totalAppHeight = FIELD_DISPLAY_HEIGHT_PIXELS;

        // --- Init core components ---
//        this.recordingManager = new RecordingManager(this::handleUdpMessage, this::onPlaybackFinished);
        this.recordingManager = new RecordingManager(this::processUdpDataAndUpdateUI, this::onPlaybackFinished);
        recordingManager.setOnProgressUpdate(index -> {
            if (controlPanel != null && controlPanel.getTimelineSlider() != null && !controlPanel.getTimelineSlider().isValueChanging()) {
                controlPanel.getTimelineSlider().setValue(index);
            }
            updateTimeLapsedDisplay();
        });

        instructionLabel = new Label("Create a new path or select a point to edit its parameters.");
        instructionLabel.setPadding(new Insets(5));
        instructionLabel.setMaxWidth(Double.MAX_VALUE);
        instructionLabel.setAlignment(Pos.CENTER);
        HBox instructionPane = new HBox(instructionLabel);
        instructionPane.setAlignment(Pos.CENTER);
        instructionPane.setStyle("-fx-background-color: #CFD8DC;");

        this.robot = new Robot(ROBOT_START_FIELD_X, ROBOT_START_FIELD_Y, ROBOT_START_HEADING_DEGREES, ROBOT_IMAGE_PATH);
        fieldDisplay = new FieldDisplay(FIELD_DISPLAY_WIDTH_PIXELS, FIELD_DISPLAY_HEIGHT_PIXELS, FIELD_WIDTH_INCHES, FIELD_HEIGHT_INCHES, FIELD_IMAGE_PATH, robot, BACKGROUND_ALPHA, FIELD_IMAGE_ALPHA, instructionLabel);
        fieldDisplay.setNamedLinesMap(namedLinesToDraw);
        fieldDisplay.setRobotTextMessage(null);

        // --- Init UI Panels ---
        controlPanel = new ControlPanel(controlPanelWidth);
        keyValueTable = new FieldKeyValueTable(rightPanelWidth);
        fieldStatusDisplay = new FieldStatusDisplay();

        // --- Assemble the right-side panel ---
        VBox rightPanel = new VBox();
        // The keyValueTable will grow to fill available vertical space. The status display will be its natural height.
        VBox.setVgrow(keyValueTable, Priority.ALWAYS);
        rightPanel.getChildren().addAll(keyValueTable, fieldStatusDisplay);

        // --- Assemble the main layout ---
        BorderPane mainLayout = new BorderPane();
        mainLayout.setLeft(controlPanel);
        mainLayout.setCenter(fieldDisplay);
        mainLayout.setRight(rightPanel);
        mainLayout.setBottom(instructionPane);

        double instructionPaneHeight = 60;
        Scene scene = new Scene(mainLayout, totalAppWidth, totalAppHeight + instructionPaneHeight);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleSceneKeyPress);

        // --- Wire up everything ---
        setupControlPanelActions(primaryStage);
        setupRecordingControlActions();
        setupParameterFieldListeners();
        setupFieldDisplayMouseHandlers();
        setupFieldDisplayKeyHandlers();

        startUdpPositionListener();
        startUdpPlotListener();
        primaryStage.setScene(scene);
        primaryStage.setResizable(true);
        primaryStage.show();
        primaryStage.setOnCloseRequest(event -> stopApp());

        updateUIFromRobotState();
        updateControlPanelForPathState();
        updateTimeLapsedDisplay();
    }

    // --- To set up the delete action ---
    private void setupFieldDisplayKeyHandlers() {
        if (fieldDisplay == null) return;

        // Set the action for when the delete key is pressed on a point
        fieldDisplay.setOnPointDeleteAction(this::handleDeletePoint);
    }

    // --- To handle the logic of deleting a point ---
    private void handleDeletePoint(CurvePoint pointToDelete) {
        if (pointToDelete == null || !currentPath.contains(pointToDelete)) return;

        int deletedIndex = currentPath.indexOf(pointToDelete);
        currentPath.remove(pointToDelete);

        // If the first point was deleted, the robot's start position must be updated
        if (deletedIndex == 0 && !currentPath.isEmpty()) {
            CurvePoint newFirstPoint = currentPath.get(0);
            robot.setPosition(newFirstPoint.x, newFirstPoint.y);
            // The robot's heading remains unchanged in this case
            controlPanel.updateRobotStartFields(newFirstPoint.x, newFirstPoint.y, robot.getHeadingDegrees());
        }

        // Refresh the UI to reflect the change
        fieldDisplay.setHighlightedPoint(null); // Clear any highlight
        fieldDisplay.setPathToDraw(currentPath);
        updateControlPanelForPathState(); // This will update the ComboBox and other controls
        updateUIFromRobotState(); // Redraw everything

        instructionLabel.setText("Deleted Point " + (deletedIndex + 1) + ".");
    }

    /**
     * Sets up the mouse interaction handlers for the FieldDisplay canvas.
     * This connects the UI events for dragging points to the application logic.
     */
    private void setupFieldDisplayMouseHandlers() {
        if (fieldDisplay == null) return;

        // --- Handler for clicking on a segment ---
        fieldDisplay.setOnSegmentClick(this::handleInsertPoint);

        // Handler for while the mouse is being dragged
        fieldDisplay.setOnPointDrag((index, newCoords) -> {
            // The point object in currentPath is updated by reference.
            // We just need to update the UI.

            // If this is the first point, also update the Robot Start fields
            if (index == 0) {
                controlPanel.updateRobotStartFields(newCoords.getX(), newCoords.getY(), robot.getHeadingDegrees());
                // Also update the robot's internal position to match
                robot.setPosition(newCoords.getX(), newCoords.getY());
            }

            // Ensure the dragged point is selected in the ComboBox
            if (controlPanel.getSelectedPointFromComboBox() != currentPath.get(index)) {
                controlPanel.updatePointSelectionComboBox(currentPath, currentPath.get(index));
            }

            // Update the parameter text fields to show the new X/Y
            controlPanel.loadParametersForPoint(currentPath.get(index));

            instructionLabel.setText(String.format(Locale.US, "Dragging Point %d to (X:%.1f, Y:%.1f)",
                    index + 1, newCoords.getX(), newCoords.getY()));
        });

        // Handler for when the drag operation ends
        fieldDisplay.setOnPointDragEnd(index -> {
            if (index >= 0 && index < currentPath.size()) {
                CurvePoint point = currentPath.get(index);
                instructionLabel.setText(String.format(Locale.US, "Moved Point %d.", index + 1));

                // Refresh the ComboBox text to show the new coordinates
                controlPanel.updatePointSelectionComboBox(currentPath, point);
            }
        });
    }

    // --- To handle the logic of inserting a new point ---
    private void handleInsertPoint(int segmentIndex, Point2D clickCoordsPixels) {
        if (segmentIndex < 0 || segmentIndex >= currentPath.size() - 1) {
            return; // Invalid index
        }

        // Convert click coordinates from pixels to field inches
        Point2D clickCoordsInches = fieldDisplay.pixelToInches(clickCoordsPixels.getX(), clickCoordsPixels.getY());

        // Get the points that define the segment
        CurvePoint startPoint = currentPath.get(segmentIndex);
        CurvePoint endPoint = currentPath.get(segmentIndex + 1);

        // Interpolate the parameters for the new point (simple average)
        double newMoveSpeed = (startPoint.moveSpeed + endPoint.moveSpeed) / 2.0;
        double newTurnSpeed = (startPoint.turnSpeed + endPoint.turnSpeed) / 2.0;
        double newFollowDistance = (startPoint.followDistance + endPoint.followDistance) / 2.0;
        double newSlowDownTurnRadians = (startPoint.slowDownTurnRadians + endPoint.slowDownTurnRadians) / 2.0;
        double newSlowDownTurnAmount = (startPoint.slowDownTurnAmount + endPoint.slowDownTurnAmount) / 2.0;

        // Create the new point at the clicked location with interpolated parameters
        CurvePoint newPoint = new CurvePoint(
                clickCoordsInches.getX(),
                clickCoordsInches.getY(),
                newMoveSpeed,
                newTurnSpeed,
                newFollowDistance,
                newSlowDownTurnRadians,
                newSlowDownTurnAmount
        );

        // Insert the new point into the path right after the start of the segment
        currentPath.add(segmentIndex + 1, newPoint);

        // After modifying the path list, we must explicitly tell the display
        // to use the new version for its next drawing cycle.
        fieldDisplay.setPathToDraw(currentPath);

        // Refresh the entire UI
        fieldDisplay.setHighlightedPoint(newPoint); // Highlight the newly created point
        updateControlPanelForPathState(); // This will update the ComboBox to include the new point
        updateUIFromRobotState(); // Redraw everything

        instructionLabel.setText("Inserted new point " + (segmentIndex + 2) + ".");
    }

    private void updateUIFromRobotState() {
        if (robot != null && fieldDisplay != null) {
            double displayHeading = robot.getHeadingDegrees() % 360;
            if (displayHeading < 0) displayHeading += 360; // Normalize to 0-359

            // --- Send status update to the new display ---
            if (fieldStatusDisplay != null) {
                fieldStatusDisplay.updateRobotStatus(robot.getXInches(), robot.getYInches(), displayHeading);
            }

            // --- Update Robot Start Fields if they have focus ---
            if (controlPanel != null && (
                    controlPanel.getStartXField().isFocused() ||
                            controlPanel.getStartYField().isFocused() ||
                            controlPanel.getStartHeadingField().isFocused()
            )) {
                controlPanel.updateRobotStartFields(robot.getXInches(), robot.getYInches(), displayHeading);
            }

            fieldDisplay.drawCurrentState();
        }
    }

    // ... all other methods from FtcFieldSimulatorApp remain the same ...
    private void showPlotDisplay() {
        if (plotDisplayWindow == null) {
            plotDisplayWindow = new PlotDisplayWindow(primaryStage); // Pass primary stage as owner
        }
        plotDisplayWindow.show();
    }
    private void handleUdpPlotData(PlotDataEvent dataEvent) {
        if (dataEvent == null) return;

        Platform.runLater(() -> {
            // Log all received plot data for now
            // System.out.println("App received PlotDataEvent: " + dataEvent);

            // If PlotDisplayWindow is not yet created, create it.
            // This ensures if data comes before user clicks button, window can still be prepared.
            // However, it won't show until user clicks.
            if (plotDisplayWindow == null) {
                // plotDisplayWindow = new PlotDisplayWindow(primaryStage); // Create but don't show
                // Let's only interact if it's already created and showing by user action
            }

            if (plotDisplayWindow != null && plotDisplayWindow.isShowing()) {
                PlotDisplay display = plotDisplayWindow.getPlotDisplay();
                if (display != null) {
                    // Pass the generic event. PlotDisplay will decide what to do.
                    display.addPlotEvent(dataEvent);
                }
            } else if (dataEvent instanceof PlotYLimitsEvent || dataEvent instanceof PlotYUnitsEvent) {
                // Optional: If plot window isn't visible, maybe still store these global settings
                // so when it becomes visible, it uses the latest known ones.
                // For now, we only update if visible.
                System.out.println("Plot window not visible. Discarding: " + dataEvent);
            }
        });
    }

    private void setupControlPanelActions(Stage ownerStage) {
        controlPanel.setOnNewPathAction(event -> startNewPathCreation());
        controlPanel.setOnDeletePathAction(event -> deleteCurrentPath());
        controlPanel.setOnImportCodeAction(event -> showImportCodeDialog());
        controlPanel.setOnExportCodeAction(event -> exportPathToCode());
        controlPanel.setOnSendPathAction(event -> handleSendPathToRobot());
        controlPanel.setOnClearTrailAction(event -> {
            fieldDisplay.clearTrail();
            fieldDisplay.drawCurrentState();
            instructionLabel.setText("Robot trail cleared.");
        });
        controlPanel.setOnClearNamedLinesAction(event -> {
            clearAllNamedLines();
            instructionLabel.setText("All custom lines cleared.");
        });
        controlPanel.setOnPointSelectionAction(this::handlePointSelectionChanged);
        controlPanel.setOnShowPlotAction(event -> showPlotDisplay());
    }

    // --- To show the import dialog ---
    private void showImportCodeDialog() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Import Path from Code");
        dialog.setHeaderText("Paste your Java code snippet below.\nIt should contain 'new CurvePoint(...)' or 'new Pose2D(...)' lines.");
        dialog.setResizable(true);

        TextArea textArea = new TextArea();
        textArea.setPromptText("pathToSpike1.add(new CurvePoint(...));");
        textArea.setFont(Font.font("Consolas", 14));
        textArea.setPrefHeight(400);
        textArea.setPrefWidth(650);

        VBox content = new VBox(textArea);
        VBox.setVgrow(textArea, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(650, 480);

        ButtonType importButtonType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(importButtonType, ButtonType.CANCEL);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == importButtonType) {
                return textArea.getText();
            }
            return null;
        });

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(this::parseAndImportPath);
    }

    // --- NEW METHOD: To parse the pasted code and update the path ---
    private void parseAndImportPath(String code) {
        List<CurvePoint> newPath = new ArrayList<>();
        double startX = -1, startY = -1, startHeading = 0;
        boolean poseFound = false;

        // Regex to find "new Pose2D(..., X, Y, ..., H)"
        Pattern posePattern = Pattern.compile("new\\s+Pose2D\\([^,]+,\\s*([\\d\\.\\-]+),\\s*([\\d\\.\\-]+),\\s*[^,]+,\\s*([\\d\\.\\-]+)\\)");

        // Regex to find "new CurvePoint(X, Y, mS, tS, fD, Math.toRadians(sDTd), sDTa)"
        Pattern curvePointPattern = Pattern.compile(
                "new\\s+CurvePoint\\(\\s*([\\d\\.\\-]+),\\s*([\\d\\.\\-]+),\\s*([\\d\\.\\-]+)," +
                        "\\s*([\\d\\.\\-]+),\\s*([\\d\\.\\-]+),\\s*Math\\.toRadians\\(([\\d\\.\\-]+)\\)," +
                        "\\s*([\\d\\.\\-]+)\\)"
        );

        String[] lines = code.split("\\r?\\n");
        for (String line : lines) {
            // First, try to find a Pose2D line
            if (!poseFound) {
                Matcher poseMatcher = posePattern.matcher(line);
                if (poseMatcher.find()) {
                    try {
                        startX = Double.parseDouble(poseMatcher.group(1));
                        startY = Double.parseDouble(poseMatcher.group(2));
                        startHeading = Double.parseDouble(poseMatcher.group(3));
                        poseFound = true;
                    } catch (NumberFormatException e) {
                        System.err.println("Could not parse Pose2D line: " + line);
                    }
                }
            }

            // Then, try to find a CurvePoint line
            Matcher curvePointMatcher = curvePointPattern.matcher(line);
            if (curvePointMatcher.find()) {
                try {
                    double x = Double.parseDouble(curvePointMatcher.group(1));
                    double y = Double.parseDouble(curvePointMatcher.group(2));
                    double moveSpeed = Double.parseDouble(curvePointMatcher.group(3));
                    double turnSpeed = Double.parseDouble(curvePointMatcher.group(4));
                    double followDistance = Double.parseDouble(curvePointMatcher.group(5));
                    double slowDownTurnDeg = Double.parseDouble(curvePointMatcher.group(6));
                    double slowDownTurnAmount = Double.parseDouble(curvePointMatcher.group(7));

                    newPath.add(new CurvePoint(x, y, moveSpeed, turnSpeed, followDistance, Math.toRadians(slowDownTurnDeg), slowDownTurnAmount));
                } catch (NumberFormatException e) {
                    System.err.println("Could not parse CurvePoint line: " + line);
                }
            }
        }

        if (newPath.isEmpty()) {
            instructionLabel.setText("Import failed: No valid 'new CurvePoint(...)' lines found.");
            return;
        }

        // --- Apply the new path ---
        // If no explicit Pose2D was found, use the first point of the path
        if (!poseFound) {
            CurvePoint firstPoint = newPath.get(0);
            startX = firstPoint.x;
            startY = firstPoint.y;
            startHeading = 0.0; // Default heading
        }

        // Replace the current path
        this.currentPath = newPath;

        // Update robot position and UI fields
        robot.setPosition(startX, startY, startHeading);
        controlPanel.updateRobotStartFields(startX, startY, startHeading);

        // Update the rest of the UI
        fieldDisplay.setPathToDraw(this.currentPath);
        isCreatingPath = false;
        controlPanel.setPathEditingActive(false);
        updateControlPanelForPathState();
        updateUIFromRobotState(); // Redraws everything

        instructionLabel.setText("Successfully imported " + newPath.size() + " points.");
    }

    private void setupParameterFieldListeners() {
        if (controlPanel == null) return;
        for (TextField tf : controlPanel.getAllParamTextFields()) {
            tf.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal) {
                    textFieldPreviousValues.put(tf, tf.getText());
                } else {
                    handleParameterFieldFocusLost(tf);
                }
            });
            tf.setOnAction(event -> handleParameterFieldFocusLost(tf));
        }

        // --- Listener for Robot Start Position parameters ---
        for (TextField tf : controlPanel.getRobotStartTextFields()) {
            tf.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal) {
                    textFieldPreviousValues.put(tf, tf.getText());
                } else {
                    handleRobotStartFieldFocusLost(); // A single handler for all 3 fields
                }
            });
            tf.setOnAction(event -> handleRobotStartFieldFocusLost());
        }
    }

    private void handleRobotStartFieldFocusLost() {
        if (controlPanel == null || robot == null) return;

        try {
            double newX = Double.parseDouble(controlPanel.getStartXField().getText());
            double newY = Double.parseDouble(controlPanel.getStartYField().getText());
            double newHeading = Double.parseDouble(controlPanel.getStartHeadingField().getText());

            robot.setPosition(newX, newY, newHeading);
            updateUIFromRobotState(); // Redraw robot at new position

            // If a path exists, update its first point to match the new robot start position
            if (!currentPath.isEmpty()) {
                CurvePoint firstPoint = currentPath.get(0);
                firstPoint.x = newX;
                firstPoint.y = newY;
                fieldDisplay.drawCurrentState(); // Redraw the path
                controlPanel.updatePointSelectionComboBox(currentPath, controlPanel.getSelectedPointFromComboBox()); // Refresh combo box text
            }
            instructionLabel.setText("Robot start position updated.");

        } catch (NumberFormatException e) {
            instructionLabel.setText("Invalid start position. Reverting.");
            // Revert fields to the robot's actual current state
            updateUIFromRobotState();
        }
    }

    private void handleParameterFieldFocusLost(TextField textField) {
        if (controlPanel == null || currentPath == null) return;

        String previousText = textFieldPreviousValues.getOrDefault(textField, "");
        String currentText = textField.getText().trim(); // Trim whitespace

        if (Objects.equals(previousText, currentText) && !previousText.equals(ControlPanel.TEXTFIELD_VARIES_TEXT)) {
            return; // No actual change, or was "-- Varies --" and still is (though user can't type that directly)
        }
        // If currentText becomes the "-- Varies --" text, it's likely a programmatic change, not user input for commit.
        if (currentText.equals(ControlPanel.TEXTFIELD_VARIES_TEXT)) {
            textFieldPreviousValues.put(textField, currentText); // Update stored value if it was set programmatically
            return;
        }

        Object selectedItem = controlPanel.getSelectedPointFromComboBox();
        double parsedValue;

        try {
            if (currentText.isEmpty()) {
                // If "ALL" was selected and field showed "-- Varies --" and user cleared it
                if (Objects.equals(selectedItem, ControlPanel.ALL_POINTS_MARKER) && previousText.equals(ControlPanel.TEXTFIELD_VARIES_TEXT)) {
                    System.out.println("Field cleared for 'ALL' that showed '-- Varies --'. No update to points for this field.");
                    // The field will remain empty. If another selection happens, it will be re-populated.
                    // Or, we could force re-evaluation of "--Varies--" here, but it might be cleared by user.
                    // For now, let it be empty. Selection change will refresh.
                    textFieldPreviousValues.put(textField, currentText); // Store the empty state
                    return;
                }
                throw new NumberFormatException("Parameter cannot be empty."); // Empty is invalid for a specific point or for "ALL" when setting a value
            }
            parsedValue = Double.parseDouble(currentText);

            // Add specific range validation if desired
            if (textField == controlPanel.getMoveSpeedField() && parsedValue <= 0) throw new NumberFormatException("Move speed must be > 0");
            if (textField == controlPanel.getTurnSpeedField() && parsedValue <= 0) throw new NumberFormatException("Turn speed must be > 0");
            if (textField == controlPanel.getFollowDistanceField() && parsedValue < 0) throw new NumberFormatException("Follow distance must be >= 0");
            if (textField == controlPanel.getSlowDownTurnAmountField() && (parsedValue < 0 || parsedValue > 1)) throw new NumberFormatException("Slow down amount must be 0.0-1.0");
            // No specific range for slowDownTurnDegreesField here, but it's converted to radians.

        } catch (NumberFormatException e) {
            instructionLabel.setText("Invalid input: " + e.getMessage() + ". Reverting.");
            textField.setText(previousText); // Revert
            textFieldPreviousValues.put(textField, previousText); // Ensure map is correct
            return;
        }

        boolean updateOccurred = false;
        if (Objects.equals(selectedItem, ControlPanel.ALL_POINTS_MARKER)) {
            for (CurvePoint point : currentPath) {
                updateCurvePointParameter(point, textField, parsedValue);
            }
            instructionLabel.setText("Applied '" + getFieldName(textField) + " = " + currentText + "' to all points.");
            updateOccurred = true;
            refreshParameterFieldsForAllSelected(); // Refresh all fields for "ALL" view, as one change might make others uniform or varied
        } else if (selectedItem instanceof CurvePoint) {
            CurvePoint point = (CurvePoint) selectedItem;
            updateCurvePointParameter(point, textField, parsedValue);
            int pointIndex = currentPath.indexOf(point) + 1;
            instructionLabel.setText("Updated Point " + pointIndex + " (" + getFieldName(textField) + " = " + currentText + ").");
            updateOccurred = true;
        }

        if (updateOccurred) {
            textFieldPreviousValues.put(textField, currentText); // Update stored value after successful application
            fieldDisplay.drawCurrentState(); // Redraw path if parameters affect appearance (though unlikely for these)
        }
    }

    private void updateCurvePointParameter(CurvePoint point, TextField changedField, double value) {
        if (changedField == controlPanel.getMoveSpeedField()) point.moveSpeed = value;
        else if (changedField == controlPanel.getTurnSpeedField()) point.turnSpeed = value;
        else if (changedField == controlPanel.getFollowDistanceField()) point.followDistance = value;
        else if (changedField == controlPanel.getSlowDownTurnDegreesField()) point.slowDownTurnRadians = Math.toRadians(value);
        else if (changedField == controlPanel.getSlowDownTurnAmountField()) point.slowDownTurnAmount = value;
    }

    private String getFieldName(TextField textField) {
        if (textField == controlPanel.getMoveSpeedField()) return "Move Speed";
        if (textField == controlPanel.getTurnSpeedField()) return "Turn Speed";
        if (textField == controlPanel.getFollowDistanceField()) return "Follow Distance";
        if (textField == controlPanel.getSlowDownTurnDegreesField()) return "Slow Turn Deg";
        if (textField == controlPanel.getSlowDownTurnAmountField()) return "Slow Turn Amt";
        return "Parameter";
    }

    private void handlePointSelectionChanged(ObservableValue<? extends Object> obs, Object oldVal, Object newVal) {
        if (controlPanel == null || isCreatingPath) { // Do not change display if actively drawing path
            if (isCreatingPath && newVal != null && controlPanel.getSelectedPointFromComboBox() != null) {
                // If drawing path and user somehow clicks ComboBox, try to revert to original selection if possible
                // This is a bit defensive, ideally ComboBox is disabled during path creation
                Platform.runLater(() -> controlPanel.updatePointSelectionComboBox(currentPath, oldVal != null ? oldVal : ControlPanel.ALL_POINTS_MARKER));
            }
            return;
        }

        CurvePoint pointToHighlight = null; // Initialize to null (no highlight)

        if (newVal == null) {
            if (currentPath.isEmpty()) {
                controlPanel.loadGlobalDefaultsIntoParameterFields();
                controlPanel.setPointEditingControlsDisabled(true);
            } else {
                // Should default to "ALL" if path exists
                controlPanel.updatePointSelectionComboBox(currentPath, ControlPanel.ALL_POINTS_MARKER);
                // This will re-trigger the listener with "ALL"
            }
            return;
        } else if (Objects.equals(newVal, ControlPanel.ALL_POINTS_MARKER)) {
            refreshParameterFieldsForAllSelected();
            // When "ALL" is selected, no specific point is highlighted
        } else if (newVal instanceof CurvePoint) {
            CurvePoint selectedCurvePoint = (CurvePoint) newVal;
            controlPanel.loadParametersForPoint(selectedCurvePoint);
            pointToHighlight = selectedCurvePoint; // This is the point to highlight
        }

        System.out.println("ComboBox selection changed to: " + newVal);
        if (Objects.equals(newVal, ControlPanel.ALL_POINTS_MARKER)) {
            refreshParameterFieldsForAllSelected();
        } else if (newVal instanceof CurvePoint) {
            controlPanel.loadParametersForPoint((CurvePoint) newVal);
        }

        // Update the highlighted point in FieldDisplay and redraw
        if (fieldDisplay != null) {
            fieldDisplay.setHighlightedPoint(pointToHighlight);
            fieldDisplay.drawCurrentState(); // Redraw the field to show highlight
        }
    }

    private void refreshParameterFieldsForAllSelected() {
        if (controlPanel == null) return;
        if (currentPath.isEmpty()) {
            controlPanel.loadGlobalDefaultsIntoParameterFields();
            // Point editing controls should be disabled by updateControlPanelForPathState
            return;
        }

        System.out.println("Refreshing for ALL points. Path size: " + currentPath.size());
        checkAndSetField(currentPath, cp -> cp.moveSpeed, controlPanel.getMoveSpeedField(), "%.2f");
        checkAndSetField(currentPath, cp -> cp.turnSpeed, controlPanel.getTurnSpeedField(), "%.2f");
        checkAndSetField(currentPath, cp -> cp.followDistance, controlPanel.getFollowDistanceField(), "%.1f");
        checkAndSetField(currentPath, cp -> Math.toDegrees(cp.slowDownTurnRadians), controlPanel.getSlowDownTurnDegreesField(), "%.1f");
        checkAndSetField(currentPath, cp -> cp.slowDownTurnAmount, controlPanel.getSlowDownTurnAmountField(), "%.2f");
    }

    private <T> void checkAndSetField(List<CurvePoint> path, Function<CurvePoint, T> getter, TextField field, String format) {
        if (path.isEmpty()) { // Should be handled by calling context
            loadSpecificGlobalDefault(field); // Fallback
            return;
        }

        T firstValue = getter.apply(path.get(0));
        boolean allSame = true;
        for (int i = 1; i < path.size(); i++) {
            T currentValue = getter.apply(path.get(i));
            if (currentValue instanceof Double && firstValue instanceof Double) {
                if (Math.abs((Double) currentValue - (Double) firstValue) > 0.0001) { // Tolerance for double comparison
                    allSame = false;
                    break;
                }
            } else if (!Objects.equals(currentValue, firstValue)) {
                allSame = false;
                break;
            }
        }

        if (allSame) {
            if (firstValue instanceof Double) {
                field.setText(String.format(Locale.US, format, (Double) firstValue));
            } else { // Should not happen with current parameters but good for generic
                field.setText(firstValue.toString());
            }
        } else {
            field.setText(ControlPanel.TEXTFIELD_VARIES_TEXT);
        }
        textFieldPreviousValues.put(field, field.getText()); // Update stored value for focus lost checks
    }

    private void loadSpecificGlobalDefault(TextField field) {
        // Accessing static defaults from ControlPanel directly
        if (field == controlPanel.getMoveSpeedField()) field.setText(ControlPanel.DEFAULT_MOVE_SPEED);
        else if (field == controlPanel.getTurnSpeedField()) field.setText(ControlPanel.DEFAULT_TURN_SPEED);
        else if (field == controlPanel.getFollowDistanceField()) field.setText(ControlPanel.DEFAULT_FOLLOW_DISTANCE);
        else if (field == controlPanel.getSlowDownTurnDegreesField()) field.setText(ControlPanel.DEFAULT_SLOW_DOWN_TURN_DEGREES);
        else if (field == controlPanel.getSlowDownTurnAmountField()) field.setText(ControlPanel.DEFAULT_SLOW_DOWN_TURN_AMOUNT);
        else field.setText("");
        textFieldPreviousValues.put(field, field.getText());
    }

    private void updateControlPanelForPathState() {
        if (controlPanel == null) return;

        boolean pathExistsAndNotEmpty = !currentPath.isEmpty();
        controlPanel.setPointEditingControlsDisabled(!pathExistsAndNotEmpty);
        controlPanel.enablePathControls(pathExistsAndNotEmpty); // For Delete, Export, Send buttons

        Object selectionToRestore = controlPanel.getSelectedPointFromComboBox();
        if (!pathExistsAndNotEmpty) {
            selectionToRestore = ControlPanel.ALL_POINTS_MARKER; // Show ALL (which will load defaults) if no path
        } else {
            // If current selection is a CurvePoint but no longer in the path (e.g., path was cleared and a new short one made)
            if (selectionToRestore instanceof CurvePoint && !currentPath.contains(selectionToRestore)) {
                selectionToRestore = ControlPanel.ALL_POINTS_MARKER;
            } else if (selectionToRestore == null) { // No selection yet, default to ALL if path exists
                selectionToRestore = ControlPanel.ALL_POINTS_MARKER;
            }
        }
        controlPanel.updatePointSelectionComboBox(currentPath, selectionToRestore);

        // After ComboBox is updated, its value change listener (handlePointSelectionChanged)
        // should take care of populating the TextFields correctly.
        // So, explicitly calling refreshParameterFieldsForAllSelected or loadParametersForPoint here
        // might be redundant if the ComboBox listener is robust.
        // Let's ensure the listener is triggered or manually trigger if needed.
        Object currentSelectionAfterUpdate = controlPanel.getSelectedPointFromComboBox();
        if (currentSelectionAfterUpdate == null && pathExistsAndNotEmpty) {
            // This case should ideally not happen if updatePointSelectionComboBox defaults to "ALL"
            controlPanel.updatePointSelectionComboBox(currentPath, ControlPanel.ALL_POINTS_MARKER);
        } else {
            // Manually refresh based on current combo box state just in case listener didn't cover all edge cases on init
            if (Objects.equals(currentSelectionAfterUpdate, ControlPanel.ALL_POINTS_MARKER)) {
                refreshParameterFieldsForAllSelected();
            } else if (currentSelectionAfterUpdate instanceof CurvePoint) {
                controlPanel.loadParametersForPoint((CurvePoint) currentSelectionAfterUpdate);
            } else { // No path or null selection
                controlPanel.loadGlobalDefaultsIntoParameterFields();
            }
        }
        // If path creation is active, disable point editing controls again
        if (isCreatingPath) {
            controlPanel.setPointEditingControlsDisabled(true);
        }
    }

    private void handleSendPathToRobot() {
        if (currentPath.isEmpty()) {
            instructionLabel.setText("No path to send.");
            System.out.println("Attempted to send path, but currentPath is empty.");
            return;
        }

        // --- Get the selected IP from the Control Panel ---
        String robotIpAddress = controlPanel.getSelectedIpAddress();
        if (robotIpAddress == null || robotIpAddress.trim().isEmpty()) {
            instructionLabel.setText("No Robot IP Address selected!");
            return;
        }

        instructionLabel.setText("Sending path to robot...");
        System.out.println("Preparing to send " + currentPath.size() + " points to robot at " + robotIpAddress + ":" + ROBOT_LISTENER_PORT);
        // This try-with-resources block opens the network socket
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress address = InetAddress.getByName(robotIpAddress);
            // --- Get Follow Angle ---
            double followAngle;
            try {
                followAngle = Double.parseDouble(controlPanel.getFollowAngleField().getText());
                String followAngleMessage = String.format(Locale.US, "follow_angle:%.2f", followAngle);
                byte[] followAngleBuffer = followAngleMessage.getBytes(StandardCharsets.UTF_8);
                DatagramPacket followAnglePacket = new DatagramPacket(followAngleBuffer, followAngleBuffer.length, address, ROBOT_LISTENER_PORT);
                socket.send(followAnglePacket);
                System.out.println("Sent: " + followAngleMessage);
            } catch (NumberFormatException e) {
                instructionLabel.setText("Invalid Follow Angle! Sending Aborted.");
                System.err.println("Could not parse Follow Angle. Path sending aborted.");
                return;
            }

            // --- Get Start Position FROM THE UI TEXT FIELDS ---
            double startX, startY, startHeading;
            try {
                startX = Double.parseDouble(controlPanel.getStartXField().getText());
                startY = Double.parseDouble(controlPanel.getStartYField().getText());
                startHeading = Double.parseDouble(controlPanel.getStartHeadingField().getText());
            } catch (NumberFormatException e) {
                instructionLabel.setText("Invalid Start Position fields! Sending Aborted.");
                System.err.println("Could not parse Start Position fields. Path sending aborted.");
                return; // Stop if start position is invalid
            }

            String startPosMessage = String.format(Locale.US, "start_robot_pos:%.3f,%.3f,%.3f", startX, startY, startHeading);
            byte[] startPosBuffer = startPosMessage.getBytes(StandardCharsets.UTF_8);
            DatagramPacket startPosPacket = new DatagramPacket(startPosBuffer, startPosBuffer.length, address, ROBOT_LISTENER_PORT);
            socket.send(startPosPacket);
            System.out.println("Sent: " + startPosMessage);

            // Loop through each point in the current path (this part is unchanged)
            for (CurvePoint point : currentPath) {
                String message = String.format(Locale.US, "curve_point:%.3f,%.3f,%.2f,%.2f,%.2f,%.3f,%.2f",
                        point.x, point.y, point.moveSpeed, point.turnSpeed,
                        point.followDistance,
                        point.slowDownTurnRadians, point.slowDownTurnAmount);

                byte[] buffer = message.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, ROBOT_LISTENER_PORT);
                socket.send(packet);
                System.out.println("Sent: " + message);
            }

            // Send a final "end" message
            String endMessage = "end";
            byte[] endBuffer = endMessage.getBytes(StandardCharsets.UTF_8);
            DatagramPacket endPacket = new DatagramPacket(endBuffer, endBuffer.length, address, ROBOT_LISTENER_PORT);
            socket.send(endPacket);
            System.out.println("Sent: " + endMessage);
            instructionLabel.setText("Path sent successfully to " + robotIpAddress);            System.out.println("Path sending complete.");
        } catch (IOException e) {
            instructionLabel.setText("Error sending path: " + e.getMessage());
            System.err.println("Error sending path to robot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handles the logic for exporting the current path to a Java code snippet,
     * displaying it in a copy-pastable popup window.
     */
    private void exportPathToCode() { // The 'ownerStage' parameter is no longer needed
        if (currentPath == null || currentPath.isEmpty()) {
            instructionLabel.setText("No path to export.");
            // Show a simple info alert to the user
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Export Code");
            alert.setHeaderText(null);
            alert.setContentText("There is no path to export. Please create a path first.");
            alert.showAndWait();
            return;
        }

        // Use a StringBuilder to efficiently build the code string
        StringBuilder codeBuilder = new StringBuilder();

        try {
            // --- Get configuration values from the UI ---
            double followAngleDeg = Double.parseDouble(controlPanel.getFollowAngleField().getText());
            double startX = Double.parseDouble(controlPanel.getStartXField().getText());
            double startY = Double.parseDouble(controlPanel.getStartYField().getText());
            double startHeading = Double.parseDouble(controlPanel.getStartHeadingField().getText());

            // --- Generate the Code String ---
            codeBuilder.append("// Code generated by FTC Field Simulator\n\n");

            codeBuilder.append("// 1. Set the robot's starting position on the field\n");
            codeBuilder.append(String.format(Locale.US, "drivetrain.setPosition(new Pose2D(DistanceUnit.INCH, %.2f, %.2f, AngleUnit.DEGREES, %.2f));\n\n", startX, startY, startHeading));

            codeBuilder.append("// 2. Define the path waypoints\n");
            codeBuilder.append("ArrayList<CurvePoint> pathToFollow = new ArrayList<>();\n");

            for (CurvePoint point : currentPath) {
                codeBuilder.append(String.format(Locale.US,
                        "pathToFollow.add(new CurvePoint(%.2f, %.2f, %.2f, %.2f, %.2f, Math.toRadians(%.1f), %.2f));\n",
                        point.x, point.y,
                        point.moveSpeed, point.turnSpeed,
                        point.followDistance,
                        Math.toDegrees(point.slowDownTurnRadians),
                        point.slowDownTurnAmount
                ));
            }
            codeBuilder.append("\n");

            codeBuilder.append("// 3. Create and add the command to the scheduler\n");
            codeBuilder.append("// The 'true' argument enables debug drawing for this path in the simulator.\n");
            codeBuilder.append(String.format(Locale.US, "scheduler.add(new FollowPathCommand(pathToFollow, Math.toRadians(%.1f), true));\n", followAngleDeg));

        } catch (NumberFormatException e) {
            instructionLabel.setText("Invalid parameters in UI fields! Could not generate code.");
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Export Error");
            alert.setHeaderText("Invalid Number Format");
            alert.setContentText("Could not generate code because one of the path parameter fields (like Follow Angle or Start Position) contains invalid text.\n\nError: " + e.getMessage());
            alert.showAndWait();
            return;
        }

        // --- Create and show the popup window ---
        showCodePopup(codeBuilder.toString());
        instructionLabel.setText("Code generated. See popup window to copy.");
    }

    /**
     * Creates and displays a modal dialog containing the generated Java code.
     * @param code The string of Java code to display.
     */
    private void showCodePopup(String code) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Exported Java Code");
        alert.setHeaderText("Copy the code below and paste it into your OpMode.");
        alert.setResizable(true);

        TextArea textArea = new TextArea(code);
        textArea.setEditable(false);
        textArea.setWrapText(false); // Keep formatting clean
        textArea.setFont(Font.font("Consolas", 14)); // Use a monospace font for code

        // Set preferred size for the TextArea
        textArea.setPrefHeight(400);
        textArea.setPrefWidth(650);

        // To make the dialog resizable with the text area, we put the text area in a layout pane.
        // This is a common JavaFX trick to make dialog content expandable.
        VBox content = new VBox(textArea);
        VBox.setVgrow(textArea, Priority.ALWAYS);
        alert.getDialogPane().setContent(content);

        // Set the minimum size of the dialog pane itself
        alert.getDialogPane().setPrefSize(650, 480);

        alert.showAndWait();
    }

//    /**
//     * Handles the logic for exporting the current path to a Java code snippet file.
//     * @param ownerStage The main stage, used as the owner for the file chooser dialog.
//     */
//    private void exportPathToCode(Stage ownerStage) {
//        if (currentPath == null || currentPath.isEmpty()) {
//            instructionLabel.setText("No path to export.");
//            return;
//        }
//
//        FileChooser fileChooser = new FileChooser();
//        fileChooser.setTitle("Export Path to Java Code");
//        fileChooser.setInitialFileName("MyAutonomousPath.java"); // Suggest a .java file name
//        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java Files (*.java)", "*.java"));
//        File file = fileChooser.showSaveDialog(ownerStage);
//
//        if (file != null) {
//            try (PrintWriter writer = new PrintWriter(file)) {
//                // --- Get configuration values from the UI ---
//                double followAngleDeg;
//                try {
//                    followAngleDeg = Double.parseDouble(controlPanel.getFollowAngleField().getText());
//                } catch (NumberFormatException e) {
//                    instructionLabel.setText("Invalid Follow Angle! Using 90 deg for export.");
//                    followAngleDeg = 90.0; // Fallback to a safe default
//                }
//
//                double startX, startY, startHeading;
//                try {
//                    startX = Double.parseDouble(controlPanel.getStartXField().getText());
//                    startY = Double.parseDouble(controlPanel.getStartYField().getText());
//                    startHeading = Double.parseDouble(controlPanel.getStartHeadingField().getText());
//                } catch (NumberFormatException e) {
//                    instructionLabel.setText("Invalid Start Position! Using 0,0,0 for export.");
//                    startX = 0.0;
//                    startY = 0.0;
//                    startHeading = 0.0; // Fallback to a safe default
//                }
//
//
//                // --- START OF MODIFICATIONS ---
//
//                // --- 1. Write the scheduler initialization code ---
//                writer.println("        // It's recommended to initialize the scheduler once in your OpMode's init() method.");
//                writer.println("        // private CommandScheduler scheduler;");
//                writer.println("        // public void init() {");
//                writer.println("        //     ...");
//                writer.println("        //     scheduler = new CommandScheduler(drivetrain, intake, shooter, turret);");
//                writer.println("        // }");
//                writer.println();
//
//
//                // --- 2. Write the initial pose setting code ---
//                writer.println("        // Set the robot's starting position on the field");
//                writer.printf(Locale.US, "        Pose2D initialPose = new Pose2D(DistanceUnit.INCH, %.2f, %.2f, AngleUnit.DEGREES, %.2f);\n", startX, startY, startHeading);
//                writer.println("        drivetrain.setPosition(initialPose);");
//                writer.println();
//
//
//                // --- 3. Write the path generation code (unchanged) ---
//                writer.println("        // Path generated by FTC Field Simulator");
//                writer.println("        boolean debug = true;");
//                writer.println("        ArrayList<CurvePoint> path = new ArrayList<>();");
//
//                for (CurvePoint point : currentPath) {
//                    writer.printf(Locale.US,
//                            "        path.add(new CurvePoint(%.2f, %.2f, %.2f, %.2f, %.2f, Math.toRadians(%.1f), %.2f));\n",
//                            point.x,
//                            point.y,
//                            point.moveSpeed,
//                            point.turnSpeed,
//                            point.followDistance,
//                            Math.toDegrees(point.slowDownTurnRadians),
//                            point.slowDownTurnAmount
//                    );
//                }
//                writer.println();
//
//
//                // --- 4. Write the new scheduler add command ---
//                writer.printf(Locale.US, "        scheduler.add(new FollowPathCommand(path, Math.toRadians(%.1f), debug));\n", followAngleDeg);
//
//                // --- END OF MODIFICATIONS ---
//
//                instructionLabel.setText("Path exported as code to " + file.getName());
//            } catch (Exception e) {
//                instructionLabel.setText("Error exporting code: " + e.getMessage());
//                e.printStackTrace();
//            }
//        } else {
//            instructionLabel.setText("Code export cancelled.");
//        }
//    }

    private void deleteCurrentPath() {
        currentPath.clear();
        isCreatingPath = false;
        fieldDisplay.setPathCreationMode(false, null, () -> finishPathCreation(false));
        fieldDisplay.setPathToDraw(currentPath);
        fieldDisplay.drawCurrentState();
        fieldDisplay.setHighlightedPoint(null);
        instructionLabel.setText("Path deleted. Click 'New Path' to start drawing.");
        updateControlPanelForPathState();
    }

    private void finishPathCreation(boolean cancelled) {
        if (!isCreatingPath) return; // Guard against multiple calls

        isCreatingPath = false; // Path creation is officially over
        fieldDisplay.setPathCreationMode(false, null, null); // Stop field listening

        if (cancelled && currentPath != null) { // Null check for currentPath
            currentPath.clear();
            instructionLabel.setText("Path creation cancelled. Click 'New Path' to start again.");
        } else {
            if (currentPath == null || currentPath.isEmpty()) { // Null check
                instructionLabel.setText("Path finished with no points. Click 'New Path' to start again.");
                if (currentPath != null) currentPath.clear(); // Ensure it's empty if it was just null
                else currentPath = new ArrayList<>(); // Initialize if null
            } else {
                instructionLabel.setText("Path finished with " + currentPath.size() + " points. Select points to edit parameters.");
            }
        }

        // Explicitly re-enable the "New Path" button and allow others to be controlled by path existence.
        if (controlPanel != null) {
            controlPanel.setPathEditingActive(false);
        }

        // This will now correctly update the state of "Delete", "Export", "Send"
        // because newPathButton will be seen as enabled by enablePathControls.
        // It will also update the ComboBox and parameter fields.
        updateControlPanelForPathState();

        // Update the visual path on the field
        if (fieldDisplay != null && currentPath != null) { // Null checks
            fieldDisplay.setPathToDraw(currentPath);
            fieldDisplay.drawCurrentState();
        }
    }

    // In FtcFieldSimulatorApp.java

    private void handleFieldClickForPath(Point2D pixelCoords) {
        if (!isCreatingPath || controlPanel == null) return;

        Point2D inchesCoordsFieldCenter = fieldDisplay.pixelToInches(pixelCoords.getX(), pixelCoords.getY());
        double fieldX = inchesCoordsFieldCenter.getX();
        double fieldY = inchesCoordsFieldCenter.getY();

        // If the current path is empty, this is the very first point.
        if (currentPath.isEmpty()) {
            double startHeading = 0.0; // Default to 0 heading for the first point
            robot.setPosition(fieldX, fieldY, startHeading);

            // --- NEW: Update the new UI fields ---
            if (controlPanel != null) {
                controlPanel.updateRobotStartFields(fieldX, fieldY, startHeading);
            }
            updateUIFromRobotState(); // This will redraw the robot
        }

        double moveSpeed, turnSpeed, followDistance, pointLength, slowDownTurnDeg, slowDownTurnAmount, slowDownTurnRad;
        try {
            moveSpeed = controlPanel.getMoveSpeedParam();
            turnSpeed = controlPanel.getTurnSpeedParam();
            followDistance = controlPanel.getFollowDistanceParam();
            slowDownTurnDeg = controlPanel.getSlowDownTurnDegreesParam();
            slowDownTurnAmount = controlPanel.getSlowDownTurnAmountParam();
            if (moveSpeed <= 0 || turnSpeed <= 0 || followDistance < 0 || slowDownTurnAmount < 0 || slowDownTurnAmount > 1) {
                throw new NumberFormatException("Default parameter out of typical range.");
            }
            slowDownTurnRad = Math.toRadians(slowDownTurnDeg);
        } catch (NumberFormatException e) {
            instructionLabel.setText("Invalid global default parameter. Using master defaults for new point.");
            System.err.println("Error parsing global default parameters: " + e.getMessage() + ". Using master defaults.");
            moveSpeed = MASTER_DEFAULT_MOVE_SPEED;
            turnSpeed = MASTER_DEFAULT_TURN_SPEED;
            followDistance = MASTER_DEFAULT_FOLLOW_DISTANCE;
            slowDownTurnRad = MASTER_DEFAULT_SLOW_DOWN_TURN_RADIANS;
            slowDownTurnAmount = MASTER_DEFAULT_SLOW_DOWN_TURN_AMOUNT;
        }

        CurvePoint newPoint = new CurvePoint(
                fieldX, // Use the already calculated fieldX
                fieldY, // Use the already calculated fieldY
                moveSpeed, turnSpeed, followDistance, slowDownTurnRad, slowDownTurnAmount
        );

        currentPath.add(newPoint);
        fieldDisplay.setPathToDraw(currentPath);
        fieldDisplay.drawCurrentState(); // This call is sufficient now, as updateUIFromRobotState was called for the first point

        if (currentPath.size() == 1) {
            instructionLabel.setText("Point 1 added. Click next waypoint. ESC to cancel.");
        } else {
            instructionLabel.setText("Point " + currentPath.size() + " added. Click next, Double-click last, or ESC to cancel.");
        }
    }

    private void updateTimeLapsedDisplay() {
        if (controlPanel == null || recordingManager == null) return;

        long timeLapsedMs;
        if (recordingManager.getCurrentState() == RecordingManager.PlaybackState.RECORDING) {
            // If recording, show time since recording started
            timeLapsedMs = recordingManager.getCurrentRecordingDuration(); // Assumes you add such a method to RecordingManager
            // or calculate it based on System.time - recordingStartTime
        } else {
            timeLapsedMs = recordingManager.getCurrentEventTimeLapsed();
        }
        controlPanel.updateTimeLapsed(timeLapsedMs);
    }

    private void startNewPathCreation() {
        if (isCreatingPath) return; // Already in this mode

        if (currentPath == null) { // Defensive initialization
            currentPath = new ArrayList<>();
        }
        currentPath.clear();
        isCreatingPath = true;
        fieldDisplay.setHighlightedPoint(null);

        // Mode change: Disable "New Path", "Delete", "Export", "Send"
        // and enable "Finish Path" / "Cancel Path" implicitly by UI context.
        if (controlPanel != null) {
            controlPanel.setPathEditingActive(true);
        }

        // Update other UI elements:
        // - ComboBox for points should be cleared/disabled.
        // - Parameter fields should show global defaults but be disabled.
        // - "Delete", "Export", "Send" will be further confirmed as disabled by enablePathControls(false)
        updateControlPanelForPathState();

        instructionLabel.setText("Click the first waypoint. Parameters from global defaults will be used.");
        if (fieldDisplay != null) {
            fieldDisplay.setPathCreationMode(true, this::handleFieldClickForPath, () -> finishPathCreation(false));
            fieldDisplay.setPathToDraw(currentPath); // Show path as it's built
            fieldDisplay.drawCurrentState();
        }
    }

    // --- Recording, UDP, and Other Utility Methods (ensure these are complete from your original) ---
    private void setupRecordingControlActions() {
        controlPanel.setOnOpenAction(e -> handleOpenRecording());
        controlPanel.setOnSaveAction(e -> handleSaveRecording());
        controlPanel.setOnRecordAction(() -> {
            RecordingManager.PlaybackState recordingState = recordingManager.getCurrentState();
            if (recordingState == RecordingManager.PlaybackState.RECORDING) {
                recordingManager.stopRecording();
                controlPanel.toggleRecordButtonIcon(false);
                if (recordingManager.hasRecording()) {
                    controlPanel.setPlaybackControlsDisabled(false);
                    controlPanel.updateTimelineSlider(recordingManager.getPlaybackIndex(), recordingManager.getTotalEvents());
                    controlPanel.setSaveButtonDisabled(false);
                } else {
                    controlPanel.setPlaybackControlsDisabled(true);
                    controlPanel.setSaveButtonDisabled(true);
                }
            } else {
                controlPanel.togglePlayPauseButtonIcon(false);
                recordingManager.startRecording();
                controlPanel.toggleRecordButtonIcon(true);
                controlPanel.setPlaybackControlsDisabled(true);
                controlPanel.setSaveButtonDisabled(true);
            }
            updateTimeLapsedDisplay();
        });
        controlPanel.setOnPlayPauseAction(() -> {
            RecordingManager.PlaybackState playbackState = recordingManager.getCurrentState();
            if (playbackState == RecordingManager.PlaybackState.PLAYING) {
                recordingManager.pause();
                controlPanel.togglePlayPauseButtonIcon(false);
            } else if (playbackState == RecordingManager.PlaybackState.IDLE || playbackState == RecordingManager.PlaybackState.PAUSED) {
                if (recordingManager.hasRecording()) {
                    recordingManager.play();
                    controlPanel.togglePlayPauseButtonIcon(true);
                }
            }
            updateTimeLapsedDisplay();
        });
        controlPanel.setOnForwardAction(() -> {
            if (recordingManager.hasRecording() && recordingManager.getCurrentState() != RecordingManager.PlaybackState.RECORDING) {
                recordingManager.stepForward();
                controlPanel.togglePlayPauseButtonIcon(false);
            }
            updateTimeLapsedDisplay();
        });
        controlPanel.setOnReverseAction(() -> {
            if (recordingManager.hasRecording() && recordingManager.getCurrentState() != RecordingManager.PlaybackState.RECORDING) {
                recordingManager.stepBackward();
                controlPanel.togglePlayPauseButtonIcon(false);
            }
            updateTimeLapsedDisplay();
        });
        controlPanel.setOnTimelineSliderChanged((observable, oldValue, newValue) -> {
            if (controlPanel.getTimelineSlider().isValueChanging() && recordingManager.getCurrentState() != RecordingManager.PlaybackState.PLAYING) {
                recordingManager.seekTo(newValue.intValue());
                controlPanel.togglePlayPauseButtonIcon(false);
                updateTimeLapsedDisplay();
            }
        });
        controlPanel.setOnSliderMouseReleased(event -> {
            if (recordingManager.getCurrentState() != RecordingManager.PlaybackState.PLAYING &&
                    recordingManager.getCurrentState() != RecordingManager.PlaybackState.RECORDING) {

                int sliderRawValue = (int) controlPanel.getTimelineSlider().getValue();

                // The FieldDisplay might need to be cleared before a potentially large jump
                // if (fieldDisplay != null) {
                // fieldDisplay.clearDrawingSurface(); // Or similar method
                // }

                recordingManager.seekTo(sliderRawValue); // seekTo will now handle snapping and dispatching

                // After seekTo, playbackIndex in RecordingManager is at the snapped position.
                // The dispatchCurrentEvent calls within seekTo would have updated the
                // onProgressUpdateCallback, which in turn should update the slider's visual position
                // to the new playbackIndex and the time label.
                // So, an explicit controlPanel.getTimelineSlider().setValue() here might cause a flicker
                // if onProgressUpdate also does it. Let onProgressUpdate be the source of truth for UI sync.

                controlPanel.togglePlayPauseButtonIcon(false); // Ensure UI consistency
            }
            // updateTimeLapsedDisplay(); // Should be handled by onProgressUpdate from seekTo
        });

        controlPanel.setOnInstantReplayAction(e -> {
            recordingManager.loadFromLiveBuffer();
            controlPanel.setReplayMode(true); // Switch UI to replay mode

            boolean hasRecording = recordingManager.hasRecording();

            // Update UI state based on whether the buffer had events
            if (hasRecording) {
                // Set slider max to the number of events and seek to the beginning
                controlPanel.updateTimelineSlider(0, recordingManager.getTotalEvents());
                recordingManager.seekTo(0);
                instructionLabel.setText("Reviewing last 10 minutes. Use timeline to scrub.");
            } else {
                controlPanel.updateTimelineSlider(0, 1); // Reset slider
                instructionLabel.setText("Live buffer is empty. No replay available.");
            }
            updateTimeLapsedDisplay();
        });

        controlPanel.setOnReturnToLiveAction(e -> {
            recordingManager.stopPlayback(); // Ensure any playback is stopped
            recordingManager.loadRecording(new ArrayList<>()); // Clear the main session
            controlPanel.setReplayMode(false); // Switch UI back to live mode

            // Reset UI elements to a clean live state
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
        File file = fileChooser.showSaveDialog(primaryStage);
        if (file == null) { instructionLabel.setText("Save cancelled."); return; }
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
        else if (data instanceof KeyValueData) {
            KeyValueData kv = (KeyValueData) data;
            // Simple escaping for now to handle potential issues in the value string
            payload = String.format("kv:%s,%s", kv.key, kv.value);
        }
        else { return null; }
        return event.timestamp + "|" + payload;
    }

    private void handleOpenRecording() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Recording");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Recording Files", "*.rec"));
        File file = fileChooser.showOpenDialog(primaryStage);
        if (file == null) { instructionLabel.setText("Open cancelled."); return; }
        ArrayList<RecordingManager.RecordedEvent> loadedEvents = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] lineParts = line.split("\\|", 2);
                if (lineParts.length != 2) continue;
                long timestamp = Long.parseLong(lineParts[0]); String payload = lineParts[1];
                UdpMessageData parsedData = null;
                if (payload.startsWith("pos:")) { String c = payload.substring(4); String[] p = c.split(","); if (p.length == 3) parsedData = new PositionData(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])); }
                else if (payload.startsWith("cir:")) { String c = payload.substring(4); String[] p = c.split(","); if (p.length == 2) parsedData = new CircleData(Double.parseDouble(p[0]), Double.parseDouble(p[1])); }
                else if (payload.startsWith("line:")) { String c = payload.substring(5); String[] p = c.split(",", 6); if (p.length == 6) parsedData = new LineData(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]), Integer.parseInt(p[5]));}
                else if (payload.startsWith("txt:")) { parsedData = new TextData(payload.substring(4));}
                else if (payload.startsWith("kv:")) {
                    String c = payload.substring(3);
                    String[] p = c.split(",", 2);
                    if (p.length == 2) parsedData = new KeyValueData(p[0], p[1]);
                }
                if (parsedData != null) loadedEvents.add(new RecordingManager.RecordedEvent(timestamp, parsedData));
            }
            recordingManager.loadRecording(loadedEvents);
            controlPanel.setPlaybackControlsDisabled(loadedEvents.isEmpty());
            controlPanel.updateTimelineSlider(0, recordingManager.getTotalEvents());
            controlPanel.setSaveButtonDisabled(loadedEvents.isEmpty());
            controlPanel.togglePlayPauseButtonIcon(false);
            instructionLabel.setText("Opened: " + file.getName());
            updateTimeLapsedDisplay();
        } catch (Exception e) {
            instructionLabel.setText("Error opening recording: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * This method is called directly by the UDP listener thread.
     * Its only jobs are to add the event to the appropriate buffers and
     * then, if in a live mode, pass the data to the UI thread for processing.
     * It should NOT do any UI updates itself.
     */
    private void handleUdpMessage(UdpMessageData messageData) {
        if (messageData == null) return;

        // 1. Always add the event to the live buffer for instant replay functionality.
        recordingManager.addLiveEvent(messageData);

        // 2. If a formal recording session is active, also add the event to that session.
        if (recordingManager.getCurrentState() == RecordingManager.PlaybackState.RECORDING) {
            recordingManager.addEvent(messageData);
        }

        // 3. If we are in a "live" viewing mode (not playing back or paused),
        //    then schedule the UI update to run on the main JavaFX thread.
        RecordingManager.PlaybackState state = recordingManager.getCurrentState();
        if (state != RecordingManager.PlaybackState.PLAYING && state != RecordingManager.PlaybackState.PAUSED) {
            Platform.runLater(() -> processUdpDataAndUpdateUI(messageData));
        }
    }

    /**
     * This method is ONLY called from the JavaFX Application Thread (via Platform.runLater).
     * It is responsible for taking message data and updating all relevant UI components,
     * such as the robot's position, the field trail, and text displays.
     */
    private void processUdpDataAndUpdateUI(UdpMessageData messageData) {
        if (messageData == null) return;

        // Process the data based on its type
        if (messageData instanceof PositionData) {
            PositionData p = (PositionData) messageData;
            if (robot != null) {
                fieldDisplay.addTrailDot(robot.getXInches(), robot.getYInches());
                robot.setPosition(p.x, p.y, p.heading);
            }
        } else if (messageData instanceof CircleData) {
            CircleData c = (CircleData) messageData;
            if (fieldDisplay != null) {
                fieldDisplay.addDebugCircle(robot.getXInches(), robot.getYInches(), c.radiusInches, c.heading, Color.rgb(255, 165, 0, 0.7));
            }
        } else if (messageData instanceof LineData) {
            LineData l = (LineData) messageData;
            synchronized (namedLinesLock) {
                namedLinesToDraw.put(l.name, l);
            }
        } else if (messageData instanceof TextData) {
            TextData t = (TextData) messageData;
            if (fieldDisplay != null) {
                fieldDisplay.setRobotTextMessage(t.text);
            }
        } else if (messageData instanceof KeyValueData) {
            KeyValueData kv = (KeyValueData) messageData;
            if (keyValueTable != null) {
                keyValueTable.updateValue(kv.key, kv.value);
            }
        }

        // After processing, redraw the UI
        if (messageData instanceof PositionData) {
            // This also updates status text fields and redraws the field
            updateUIFromRobotState();
        } else {
            // For non-positional data, just redraw the field to show new lines, circles, etc.
            fieldDisplay.drawCurrentState();
        }
    }


//    private void handleUdpMessage(UdpMessageData messageData) {
//        if (messageData == null) return;
//        Platform.runLater(() -> {
//            if (recordingManager.getCurrentState() == RecordingManager.PlaybackState.RECORDING) recordingManager.addEvent(messageData);
//            if (messageData instanceof PositionData) { PositionData p = (PositionData)messageData; if(robot!=null){ fieldDisplay.addTrailDot(robot.getXInches(),robot.getYInches()); robot.setPosition(p.x,p.y); robot.setHeading(p.heading);}}
//            else if (messageData instanceof CircleData) { CircleData c = (CircleData)messageData; if(fieldDisplay!=null) fieldDisplay.addDebugCircle(robot.getXInches(),robot.getYInches(),c.radiusInches,c.heading,Color.rgb(255,165,0,0.7));}
//            else if (messageData instanceof LineData) { LineData l = (LineData)messageData; synchronized(namedLinesLock){namedLinesToDraw.put(l.name,l);}}
//            else if (messageData instanceof TextData) { TextData t = (TextData)messageData; if(fieldDisplay!=null) fieldDisplay.setRobotTextMessage(t.text);}
//            else if (messageData instanceof KeyValueData) {
//                KeyValueData kv = (KeyValueData) messageData;
//                if (keyValueTable != null) {
//                    keyValueTable.updateValue(kv.key, kv.value);
//                }
//            }
//            if(messageData instanceof PositionData) updateUIFromRobotState(); else fieldDisplay.drawCurrentState();
//        });
//    }

    private void onPlaybackFinished() {
        Platform.runLater(() -> {
            if (controlPanel != null) {
                controlPanel.togglePlayPauseButtonIcon(false);
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

    private void startUdpPlotListener() {
        try {
            udpPlotListener = new UdpPlotListener(this::handleUdpPlotData); // Uses default port from UdpPlotListener
            udpPlotListenerThread = new Thread(udpPlotListener, "UdpPlotListenerThread");
            udpPlotListenerThread.setDaemon(true);
            udpPlotListenerThread.start();
            System.out.println("UDP Plot Listener started on port " + UdpPlotListener.DEFAULT_PLOT_LISTENER_PORT);
        } catch (Exception e) {
            // You might want a different label or way to show this error if instructionLabel is for field sim
            System.err.println("ERROR: UDP Plot Listener start failed on " + UdpPlotListener.DEFAULT_PLOT_LISTENER_PORT + " - " + e.getMessage());
            if (instructionLabel != null) { // Be cautious if this runs before instructionLabel is ready
                instructionLabel.setText("ERROR: Plot Listener failed");
            }
            e.printStackTrace();
        }
    }

    private void stopApp() {
        if (udpListener != null) udpListener.stopListener();
        if (udpPlotListener != null) udpPlotListener.stopListener();

        if (udpListenerThread != null && udpListenerThread.isAlive()) {
            try {
                udpListenerThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (udpPlotListenerThread != null && udpPlotListenerThread.isAlive()) {
            try {
                udpPlotListenerThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (plotDisplayWindow != null && plotDisplayWindow.isShowing()){ // Close plot window if open
            plotDisplayWindow.hide();
        }
        System.out.println("Exiting application.");
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

    private void handleSceneKeyPress(KeyEvent event) {
        if (isCreatingPath) {
            if (event.getCode() == KeyCode.ESCAPE) {
                finishPathCreation(true); // true for cancelled
                event.consume();
            }
        } else {
            handleRobotMovementKeyPress(event);
        }
    }

    private void handleRobotMovementKeyPress(KeyEvent event) {
        if (robot == null || isCreatingPath) { // Do not move robot if path creation is active
            return;
        }
        double currentX = robot.getXInches();
        double currentY = robot.getYInches();
        double currentHeading_CCW = robot.getHeadingDegrees();
        boolean moved = false;

        double newFieldX = currentX;
        double newFieldY = currentY;
        double angleRad_CCW = Math.toRadians(currentHeading_CCW);

        switch (event.getCode()) {
            case UP:
                newFieldX = currentX + ROBOT_MOVE_INCREMENT_INCHES * Math.cos(angleRad_CCW);
                newFieldY = currentY + ROBOT_MOVE_INCREMENT_INCHES * Math.sin(angleRad_CCW);
                moved = true;
                break;
            case DOWN:
                newFieldX = currentX - ROBOT_MOVE_INCREMENT_INCHES * Math.cos(angleRad_CCW);
                newFieldY = currentY - ROBOT_MOVE_INCREMENT_INCHES * Math.sin(angleRad_CCW);
                moved = true;
                break;
            case LEFT:
                robot.setHeading(currentHeading_CCW + ROBOT_TURN_INCREMENT_DEGREES);
                moved = true;
                break;
            case RIGHT:
                robot.setHeading(currentHeading_CCW - ROBOT_TURN_INCREMENT_DEGREES);
                moved = true;
                break;
            case A: // Strafe Left
                double strafeLeftAngleRad_CCW = Math.toRadians(currentHeading_CCW + 90.0);
                newFieldX = currentX + ROBOT_MOVE_INCREMENT_INCHES * Math.cos(strafeLeftAngleRad_CCW);
                newFieldY = currentY + ROBOT_MOVE_INCREMENT_INCHES * Math.sin(strafeLeftAngleRad_CCW);
                moved = true;
                break;
            case D: // Strafe Right
                double strafeRightAngleRad_CCW = Math.toRadians(currentHeading_CCW - 90.0);
                newFieldX = currentX + ROBOT_MOVE_INCREMENT_INCHES * Math.cos(strafeRightAngleRad_CCW);
                newFieldY = currentY + ROBOT_MOVE_INCREMENT_INCHES * Math.sin(strafeRightAngleRad_CCW);
                moved = true;
                break;
            default:
                // Not a movement key we handle here
                break;
        }

        if (moved) {
            if (event.getCode() == KeyCode.UP || event.getCode() == KeyCode.DOWN ||
                    event.getCode() == KeyCode.A || event.getCode() == KeyCode.D) {
                robot.setPosition(newFieldX, newFieldY);
            }
            if (controlPanel != null) {
                double displayHeading = robot.getHeadingDegrees() % 360;
                if (displayHeading < 0) displayHeading += 360;
                controlPanel.updateRobotStartFields(robot.getXInches(), robot.getYInches(), displayHeading);
            }
            updateUIFromRobotState();
            event.consume(); // Consume the event so it's not processed further (e.g., by focus traversal)
        }
    }
}


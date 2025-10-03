// In PlotDisplay.java
package com.example.ftcfieldsimulator;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.MouseEvent;
import javafx.scene.shape.StrokeLineCap; // For cursor line style
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane; // for layering scrollbar and Y-axis
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
// No need for Affine/Rotate directly here for Task 4, gc.save/restore is enough for text rotation

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.HashMap; // If not already there
import java.util.TreeSet; // For managing readout vertical positions
import java.util.Set;     // For active styles
import java.text.DecimalFormat; // For formatting readout values


public class PlotDisplay extends Pane { // Will now act as a container for Canvas, Y-axis Canvas, Scrollbar

    // --- Configuration Constants ---
    public static final double DEFAULT_PLOT_AREA_WIDTH_PIXELS = 1000; // Visible graph area width
    public static final double DEFAULT_PLOT_AREA_HEIGHT_PIXELS = DEFAULT_PLOT_AREA_WIDTH_PIXELS * (9.0 / 16.0);

    private static final double PADDING_TOP = 30;
    private static final double PADDING_BOTTOM = 50;
    private static final double PADDING_LEFT_FOR_Y_AXIS = 60; // Space reserved for the Y-axis fixed pane
    private static final double PADDING_RIGHT_GRAPH = 20;    // Padding on the very right of scrollable content

    private static final Color MARKER_LINE_COLOR = Color.rgb(100, 100, 100); // Darker Gray
    private static final Color MARKER_TEXT_COLOR = Color.BLACK; // Or make configurable

    private static final double SCROLLBAR_HEIGHT = 20;

    private Canvas mainGraphCanvas; // Canvas for the scrolling graph (grid_x, data, x-labels)
    private GraphicsContext mainGc;
    private Canvas yAxisCanvas;     // Fixed canvas for Y-axis and its grid lines
    private GraphicsContext yAxisGc;

    private ScrollBar hScrollBar;
    private StackPane graphContainer; // To hold the mainGraphCanvas

    private double visibleGraphWidth;  // Width of the viewport for the graph
    private double visibleGraphHeight; // Height of the viewport for the graph (and Y-axis)

    // --- Data Storage & State ---
    private final List<PlotDataEvent> plotEvents = new ArrayList<>();
    private static final int MAX_PLOT_EVENTS = 100000;

    private double currentMinY = 0.0;
    private double currentMaxY = 100.0;
    private String yAxisUnit = "Value";

    private long firstTimestamp = -1;
    private long lastTimestamp = -1; // To determine total time span for scrollbar
    private double pixelsPerMillisecond = 0.02; // Start with 20 pixels per second
    private static final double MIN_PIXELS_PER_MS = 0.0001;
    private static final double MAX_PIXELS_PER_MS = 1.0;

    private static final double X_AXIS_LABEL_AREA_HEIGHT_ON_MAIN_CANVAS = 30; // e.g., 30 pixels for ticks & labels

    private double currentScrollOffsetMs = 0; // How many ms the view is scrolled from t0

    // --- Auto-Scroll State ---
    private final BooleanProperty autoScrollEnabled = new SimpleBooleanProperty(true); // Default true
    private PlotDisplayControlPanel controlPanelProxy; // To update the checkbox in the UI

    // +++ Data Readout State for Points/Lines +++
    private static final Color READOUT_TEXT_COLOR_DEFAULT = Color.BLACK; // Fallback
    private static final double READOUT_TEXT_X_OFFSET = 7;  // Pixels to the right of cursor line
    private static final double READOUT_TEXT_Y_SPACING = 12; // Min vertical pixels between readouts
    private DecimalFormat readoutValueFormat = new DecimalFormat("#0.0#"); // Format for Y values

    // Stores the latest Y value string to display for each style near the cursor
    private final Map<Integer, String> styleToReadoutValueString = new HashMap<>();
    // Stores the calculated screen Y coordinate for each style's readout text
    private final Map<Integer, Double> styleToReadoutScreenY = new HashMap<>();
    // Stores the actual data Y value (not screen Y) for sorting/staggering
    private final Map<Integer, Double> styleToReadoutDataY = new HashMap<>();
    // Stores the color for each style's readout text
    private final Map<Integer, Color> styleToReadoutColor = new HashMap<>();

    // +++ Key-Value Data Storage +++
    private record TimestampedStringValue(long timestamp, String value) implements Comparable<TimestampedStringValue> {
        @Override
        public int compareTo(TimestampedStringValue other) {
            return Long.compare(this.timestamp, other.timestamp);
        }
    }
    private final Map<String, List<TimestampedStringValue>> keyValueStore = new ConcurrentHashMap<>();

    // +++ Mouse Cursor State +++
    private boolean isMouseInPlotArea = false;
    private double mousePlotX = -1; // X coordinate on mainGraphCanvas
    private long currentCursorTimeMs = -1; // Timestamp corresponding to mouse position
    private static final Color CURSOR_LINE_COLOR = Color.rgb(255, 140, 0); // Dark Orange (was Color.YELLOW)
    private static final Color[] POINT_COLORS = { /* ... same as before ... */
            Color.RED, Color.BLUE, Color.GREEN, Color.rgb(255, 165, 0), Color.MAGENTA
    };
    // +++ Define Line Styles (Colors and Dash Patterns) +++
    private static class LineStyle {
        final Color color;
        final double width;
        final double[] dashArray; // null or empty for solid

        LineStyle(Color color, double width, double[] dashArray) {
            this.color = color;
            this.width = width;
            this.dashArray = dashArray;
        }
    }
    private static final LineStyle[] LINE_STYLES = {
            new LineStyle(Color.RED, 2, null),          // Style 1: Solid Red
            new LineStyle(Color.BLUE, 2, null),         // Style 2: Solid Blue
            new LineStyle(Color.GREEN, 2, null),        // Style 3: Solid Green
            new LineStyle(Color.ORANGE, 2, null),       // Style 4: Solid Orange
            new LineStyle(Color.CYAN, 2, null),         // Style 5: Solid Cyan
            new LineStyle(Color.PURPLE, 2, new double[]{5,3}), // Style 6: Dashed Purple
            new LineStyle(Color.LIMEGREEN, 2, new double[]{8,4}),// Style 7: Dashed Lime
            new LineStyle(Color.HOTPINK, 2, new double[]{2,3}), // Style 8: Dotted Pink
            new LineStyle(Color.TEAL, 2, new double[]{3,4}),    // Style 9: Dotted Teal
            new LineStyle(Color.BLACK, 1, null)         // Style 10: Solid Black (thin)
    };
    private final PlotPoint[] lastLinePointByStyle = new PlotPoint[LINE_STYLES.length]; // For line_y


    public PlotDisplay(double requestedVisibleWidth, double requestedVisibleHeight) {
        // requestedVisibleWidth is the width of the data plotting area (for mainGraphCanvas)
        // requestedVisibleHeight is the height of the data plotting area (for mainGraphCanvas & yAxisCanvas's data part)
        this.visibleGraphWidth = requestedVisibleWidth;
        this.visibleGraphHeight = requestedVisibleHeight; // This is the height for the actual data plotting grid

        // --- Y-Axis Canvas (Fixed) ---
        // Its total height needs to accommodate its own PADDING_TOP and PADDING_BOTTOM
        // around the visibleGraphHeight data area.
        this.yAxisCanvas = new Canvas(PADDING_LEFT_FOR_Y_AXIS,
                PADDING_TOP + this.visibleGraphHeight + PADDING_BOTTOM);
        this.yAxisGc = yAxisCanvas.getGraphicsContext2D();

        // --- Main Graph Canvas (Scrollable) ---
        // Its dimensions are purely for the data plotting area.
        this.mainGraphCanvas = new Canvas(this.visibleGraphWidth,
                this.visibleGraphHeight + X_AXIS_LABEL_AREA_HEIGHT_ON_MAIN_CANVAS);
        this.mainGc = mainGraphCanvas.getGraphicsContext2D();

        this.graphContainer = new StackPane(mainGraphCanvas);
        // The graphContainer's preferred size is that of the data plotting area.
        this.graphContainer.setPrefSize(this.visibleGraphWidth,
                this.visibleGraphHeight + X_AXIS_LABEL_AREA_HEIGHT_ON_MAIN_CANVAS);

        // The clip is a rectangle matching the intended visible area of the main graph.
        // Its coordinates are relative to the graphContainer itself.
        javafx.scene.shape.Rectangle clipRect = new javafx.scene.shape.Rectangle();
        clipRect.widthProperty().bind(this.graphContainer.widthProperty());  // Bind to actual width
        clipRect.heightProperty().bind(this.graphContainer.heightProperty());// Bind to actual height
        this.graphContainer.setClip(clipRect);

        // --- Horizontal Scrollbar ---
        this.hScrollBar = new ScrollBar();
        this.hScrollBar.setOrientation(Orientation.HORIZONTAL);
        this.hScrollBar.setMin(0);
        this.hScrollBar.setMax(0); // Will be updated based on data
        this.hScrollBar.setVisibleAmount(this.visibleGraphWidth); // Viewport size in scrollbar units

        this.hScrollBar.valueProperty().addListener((obs, oldVal, newVal) -> {
            double newScrollPixelValue = newVal.doubleValue();
            mainGraphCanvas.setTranslateX(-newScrollPixelValue);

            if (pixelsPerMillisecond > 0 && !Double.isNaN(pixelsPerMillisecond) && !Double.isInfinite(pixelsPerMillisecond)) {
                currentScrollOffsetMs = newScrollPixelValue / pixelsPerMillisecond;
            } else {
                currentScrollOffsetMs = 0;
            }
            redrawMainGraph();
        });

        this.hScrollBar.pressedProperty().addListener((obs, wasPressed, isPressed) -> {
            if (isPressed && autoScrollEnabled.get()) {
                // User has clicked/started dragging the scrollbar
                setAutoScrollEnabled(false); // This will also update the controlPanelProxy's checkbox
            }
        });

        // --- Layout within this Pane ---
        // yAxisCanvas is positioned at the top-left of this PlotDisplay pane.
        // It manages its own internal padding for its content.
        this.yAxisCanvas.setLayoutX(0);
        this.yAxisCanvas.setLayoutY(0);

        // graphContainer (holding mainGraphCanvas) is positioned to the right of yAxisCanvas's area,
        // AND its top edge is shifted down by PADDING_TOP to align with where yAxisCanvas
        // starts drawing its data grid.
        this.graphContainer.setLayoutX(PADDING_LEFT_FOR_Y_AXIS);
        this.graphContainer.setLayoutY(PADDING_TOP); // Key alignment for horizontal grids

        // hScrollBar is positioned below the graphContainer and yAxisCanvas's main content area.
        this.hScrollBar.setLayoutX(PADDING_LEFT_FOR_Y_AXIS);
        this.hScrollBar.setLayoutY(PADDING_TOP + this.visibleGraphHeight + X_AXIS_LABEL_AREA_HEIGHT_ON_MAIN_CANVAS);
        this.hScrollBar.setPrefWidth(this.visibleGraphWidth);
        this.hScrollBar.setPrefHeight(SCROLLBAR_HEIGHT); // Explicitly set scrollbar height

        getChildren().addAll(this.yAxisCanvas, this.graphContainer, this.hScrollBar);

        // Set total preferred size of this PlotDisplay Pane, accommodating all parts
        double totalPaneWidth = PADDING_LEFT_FOR_Y_AXIS + this.visibleGraphWidth + PADDING_RIGHT_GRAPH; // PADDING_RIGHT_GRAPH for space on the canvas
        double totalPaneHeight = PADDING_TOP + this.visibleGraphHeight + X_AXIS_LABEL_AREA_HEIGHT_ON_MAIN_CANVAS + SCROLLBAR_HEIGHT;
        setPrefSize(totalPaneWidth, totalPaneHeight);

        // Ensure the mainGraphCanvas itself also considers PADDING_RIGHT_GRAPH if content can go there
        // This is more about its internal width when calculating scroll extent.
        // The `updateCanvasWidthAndScrollbar` should use `visibleGraphWidth` for the viewport calculation,
        // but `requiredCanvasWidth` can include `PADDING_RIGHT_GRAPH`.
        // Let's refine `updateCanvasWidthAndScrollbar` slightly: requiredCanvasWidth should be time * ppm + PADDING_RIGHT_GRAPH

        // +++ Mouse Listeners for Cursor +++
        mainGraphCanvas.setOnMouseMoved(this::handleMouseMovedOverPlot);
        mainGraphCanvas.setOnMouseExited(this::handleMouseExitedPlot);
        // If graphContainer is used for mouse events (e.g. to catch events over padding):
        // graphContainer.setOnMouseMoved(this::handleMouseMovedOverPlot);
        // graphContainer.setOnMouseExited(this::handleMouseExitedPlot);
        // Ensure coordinates are correctly translated if using graphContainer.
        // For simplicity, starting with mainGraphCanvas.

        redrawFullPlot();
    }

    public void setControlPanelProxy(PlotDisplayControlPanel panel) {
        this.controlPanelProxy = panel;
        // Ensure checkbox state matches initial autoScrollEnabled state
        if (this.controlPanelProxy != null) {
            this.controlPanelProxy.setFollowRealTimeSelected(this.autoScrollEnabled.get());
        }
    }

    public boolean isAutoScrollEnabled() {
        return autoScrollEnabled.get();
    }

    public BooleanProperty autoScrollEnabledProperty() {
        return autoScrollEnabled;
    }

    public void setAutoScrollEnabled(boolean enabled) {
        boolean oldState = autoScrollEnabled.get();
        autoScrollEnabled.set(enabled);

        // Update the checkbox in the control panel if its state differs
        if (controlPanelProxy != null && controlPanelProxy.isFollowRealTimeSelected() != enabled) {
            controlPanelProxy.setFollowRealTimeSelected(enabled);
        }

        // If auto-scroll is re-enabled, scroll to the latest data
        if (enabled && !oldState && lastTimestamp != -1) {
            scrollToTimestamp(lastTimestamp);
        }
    }

    // +++ Mouse Event Handlers for Cursor +++
    private void handleMouseMovedOverPlot(MouseEvent event) {
        isMouseInPlotArea = true;
        // event.getX() is relative to mainGraphCanvas's top-left corner (0,0 of its potentially wider area)
        mousePlotX = event.getX();

        if (pixelsPerMillisecond > 0 && firstTimestamp != -1) {
            // currentScrollOffsetMs is time at the *visible* left edge of mainGraphCanvas
            // mousePlotX is pixel offset from *absolute* left of (potentially wider) mainGraphCanvas
            // We need time at mousePlotX:
            currentCursorTimeMs = firstTimestamp + (long)(mousePlotX / pixelsPerMillisecond);
        } else {
            currentCursorTimeMs = -1;
        }

        // System.out.println("Cursor Time: " + currentCursorTimeMs + " (mousePlotX: " + mousePlotX + ")"); // Debug

        refreshKeyValueTable(); // Update KV table based on cursor position
        updateAndDrawDataReadouts();
        redrawMainGraph(); // To draw the cursor line and eventually readouts
    }

    private void handleMouseExitedPlot(MouseEvent event) {
        isMouseInPlotArea = false;
        currentCursorTimeMs = -1; // Invalidate cursor time
        mousePlotX = -1;

        refreshKeyValueTable(); // Revert KV table to "latest" values

        // +++ Clear data readouts when mouse exits +++
        styleToReadoutValueString.clear();
        styleToReadoutScreenY.clear();
        styleToReadoutDataY.clear();
        styleToReadoutColor.clear();

        redrawMainGraph(); // To remove cursor line and readouts
    }

    // +++ Key-Value Table Update Logic +++
    private void refreshKeyValueTable() {
        if (controlPanelProxy == null) return;

        List<PlotDisplayControlPanel.KeyTableEntry> entries = new ArrayList<>();
        if (isMouseInPlotArea && currentCursorTimeMs != -1) {
            // Cursor is active: get values at or before cursor time
            synchronized (keyValueStore) {
                for (Map.Entry<String, List<TimestampedStringValue>> entry : keyValueStore.entrySet()) {
                    String key = entry.getKey();
                    List<TimestampedStringValue> values = entry.getValue();
                    TimestampedStringValue relevantValue = null;
                    // Find the last value with timestamp <= currentCursorTimeMs
                    // Values list is sorted by timestamp.
                    for (int i = values.size() - 1; i >= 0; i--) {
                        if (values.get(i).timestamp() <= currentCursorTimeMs) {
                            relevantValue = values.get(i);
                            break;
                        }
                    }
                    if (relevantValue != null) {
                        String timeStr = formatTimeForTable(relevantValue.timestamp());
                        entries.add(new PlotDisplayControlPanel.KeyTableEntry(timeStr, key, relevantValue.value()));
                    }
                    // else: Per your Q2 answer, if cursor is before first value for this key, row doesn't appear
                }
            }
        } else {
            // Cursor is not active: get latest values
            synchronized (keyValueStore) {
                for (Map.Entry<String, List<TimestampedStringValue>> entry : keyValueStore.entrySet()) {
                    String key = entry.getKey();
                    List<TimestampedStringValue> values = entry.getValue();
                    if (!values.isEmpty()) {
                        TimestampedStringValue latestValue = values.get(values.size() - 1); // List is sorted
                        String timeStr = formatTimeForTable(latestValue.timestamp());
                        entries.add(new PlotDisplayControlPanel.KeyTableEntry(timeStr, key, latestValue.value()));
                    }
                }
            }
        }
        // Sort entries by key for consistent table order
        entries.sort(Comparator.comparing(PlotDisplayControlPanel.KeyTableEntry::getKey));

        Platform.runLater(() -> controlPanelProxy.updateKeyValueTable(entries));
    }

    private String formatTimeForTable(long absoluteTimestamp) {
        if (firstTimestamp == -1) return "N/A";
        double relativeSeconds = (absoluteTimestamp - firstTimestamp) / 1000.0;
        return String.format(Locale.US, "%.2f", relativeSeconds);
    }

    // +++ Helper Method to Scroll to a Specific Timestamp +++
    private void scrollToTimestamp(long targetTimestampMs) {
        if (firstTimestamp == -1 || pixelsPerMillisecond <= 0 || hScrollBar.isDisabled()) return;

        double timeSpanOfViewPortMs = visibleGraphWidth / pixelsPerMillisecond;

        // Calculate the target scroll offset in milliseconds from the start (firstTimestamp)
        // Aim to place targetTimestampMs such that it's near the right edge,
        // e.g., 95% of the viewport width is to its left.
        double desiredTimeAtLeftEdgeMs = (targetTimestampMs - firstTimestamp) - (timeSpanOfViewPortMs * 0.95);
        if (desiredTimeAtLeftEdgeMs < 0) {
            desiredTimeAtLeftEdgeMs = 0; // Don't scroll before the beginning of data
        }

        double newScrollPixelValue = desiredTimeAtLeftEdgeMs * pixelsPerMillisecond;

        // Ensure the new scroll value is within valid scrollbar bounds
        double maxPossibleScrollValue = hScrollBar.getMax() - hScrollBar.getVisibleAmount();
        if (maxPossibleScrollValue < 0) maxPossibleScrollValue = 0;

        newScrollPixelValue = MathUtil.clip(newScrollPixelValue, 0, maxPossibleScrollValue);

        // Only set if significantly different to avoid unnecessary redraws or micro-jitters
        if (Math.abs(hScrollBar.getValue() - newScrollPixelValue) > 0.5) { // Threshold of 0.5 pixels
            hScrollBar.setValue(newScrollPixelValue);
            // The scrollbar's valueProperty listener will update currentScrollOffsetMs and call redrawMainGraph().
        }
    }

    private void updateCanvasWidthAndScrollbar() {
        if (firstTimestamp == -1 || lastTimestamp == -1 || firstTimestamp > lastTimestamp) {
            mainGraphCanvas.setWidth(visibleGraphWidth + PADDING_RIGHT_GRAPH);
            mainGraphCanvas.setTranslateX(0);
            hScrollBar.setMin(0);
            hScrollBar.setMax(0); // Effectively no content beyond viewport
            hScrollBar.setValue(0);
            hScrollBar.setVisibleAmount(visibleGraphWidth); // Thumb fills if max is also visibleGraphWidth or less
            hScrollBar.setDisable(true);
            currentScrollOffsetMs = 0;
            return;
        }

        long totalDurationMs = lastTimestamp - firstTimestamp;
        double totalDataPixelSpan = totalDurationMs * pixelsPerMillisecond;
        double totalContentWidth = totalDataPixelSpan + PADDING_RIGHT_GRAPH;

        // Canvas width must be at least the viewport width, or total content width if larger
        double requiredCanvasWidth = Math.max(totalContentWidth, visibleGraphWidth);
        mainGraphCanvas.setWidth(requiredCanvasWidth);

        // --- ScrollBar Configuration Revision ---
        // Max should be the total width of the content that can be laid out.
        // If totalContentWidth is less than visibleGraphWidth, we effectively don't need to scroll.
        // The scrollbar's maximum value should represent the upper bound of the scrollable content.
        hScrollBar.setMin(0);
        hScrollBar.setMax(totalContentWidth); // Total extent of what could be shown
        hScrollBar.setVisibleAmount(visibleGraphWidth); // How much of that extent is visible at once

        // Disable scrollbar if all content fits or there's nothing to scroll.
        // The scrollbar becomes non-interactive if max <= visibleAmount in practical terms.
        boolean scrollbarShouldBeDisabled = totalContentWidth <= visibleGraphWidth;
        hScrollBar.setDisable(scrollbarShouldBeDisabled);

        if (scrollbarShouldBeDisabled) {
            hScrollBar.setValue(0); // Reset scroll position
            mainGraphCanvas.setTranslateX(0);
            currentScrollOffsetMs = 0;
        } else {
            // If the scrollbar was previously at a value that's now invalid because
            // content shrank (but still scrollable), clamp it.
            // The maximum *value* the scrollbar can take is max - visibleAmount.
            double maxScrollValue = hScrollBar.getMax() - hScrollBar.getVisibleAmount();
            if (maxScrollValue < 0) maxScrollValue = 0; // Should not happen if disabled correctly

            if (hScrollBar.getValue() > maxScrollValue) {
                hScrollBar.setValue(maxScrollValue);
            }
            // Update currentScrollOffsetMs based on the current (possibly clamped) scrollbar value
            if (pixelsPerMillisecond > 0) {
                currentScrollOffsetMs = hScrollBar.getValue() / pixelsPerMillisecond;
            } else {
                currentScrollOffsetMs = 0;
            }
        }
        // No explicit call to mainGraphCanvas.setTranslateX here,
        // as hScrollBar.setValue() will trigger the listener which handles translation.
    }

    public void setYLimits(double minY, double maxY) {
        if (maxY <= minY) return;
        this.currentMinY = minY;
        this.currentMaxY = maxY;
        redrawFullPlot(); // Redraw Y-axis and potentially data scaling
    }

    public void setYUnit(String unit) {
        this.yAxisUnit = (unit == null || unit.trim().isEmpty()) ? "Value" : unit;
        redrawYAxis(); // Only Y-axis needs repaint
    }

    public void addPlotEvent(PlotDataEvent event) {
        if (event == null) return;

        boolean updateKeyValueTableNeeded = false; // Flag to update KV table

        boolean isFirstEvent = (firstTimestamp == -1);
        if (isFirstEvent) {
            firstTimestamp = event.getTimestamp();
        }
        // Update lastTimestamp regardless
        if (lastTimestamp == -1 || event.getTimestamp() > lastTimestamp) {
            lastTimestamp = event.getTimestamp();
        }

        synchronized (plotEvents) {
            if (!(event instanceof PlotKeyValueEvent)) { // Don't add KV events to the main graph events
                if (plotEvents.size() >= MAX_PLOT_EVENTS) {
                    // If removing, need to check if firstTimestamp needs update (complex, handle later if necessary)
                    // For now, simple removal from start. This could make plot jump if firstTimestamp changes.
                    PlotDataEvent removed = plotEvents.remove(0);
                    if (firstTimestamp == removed.getTimestamp() && !plotEvents.isEmpty()) {
                        firstTimestamp = plotEvents.get(0).getTimestamp();
                    } else if (plotEvents.isEmpty()) {
                        firstTimestamp = -1;
                        lastTimestamp = -1;
                    }
                }
                plotEvents.add(event);
            }

            if (event instanceof PlotKeyValueEvent) {
                PlotKeyValueEvent kvEvent = (PlotKeyValueEvent) event;
                keyValueStore.computeIfAbsent(kvEvent.getKey(), k -> new ArrayList<>())
                        .add(new TimestampedStringValue(kvEvent.getTimestamp(), kvEvent.getValue()));
                // Sort the list for this key after adding (important for finding value at cursor)
                keyValueStore.get(kvEvent.getKey()).sort(null); // Natural sort by timestamp
                updateKeyValueTableNeeded = true;
            } else if (event instanceof PlotYLimitsEvent) {
                PlotYLimitsEvent yle = (PlotYLimitsEvent) event;
                setYLimits(yle.getMinY(), yle.getMaxY()); // Will redraw all
                // No return here, allow auto-scroll to potentially happen if enabled
            } else if (event instanceof PlotYUnitsEvent) {
                PlotYUnitsEvent yue = (PlotYUnitsEvent) event;
                setYUnit(yue.getUnit()); // Will redraw Y-axis
                return; // Y-units change doesn't affect scroll or main graph data positions
            }
        }
        updateCanvasWidthAndScrollbar();

        if (autoScrollEnabled.get() && !(event instanceof PlotKeyValueEvent)) { // Don't auto-scroll for KV events alone
            scrollToTimestamp(event.getTimestamp());
        }

        if (updateKeyValueTableNeeded) {
            refreshKeyValueTable(); // Update table based on new KV data
        }

        redrawMainGraph(); // Redraw main graph with new data
        if (isFirstEvent || (event instanceof PlotYLimitsEvent)) {
            redrawYAxis();
        }
    }

    public void clearPlot() {
        synchronized (plotEvents) {
            plotEvents.clear();
        }
        synchronized (keyValueStore) { // If accessed from multiple threads (e.g. UDP + UI)
            keyValueStore.clear();
        }
        firstTimestamp = -1;
        lastTimestamp = -1;
        currentScrollOffsetMs = 0;
        mainGraphCanvas.setTranslateX(0);
        hScrollBar.setValue(0);
        // Reset last line points
        for(int i=0; i < lastLinePointByStyle.length; i++) lastLinePointByStyle[i] = null;

        updateCanvasWidthAndScrollbar(); // Reset scrollbar based on no data
        refreshKeyValueTable(); // Update table to show it's empty
        redrawFullPlot();
    }

    public void stretchTimeAxis(double factor) {
        double oldPixelsPerMs = pixelsPerMillisecond;
        pixelsPerMillisecond *= factor;
        pixelsPerMillisecond = MathUtil.clip(pixelsPerMillisecond, MIN_PIXELS_PER_MS, MAX_PIXELS_PER_MS);

        // When time is stretched, user might want to break out of auto-scroll
        // if they are manually adjusting zoom for a specific region.
        if (autoScrollEnabled.get()) {
            // Option 1: Disable auto-scroll when user zooms
            // setAutoScrollEnabled(false);

            // Option 2: Re-apply auto-scroll to the new end after zoom
            // This might be jarring if they zoomed to focus on an old area.
            // Let's go with disabling it if they are actively zooming.
            setAutoScrollEnabled(false);
        }

        if (Math.abs(oldPixelsPerMs - pixelsPerMillisecond) > 1e-9 && firstTimestamp != -1) {
            // Adjust scroll offset to keep the center of the view (approximately) the same
            double viewCenterTimeMs = currentScrollOffsetMs + (visibleGraphWidth / (2 * oldPixelsPerMs));
            updateCanvasWidthAndScrollbar(); // Update canvas width first based on new scale

            // Recalculate scroll value to keep viewCenterTimeMs in the middle of the new viewport
            double newScrollValuePixels = (viewCenterTimeMs * pixelsPerMillisecond) - (visibleGraphWidth / 2.0);
            newScrollValuePixels = MathUtil.clip(newScrollValuePixels, 0, hScrollBar.getMax() - visibleGraphWidth);
            if(hScrollBar.getMax() <= visibleGraphWidth) newScrollValuePixels = 0; // If content fits, scroll to start

            hScrollBar.setValue(newScrollValuePixels); // This will trigger redraw via listener
            currentScrollOffsetMs = hScrollBar.getValue() / pixelsPerMillisecond; // update currentScrollOffsetMs
        } else {
            updateCanvasWidthAndScrollbar(); // Just update bounds
        }
        redrawFullPlot(); // Redraw everything with new scale
    }


    private void redrawFullPlot() {
        redrawYAxis();
        redrawMainGraph();
    }

    private void redrawYAxis() {
        yAxisGc.clearRect(0, 0, yAxisCanvas.getWidth(), yAxisCanvas.getHeight());
        yAxisGc.setFill(Color.WHITE); // Background for Y-axis area
        yAxisGc.fillRect(0,0, yAxisCanvas.getWidth(), yAxisCanvas.getHeight());

        // Y-axis line is drawn relative to its own canvas coordinates
        double yAxisLineX = PADDING_LEFT_FOR_Y_AXIS -1; // Draw it at the very right of yAxisCanvas

        // Draw horizontal grid lines on Y-axis canvas
        yAxisGc.setStroke(Color.LIGHTGRAY);
        yAxisGc.setLineWidth(0.5);
        int numHorizontalGridLines = 10; // Match main graph
        for (int i = 0; i <= numHorizontalGridLines; i++) {
            double y = PADDING_TOP + (visibleGraphHeight / numHorizontalGridLines) * i;
            yAxisGc.strokeLine(0, y, PADDING_LEFT_FOR_Y_AXIS, y); // Extend to left edge
        }


        yAxisGc.setStroke(Color.BLACK);
        yAxisGc.setLineWidth(1.0);
        yAxisGc.setFill(Color.BLACK);
        yAxisGc.setFont(Font.font("Arial", 10));

        yAxisGc.strokeLine(yAxisLineX, PADDING_TOP, yAxisLineX, PADDING_TOP + visibleGraphHeight);

        yAxisGc.setTextAlign(TextAlignment.RIGHT);
        int numYLabels = 20;
        double yRange = currentMaxY - currentMinY;
        if (yRange <= 1e-6) yRange = 1.0;

        for (int i = 0; i <= numYLabels; i++) {
            double value = currentMinY + (yRange / numYLabels) * i;
            double yPos = PADDING_TOP + visibleGraphHeight - ((value - currentMinY) / yRange * visibleGraphHeight);
            if (yPos >= PADDING_TOP - 5 && yPos <= PADDING_TOP + visibleGraphHeight + 5) { // Generous bounds for labels
                yAxisGc.fillText(formatNiceNumber(value, yRange), yAxisLineX - 5, yPos + 4);
                yAxisGc.strokeLine(yAxisLineX - 3, yPos, yAxisLineX, yPos);
            }
        }

        yAxisGc.save();
        yAxisGc.setTextAlign(TextAlignment.CENTER);
        yAxisGc.translate(15, PADDING_TOP + visibleGraphHeight / 2); // Position for rotated text
        yAxisGc.rotate(-90);
        yAxisGc.fillText(yAxisUnit, 0, 0);
        yAxisGc.restore();
    }

    private void redrawMainGraph() {
        // mainGraphCanvas is now taller: height = visibleGraphHeight + X_AXIS_LABEL_AREA_HEIGHT_ON_MAIN_CANVAS
        mainGc.clearRect(0, 0, mainGraphCanvas.getWidth(), mainGraphCanvas.getHeight());

        // Horizontal Grid Lines - only draw within the data area (0 to visibleGraphHeight)
        mainGc.setStroke(Color.LIGHTGRAY);
        mainGc.setLineWidth(0.5);
        int numHorizontalGridLines = 10;
        for (int i = 0; i <= numHorizontalGridLines; i++) {
            double yPosOnCanvas = (this.visibleGraphHeight / numHorizontalGridLines) * i;
            // Clamp to ensure it's within the data plotting area (0 to visibleGraphHeight-1)
            if (yPosOnCanvas >= this.visibleGraphHeight) {
                yPosOnCanvas = this.visibleGraphHeight -1;
                if (yPosOnCanvas <0) yPosOnCanvas = 0; // if visibleGraphHeight is tiny
            }
            mainGc.strokeLine(0, yPosOnCanvas, mainGraphCanvas.getWidth(), yPosOnCanvas);
        }

        // Vertical Grid Lines - draw through the data area only (0 to visibleGraphHeight)
        if (firstTimestamp != -1) {
            double gridTimeStepMs = 500;
            // ... (adjust gridTimeStepMs) ...
            if(pixelsPerMillisecond * gridTimeStepMs < 20) gridTimeStepMs = 1000;
            if(pixelsPerMillisecond * gridTimeStepMs < 20) gridTimeStepMs = 2000;
            if(pixelsPerMillisecond * gridTimeStepMs < 20) gridTimeStepMs = 5000;

            long totalDurationMs = (lastTimestamp > firstTimestamp) ? (lastTimestamp - firstTimestamp) : 0;
            long maxTOnCanvas = firstTimestamp + (long)(mainGraphCanvas.getWidth() / pixelsPerMillisecond) + (long)gridTimeStepMs;

            for (long t = (long)(Math.ceil(firstTimestamp / gridTimeStepMs) * gridTimeStepMs); t <= maxTOnCanvas; t += gridTimeStepMs) {
                if (t < firstTimestamp) continue;
                double xScreen = timeMsToScreenX(t);
                if (xScreen >= 0 && xScreen <= mainGraphCanvas.getWidth()) {
                    mainGc.strokeLine(xScreen, 0, xScreen, this.visibleGraphHeight -1); // End at bottom of data area
                }
            }
        }

        // X-axis line - at the bottom of the data plotting area
        double xAxisLineY = this.visibleGraphHeight - 1;
        if (xAxisLineY < 0) xAxisLineY = 0;

        mainGc.setStroke(Color.BLACK);
        mainGc.setLineWidth(1.0);
        mainGc.strokeLine(0, xAxisLineY, mainGraphCanvas.getWidth(), xAxisLineY);

        // X-axis Labels and Ticks - drawn BELOW xAxisLineY, in the extra space
        mainGc.setFill(Color.BLACK);
        mainGc.setFont(Font.font("Arial", 10));
        mainGc.setTextAlign(TextAlignment.CENTER);

        if (firstTimestamp != -1) {
            double gridTimeStepMs = 500;
            // ... (adjust gridTimeStepMs) ...
            if(pixelsPerMillisecond * gridTimeStepMs < 20) gridTimeStepMs = 1000;
            if(pixelsPerMillisecond * gridTimeStepMs < 20) gridTimeStepMs = 2000;
            if(pixelsPerMillisecond * gridTimeStepMs < 20) gridTimeStepMs = 5000;

            long totalDurationMs = (lastTimestamp > firstTimestamp) ? (lastTimestamp - firstTimestamp) : 0;
            long maxTOnCanvas = firstTimestamp + (long)(mainGraphCanvas.getWidth() / pixelsPerMillisecond) + (long)gridTimeStepMs;

            for (long t = (long)(Math.ceil(firstTimestamp / gridTimeStepMs) * gridTimeStepMs); t <= maxTOnCanvas; t += gridTimeStepMs) {
                if (t < firstTimestamp) continue;

                double xScreenOnCanvas = timeMsToScreenX(t);
                double xInView = xScreenOnCanvas + mainGraphCanvas.getTranslateX();

                if (xInView >= -visibleGraphWidth * 0.5 && xInView <= visibleGraphWidth * 1.5) {
                    String labelText = String.format(Locale.US, "%.1fs", (t - firstTimestamp) / 1000.0);

                    // --- NEW Y POSITIONS FOR X-AXIS LABELS AND TICKS ---
                    // Draw them in the X_AXIS_LABEL_AREA_HEIGHT_ON_MAIN_CANVAS below xAxisLineY
                    double tickStartY = xAxisLineY;                                 // Ticks start ON the X-axis line
                    double tickEndY = xAxisLineY + 4;                               // Ticks go 4px down
                    double labelTextY = xAxisLineY + 15;                            // Text baseline 15px below X-axis line

                    // Ensure these are within the new taller mainGraphCanvas height
                    if (tickEndY > mainGraphCanvas.getHeight() -1) tickEndY = mainGraphCanvas.getHeight() -1;
                    if (labelTextY > mainGraphCanvas.getHeight() - 5) labelTextY = mainGraphCanvas.getHeight() - 5; // Keep text from bottom edge

                    mainGc.strokeLine(xScreenOnCanvas, tickStartY, xScreenOnCanvas, tickEndY);
                    mainGc.fillText(labelText, xScreenOnCanvas, labelTextY);

                    System.out.println("X-Label: t=" + ((t - firstTimestamp)/1000.0) + "s, xScreen=" + xScreenOnCanvas + ", labelY=" + labelTextY);
                }
            }
        }

        drawData(); // Draws points, lines, text annotations

        // +++ Draw Mouse Cursor Line +++
        if (isMouseInPlotArea && mousePlotX >= 0 && currentCursorTimeMs != -1) {
            mainGc.save();
            mainGc.setStroke(CURSOR_LINE_COLOR);
            mainGc.setLineWidth(1.0);
            mainGc.setLineDashes(5, 3); // Dashed line for cursor
            mainGc.setLineCap(StrokeLineCap.BUTT);
            // mousePlotX is already in mainGraphCanvas coordinates (relative to its potentially wider scrollable content)
            mainGc.strokeLine(mousePlotX, 0, mousePlotX, this.visibleGraphHeight -1); // Span data area
            mainGc.restore();


            // +++ Draw the actual text readouts +++
            drawCursorDataReadouts(); // Uses stored values from styleToReadout... maps
        }
    }

    private String formatNiceNumber(double value, double range) { /* ... same as before ... */
        if (range >= 200 || Math.abs(value) >= 100) return String.format(Locale.US, "%.0f", value);
        if (range >= 20 || Math.abs(value) >= 10) return String.format(Locale.US, "%.1f", value);
        if (range >= 1 || Math.abs(value) >= 1) return String.format(Locale.US, "%.2f", value);
        return String.format(Locale.US, "%.3f", value);
    }

    private void drawData() {
        if (firstTimestamp == -1) return;

        // Calculate visible time range based on current scroll and scale
        long viewStartTimeMs = firstTimestamp + (long)currentScrollOffsetMs;
        long viewEndTimeMs = viewStartTimeMs + (long)(visibleGraphWidth / pixelsPerMillisecond);

        synchronized (plotEvents) {
            // Reset last line points for each style before redrawing lines for the current view.
            // This is crucial because we only draw segments based on the *immediately preceding* point
            // of the same style. If data is added out of order or some events are culled,
            // this ensures lines don't jump across large gaps incorrectly.
            // For very large datasets, a more optimized approach might find the last point *within the view*.
            for(int i=0; i < lastLinePointByStyle.length; i++) {
                lastLinePointByStyle[i] = null;
            }

            // Iterate through all events to find the last point for each style *before* the current view window.
            // This helps connect lines smoothly if the first point of a line segment is off-screen left.
            for (PlotDataEvent event : plotEvents) {
                if (event.getTimestamp() < viewStartTimeMs) {
                    if (event instanceof PlotLineEvent) {
                        PlotLineEvent le = (PlotLineEvent) event;
                        int styleIndex = le.getStyle() - 1;
                        if (styleIndex >= 0 && styleIndex < LINE_STYLES.length) {
                            // Update last known point for this style, even if off-screen
                            double x = timeMsToScreenX(le.getTimestamp());
                            double y = yValueToScreenY(le.getYValue());
                            lastLinePointByStyle[styleIndex] = new PlotPoint(x,y);
                        }
                    }
                } else {
                    // Once we are past the view's start, no need to check further for pre-view points
                    break;
                }
            }


            for (PlotDataEvent event : plotEvents) {
                // Culling: if event is way outside visible time, skip (especially for points/text)
                // For lines, culling is trickier as one end might be in view.
                // The drawing methods themselves will clip.
                if (!(event instanceof PlotLineEvent) &&
                        (event.getTimestamp() < viewStartTimeMs - 20000 / pixelsPerMillisecond ||  // Generous buffer for text width
                                event.getTimestamp() > viewEndTimeMs + 20000 / pixelsPerMillisecond)) {
                    if (plotEvents.size() > 1000) { // Only cull aggressively if many events
                        // continue; // For now, let draw methods handle clipping
                    }
                }

                if (event instanceof PlotPointEvent) {
                    drawPlotPoint((PlotPointEvent) event); // Removed viewStartTimeMs param, not needed
                } else if (event instanceof PlotLineEvent) {
                    drawPlotLine((PlotLineEvent) event);
                }
            }

            for (PlotDataEvent event : plotEvents) {
                if (event instanceof PlotTextAnnotationEvent) { // Our repurposed event
                    // Check if the event's timestamp is within the broader visible canvas range
                    double xScreenOnCanvas = timeMsToScreenX(event.getTimestamp());
                    if (xScreenOnCanvas >= -10 && xScreenOnCanvas <= mainGraphCanvas.getWidth() + 10) { // Rough check
                        drawMarkerTextAnnotation((PlotTextAnnotationEvent) event);
                    }
                }
            }

        }
    }


    // New method to draw the marker text annotation
    private void drawMarkerTextAnnotation(PlotTextAnnotationEvent markerEvent) {
        double xScreenOnCanvas = timeMsToScreenX(markerEvent.getTimestamp());

        // 1. Draw the vertical line
        mainGc.setStroke(MARKER_LINE_COLOR);
        mainGc.setLineWidth(1.0); // Or slightly thicker than grid
        mainGc.setLineDashes(); // Ensure solid line
        // Line spans the data plotting height (0 to visibleGraphHeight on mainGraphCanvas)
        mainGc.strokeLine(xScreenOnCanvas, 0, xScreenOnCanvas, this.visibleGraphHeight -1);

        // 2. Determine Y position for the text
        double textY;
        String position = markerEvent.getPositionKeyword();
        mainGc.setFont(Font.font("Arial", Font.getDefault().getSize())); // Standard font size
        mainGc.setTextAlign(TextAlignment.LEFT); // Text to the RIGHT of the line means align left at line + offset

        // Get text height for more precise vertical centering for "mid" if desired
        // For simplicity now, using approximate factors of visibleGraphHeight.
        // FontMetrics fm = Toolkit.getToolkit().getFontLoader().getFontMetrics(mainGc.getFont());
        // double textHeight = fm.getLineHeight(); // Or ascent/descent

        if ("top".equals(position)) {
            textY = 15; // Pixels from the top of the data area
            mainGc.setTextBaseline(javafx.geometry.VPos.TOP);
        } else if ("bot".equals(position)) {
            textY = this.visibleGraphHeight - 15; // Pixels from the bottom of the data area
            mainGc.setTextBaseline(javafx.geometry.VPos.BOTTOM);
        } else { // "mid" or default
            textY = this.visibleGraphHeight / 2.0;
            mainGc.setTextBaseline(javafx.geometry.VPos.CENTER);
        }

        // 3. Draw the text
        mainGc.setFill(MARKER_TEXT_COLOR);
        double textXOffset = 5; // Pixels to the right of the vertical line
        mainGc.fillText(markerEvent.getText(), xScreenOnCanvas + textXOffset, textY);
    }

    // Method to draw a line segment for PlotLineEvent
    private void drawPlotLine(PlotLineEvent lineEvent) {
        if (lineEvent.getStyle() < 1 || lineEvent.getStyle() > LINE_STYLES.length) {
            System.err.println("Invalid style for PlotLineEvent: " + lineEvent.getStyle());
            return;
        }
        int styleIndex = lineEvent.getStyle() - 1;
        LineStyle lineStyle = LINE_STYLES[styleIndex];

        double x2 = timeMsToScreenX(lineEvent.getTimestamp());
        double y2 = yValueToScreenY(lineEvent.getYValue());

        PlotPoint lastPoint = lastLinePointByStyle[styleIndex];

        if (lastPoint != null) {
            double x1 = lastPoint.x;
            double y1 = lastPoint.y;

            // Basic viewport culling for line segments (both points off one side)
            // More sophisticated clipping (Liang-Barsky, Cohen-Sutherland) could be used if needed.
            if ((x1 < 0 && x2 < 0 && Math.max(x1,x2) < mainGraphCanvas.getTranslateX()) ||
                    (x1 > visibleGraphWidth && x2 > visibleGraphWidth && Math.min(x1,x2) > mainGraphCanvas.getTranslateX() + visibleGraphWidth)) {
                // Both points are to the left of viewport or both to the right - skip (simplistic)
                // This culling is basic. JavaFX canvas drawing will clip to canvas bounds anyway.
            }


            mainGc.setStroke(lineStyle.color);
            mainGc.setLineWidth(lineStyle.width);
            if (lineStyle.dashArray != null && lineStyle.dashArray.length > 0) {
                mainGc.setLineDashes(lineStyle.dashArray);
            } else {
                mainGc.setLineDashes(); // Clear dashes for solid line
            }

            mainGc.strokeLine(x1, y1, x2, y2);

            mainGc.setLineDashes(); // Reset dashes for subsequent drawings
        }
        // Update the last point for this style
        lastLinePointByStyle[styleIndex] = new PlotPoint(x2, y2);
    }


    // Helper record for line drawing
    private static class PlotPoint {
        final double x, y;
        PlotPoint(double x, double y) { this.x = x; this.y = y; }
    }

    private double timeMsToScreenX(long timestampMs) {
        // Converts an absolute timestamp to an X coordinate on the mainGraphCanvas
        // This X is relative to the start of the mainGraphCanvas (which scrolls)
        return (timestampMs - firstTimestamp) * pixelsPerMillisecond;
    }

    private double yValueToScreenY(double yValue) {
        if (currentMaxY <= currentMinY) return this.visibleGraphHeight / 2.0;
        // Y is mapped to the range [0, this.visibleGraphHeight]
        return this.visibleGraphHeight - (((yValue - currentMinY) / (currentMaxY - currentMinY)) * this.visibleGraphHeight);
    }


    // Modify drawPlotPoint to remove unused parameter
    private void drawPlotPoint(PlotPointEvent pointEvent) { // Removed currentViewStartMs param
        double yValue = pointEvent.getYValue();
        double yScreen = yValueToScreenY(yValue);
        double xScreenOnCanvas = timeMsToScreenX(pointEvent.getTimestamp());

        if (xScreenOnCanvas >= -2 && xScreenOnCanvas <= mainGraphCanvas.getWidth() + 2 &&
                yScreen >= -2 && yScreen <= visibleGraphHeight + 2) {

            int style = pointEvent.getStyle(); // POINT_COLORS uses 1-based style
            mainGc.setFill((style >= 1 && style <= POINT_COLORS.length) ? POINT_COLORS[style - 1] : Color.BLACK);
            mainGc.fillOval(xScreenOnCanvas - 2, yScreen - 2, 4, 4);
        }
    }

    private void updateAndDrawDataReadouts() {
        if (!isMouseInPlotArea || currentCursorTimeMs == -1 || firstTimestamp == -1) {
            styleToReadoutValueString.clear();
            styleToReadoutScreenY.clear();
            styleToReadoutDataY.clear();
            styleToReadoutColor.clear();
            // No need to explicitly redraw here if this is called before drawing data in redrawMainGraph
            return;
        }

        // Clear previous readouts before recalculating
        styleToReadoutValueString.clear();
        styleToReadoutScreenY.clear();
        styleToReadoutDataY.clear();
        styleToReadoutColor.clear();

        Set<Integer> activeStyles = new TreeSet<>(); // Keep styles sorted for consistent processing order

        synchronized (plotEvents) {
            // Identify active styles and find closest point/line for each
            for (PlotDataEvent event : plotEvents) {
                if (event.getTimestamp() > currentCursorTimeMs + (visibleGraphWidth / pixelsPerMillisecond) ||
                        event.getTimestamp() < currentCursorTimeMs - (visibleGraphWidth / pixelsPerMillisecond)) {
                    // Basic culling: only consider events somewhat near the cursor's time view
                    // This is a loose filter; more precise "closest" logic follows.
                    // Continue; //This might be too aggressive if a line starts/ends far but crosses view
                }

                int style = -1;
                double yValue = Double.NaN;
                Color eventColor = READOUT_TEXT_COLOR_DEFAULT;

                if (event instanceof PlotPointEvent) {
                    PlotPointEvent pEvent = (PlotPointEvent) event;
                    style = pEvent.getStyle();
                    yValue = pEvent.getYValue();
                    if (style >= 1 && style <= POINT_COLORS.length) {
                        eventColor = POINT_COLORS[style - 1];
                    }
                } else if (event instanceof PlotLineEvent) {
                    PlotLineEvent lEvent = (PlotLineEvent) event;
                    style = lEvent.getStyle();
                    // For line events, this yValue is an endpoint. Interpolation is handled below.
                    yValue = lEvent.getYValue(); // Placeholder, real value comes from interpolation or endpoint
                    if (style >= 1 && style <= LINE_STYLES.length) {
                        eventColor = LINE_STYLES[style - 1].color;
                    }
                }

                if (style != -1) {
                    activeStyles.add(style);
                    // This simple approach just takes the last Y-value encountered for a style
                    // We need to find the *closest* one.
                }
            }

            // For each active style, find the *actual* closest data
            for (int styleId : activeStyles) {
                PlotDataEvent closestEvent = null;
                PlotDataEvent prevEventForLine = null; // For line interpolation
                long minTimeDiff = Long.MAX_VALUE;

                // Find the event of this style closest to cursorTime
                for (PlotDataEvent event : plotEvents) {
                    int currentEventStyle = -1;
                    if (event instanceof PlotPointEvent && ((PlotPointEvent) event).getStyle() == styleId) {
                        currentEventStyle = styleId;
                    } else if (event instanceof PlotLineEvent && ((PlotLineEvent) event).getStyle() == styleId) {
                        currentEventStyle = styleId;
                    }

                    if (currentEventStyle == styleId) {
                        long timeDiff = Math.abs(event.getTimestamp() - currentCursorTimeMs);
                        if (timeDiff < minTimeDiff) {
                            minTimeDiff = timeDiff;
                            closestEvent = event;
                        }
                        // For line interpolation, also keep track of the event just BEFORE closestEvent
                        // if closestEvent itself is a LineEvent, to form a segment.
                        // This logic gets complex quickly. Let's simplify: find closest segment for lines.
                    }
                }

                // Simpler approach for lines for now: find line segment that STRADDLES cursor time
                // or closest endpoint if no straddle.
                PlotLineEvent lineStartEvent = null;
                PlotLineEvent lineEndEvent = null;
                PlotPointEvent closestPointForStyle = null;
                double interpolatedY = Double.NaN;

                // Pass 1: Find straddling line segment for this style
                PlotLineEvent lastLineEventOfStyle = null;
                for (PlotDataEvent event : plotEvents) {
                    if (event instanceof PlotLineEvent && ((PlotLineEvent)event).getStyle() == styleId) {
                        PlotLineEvent currentLineEvent = (PlotLineEvent) event;
                        if (lastLineEventOfStyle != null) {
                            // Check if currentCursorTimeMs is between lastLineEventOfStyle and currentLineEvent
                            long t1 = lastLineEventOfStyle.getTimestamp();
                            long t2 = currentLineEvent.getTimestamp();
                            if ((t1 <= currentCursorTimeMs && currentCursorTimeMs <= t2) ||
                                    (t2 <= currentCursorTimeMs && currentCursorTimeMs <= t1)) {
                                lineStartEvent = lastLineEventOfStyle;
                                lineEndEvent = currentLineEvent;
                                break; // Found a straddling segment
                            }
                        }
                        lastLineEventOfStyle = currentLineEvent;
                    }
                }

                if (lineStartEvent != null && lineEndEvent != null) { // Straddling line found
                    double y1 = lineStartEvent.getYValue();
                    double y2 = lineEndEvent.getYValue();
                    long t1 = lineStartEvent.getTimestamp();
                    long t2 = lineEndEvent.getTimestamp();
                    if (t1 == t2) { // Vertical line segment, or same point
                        interpolatedY = y1; // Or average, or closest in time if different y values
                    } else {
                        // Linear interpolation: y = y1 + (x - x1) * (y2 - y1) / (x2 - x1)
                        interpolatedY = y1 + (double)(currentCursorTimeMs - t1) * (y2 - y1) / (double)(t2 - t1);
                    }
                    if (styleId >= 1 && styleId <= LINE_STYLES.length) {
                        styleToReadoutColor.put(styleId, LINE_STYLES[styleId - 1].color);
                    }
                } else {
                    // No straddling line, find closest point OR line endpoint of this style
                    closestEvent = null; // Reset from general closest search
                    minTimeDiff = Long.MAX_VALUE;
                    for (PlotDataEvent event : plotEvents) {
                        int eventStyle = -1;
                        double eventY = Double.NaN;
                        Color color = READOUT_TEXT_COLOR_DEFAULT;

                        if (event instanceof PlotPointEvent && ((PlotPointEvent)event).getStyle() == styleId) {
                            eventStyle = styleId;
                            eventY = ((PlotPointEvent)event).getYValue();
                            if (styleId >= 1 && styleId <= POINT_COLORS.length) color = POINT_COLORS[styleId-1];
                        } else if (event instanceof PlotLineEvent && ((PlotLineEvent)event).getStyle() == styleId) {
                            eventStyle = styleId;
                            eventY = ((PlotLineEvent)event).getYValue(); // Endpoint of a line
                            if (styleId >= 1 && styleId <= LINE_STYLES.length) color = LINE_STYLES[styleId-1].color;
                        }

                        if (eventStyle != -1) {
                            long timeDiff = Math.abs(event.getTimestamp() - currentCursorTimeMs);
                            if (timeDiff < minTimeDiff) {
                                minTimeDiff = timeDiff;
                                interpolatedY = eventY; // Use the direct Y value of the closest point/endpoint
                                styleToReadoutColor.put(styleId, color);
                            }
                        }
                    }
                }

                if (!Double.isNaN(interpolatedY)) {
                    styleToReadoutValueString.put(styleId, readoutValueFormat.format(interpolatedY));
                    styleToReadoutScreenY.put(styleId, yValueToScreenY(interpolatedY)); // For initial placement
                    styleToReadoutDataY.put(styleId, interpolatedY); // For sorting/staggering
                }
            }
        } // end synchronized

        // Stagger Y positions if they are too close - basic approach
        if (!styleToReadoutScreenY.isEmpty()) {
            List<Map.Entry<Integer, Double>> sortedReadouts = new ArrayList<>(styleToReadoutDataY.entrySet());
            // Sort by data Y value to process them in visual order (e.g., top to bottom)
            sortedReadouts.sort(Map.Entry.comparingByValue());

            TreeSet<Double> occupiedScreenYSlots = new TreeSet<>(); // Screen Y positions already taken

            for (Map.Entry<Integer, Double> entry : sortedReadouts) {
                int styleId = entry.getKey();
                double targetScreenY = yValueToScreenY(entry.getValue()); // Convert data Y to screen Y

                // Adjust targetScreenY to avoid overlap
                while (isSlotOccupied(targetScreenY, occupiedScreenYSlots)) {
                    targetScreenY += READOUT_TEXT_Y_SPACING / 2.0; // Shift down slightly
                }
                // Ensure it's within bounds
                targetScreenY = MathUtil.clip(targetScreenY, 5, visibleGraphHeight - 5);

                styleToReadoutScreenY.put(styleId, targetScreenY);
                occupiedScreenYSlots.add(targetScreenY - READOUT_TEXT_Y_SPACING / 2.0); // Mark range as occupied
                occupiedScreenYSlots.add(targetScreenY + READOUT_TEXT_Y_SPACING / 2.0);
            }
        }
    }

    private boolean isSlotOccupied(double targetY, TreeSet<Double> slots) {
        // Check if targetY is too close to any existing slot
        Double floor = slots.floor(targetY);
        Double ceiling = slots.ceiling(targetY);

        if (floor != null && targetY - floor < READOUT_TEXT_Y_SPACING) return true;
        if (ceiling != null && ceiling - targetY < READOUT_TEXT_Y_SPACING) return true;

        return false;
    }

    // Call this from redrawMainGraph
    private void drawCursorDataReadouts() {
        if (!isMouseInPlotArea || mousePlotX < 0 || styleToReadoutValueString.isEmpty()) {
            return;
        }

        mainGc.save();
        mainGc.setTextAlign(TextAlignment.LEFT);
        mainGc.setFont(Font.font("Arial", 10)); // Smaller font for readouts

        for (Map.Entry<Integer, String> entry : styleToReadoutValueString.entrySet()) {
            int styleId = entry.getKey();
            String valueStr = entry.getValue();
            Double screenY = styleToReadoutScreenY.get(styleId);
            Color textColor = styleToReadoutColor.getOrDefault(styleId, READOUT_TEXT_COLOR_DEFAULT);

            if (screenY != null) {
                mainGc.setFill(textColor);
                mainGc.fillText(valueStr, mousePlotX + READOUT_TEXT_X_OFFSET, screenY);
            }
        }
        mainGc.restore();
    }
}
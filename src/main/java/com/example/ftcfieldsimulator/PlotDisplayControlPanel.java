// Create this file: PlotDisplayControlPanel.java
package com.example.ftcfieldsimulator;

import java.util.List;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.Priority;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class PlotDisplayControlPanel extends VBox {
    private Button btnClearPlot;
    private Button btnStretchTime; // Zoom In on Time
    private Button btnShrinkTime;  // Zoom Out on Time
    private CheckBox chkFollowRealTime;
    private Label lblKeyValueTitle;
    private TableView<KeyTableEntry> keyValueTable;
    private ObservableList<KeyTableEntry> keyValueTableData = FXCollections.observableArrayList();

    // Needs to be public for PropertyValueFactory, or use public getters for StringProperties
    public static class KeyTableEntry {
        private final StringProperty time;
        private final StringProperty key;
        private final StringProperty value;

        public KeyTableEntry(String time, String key, String value) {
            this.time = new SimpleStringProperty(time);
            this.key = new SimpleStringProperty(key);
            this.value = new SimpleStringProperty(value);
        }

        public String getTime() { return time.get(); }
        public StringProperty timeProperty() { return time; }
        public void setTime(String time) { this.time.set(time); }

        public String getKey() { return key.get(); }
        public StringProperty keyProperty() { return key; }
        public void setKey(String key) { this.key.set(key); }

        public String getValue() { return value.get(); }
        public StringProperty valueProperty() { return value; }
        public void setValue(String value) { this.value.set(value); }
    }

    public PlotDisplayControlPanel() {
        super(10); // Spacing for VBox
        setPadding(new Insets(10));
        setPrefWidth(300); // Consistent width
        setStyle("-fx-background-color: #ECEFF1;");
        setAlignment(Pos.TOP_CENTER);

        Label title = new Label("Plot Controls");
        title.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        title.setPadding(new Insets(0,0,10,0));

        btnClearPlot = new Button("Clear Plot");
        btnClearPlot.setMaxWidth(Double.MAX_VALUE);

        btnStretchTime = new Button("Stretch Time (+)");
        btnStretchTime.setMaxWidth(Double.MAX_VALUE);

        btnShrinkTime = new Button("Shrink Time (-)");
        btnShrinkTime.setMaxWidth(Double.MAX_VALUE);

        chkFollowRealTime = new CheckBox("Follow Real-Time");
        chkFollowRealTime.setSelected(true); // Default to enabled
        chkFollowRealTime.setMaxWidth(Double.MAX_VALUE); // Make it take full width like buttons

        lblKeyValueTitle = new Label("Key-Value Data");
        lblKeyValueTitle.setFont(Font.font("Arial", FontWeight.BOLD, 13));
        lblKeyValueTitle.setPadding(new Insets(15, 0, 5, 0)); // Add some top margin

        keyValueTable = new TableView<>();
        TableColumn<KeyTableEntry, String> timeCol = new TableColumn<>("Time (s)");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("time"));
        timeCol.setPrefWidth(70); // Adjust as needed

        TableColumn<KeyTableEntry, String> keyCol = new TableColumn<>("Key");
        keyCol.setCellValueFactory(new PropertyValueFactory<>("key"));
        keyCol.setPrefWidth(80); // Adjust as needed

        TableColumn<KeyTableEntry, String> valueCol = new TableColumn<>("Value");
        valueCol.setCellValueFactory(new PropertyValueFactory<>("value"));
//        valueCol.setPrefWidth(80); // Adjust as needed
        valueCol.prefWidthProperty().bind(
                keyValueTable.widthProperty()
                        .subtract(timeCol.widthProperty())
                        .subtract(keyCol.widthProperty())
                        .subtract(2) // Account for table borders/internal padding slightly
        );

        keyValueTable.getColumns().addAll(timeCol, keyCol, valueCol);
        keyValueTable.setItems(keyValueTableData);
        keyValueTable.setPlaceholder(new Label("No key-value data yet"));
        VBox.setVgrow(keyValueTable, Priority.ALWAYS); // Allow table to take vertical space
        keyValueTable.setPrefHeight(200); // Or some initial preferred height

        getChildren().addAll(title, btnClearPlot, btnStretchTime, btnShrinkTime, chkFollowRealTime, lblKeyValueTitle, keyValueTable);
    }

    public void updateKeyValueTable(List<KeyTableEntry> entries) {
        keyValueTableData.setAll(entries); // Efficiently updates the table
    }

    public void setOnClearPlotAction(EventHandler<ActionEvent> handler) {
        btnClearPlot.setOnAction(handler);
    }

    public void setOnStretchTimeAction(EventHandler<ActionEvent> handler) {
        btnStretchTime.setOnAction(handler);
    }

    public void setOnShrinkTimeAction(EventHandler<ActionEvent> handler) {
        btnShrinkTime.setOnAction(handler);
    }

    public void setOnFollowRealTimeAction(EventHandler<ActionEvent> handler) {
        chkFollowRealTime.setOnAction(handler);
    }

    public boolean isFollowRealTimeSelected() {
        return chkFollowRealTime.isSelected();
    }

    public BooleanProperty followRealTimeSelectedProperty() {
        return chkFollowRealTime.selectedProperty();
    }

    // Call this from PlotDisplay if auto-scroll is disabled by user interaction (e.g., manual scroll)
    public void setFollowRealTimeSelected(boolean selected) {
        chkFollowRealTime.setSelected(selected);
    }
}

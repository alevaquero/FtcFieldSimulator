package com.example.ftcfieldsimulator;

public class PlotSeriesNamePoint2Event implements PlotDataEvent {
    private final long timestamp;
    private final String seriesName;
    private final int style;

    public PlotSeriesNamePoint2Event(long timestamp, String seriesName, int style) {
        this.timestamp = timestamp;
        this.seriesName = seriesName;
        this.style = style;
    }

    @Override
    public long getTimestamp() { return timestamp; }
    public String getSeriesName() { return seriesName; }
    public int getStyle() { return style; }

    @Override
    public String toString() {
        return "PlotSeriesNamePoint2Event{" +
                "seriesName='" + seriesName + '\'' +
                ", style=" + style +
                '}';
    }
}

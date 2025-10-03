// In the robot's teamcode package, or a shared library
package com.example.ftcfieldsimulator; // Or your robot's package, e.g., org.firstinspires.ftc.teamcode.util

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Utility class for robot code to send UDP messages to the FtcFieldSimulator's PlotDisplay.
 * This class would typically reside in the FTC robot controller's `teamcode` module or a shared library.
 */
public class UdpClientPlot {
    private DatagramSocket socket;
    private InetAddress address;
    private int port;
    private boolean initialized = false;

    private static final String DEFAULT_SIMULATOR_IP = "127.0.0.1"; // Default for simulator on the same PC
    // Change if simulator is on a different machine

    /**
     * Constructor for UdpClientPlot.
     * @param host The hostname or IP address of the machine running the FtcFieldSimulator.
     * @param port The port number the UdpPlotListener in FtcFieldSimulator is listening on.
     */
    public UdpClientPlot(String host, int port) {
        try {
            this.socket = new DatagramSocket(); // Socket for sending, binds to any available local port
            this.address = InetAddress.getByName(host);
            this.port = port;
            this.initialized = true;
            System.out.println("UdpClientPlot initialized to send to " + host + ":" + port);
        } catch (Exception e) {
            // In FTC robot code, use Telemetry or System.out for errors if OpMode context is available
            System.err.println("UdpClientPlot: Failed to initialize UDP client for " + host + ":" + port + " - " + e.getMessage());
            // e.printStackTrace(); // Avoid printStackTrace in tight loops on robot
            this.initialized = false;
        }
    }

    /**
     * Default constructor. Attempts to connect to the simulator on localhost
     * using the default plot listener port.
     */
    public UdpClientPlot() {
        this(DEFAULT_SIMULATOR_IP, UdpPlotListener.DEFAULT_PLOT_LISTENER_PORT);
    }

    private void sendMessage(String message) {
        if (!initialized || socket == null || socket.isClosed()) {
            // System.err.println("UdpClientPlot: Not initialized or socket closed. Cannot send: " + message);
            return;
        }
        try {
            byte[] buffer = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, port);
            socket.send(packet);
        } catch (IOException e) {
            System.err.println("UdpClientPlot: IOException sending message '" + message + "': " + e.getMessage());
            // Consider more robust error handling for robot code (e.g., rate limiting error prints)
        } catch (Exception e) {
            // Catch other potential runtime exceptions during send
            System.err.println("UdpClientPlot: Exception sending message '" + message + "': " + e.getMessage());
        }
    }

    /**
     * Sends a Y-value data point to be plotted.
     * @param timestamp The timestamp of the data point (e.g., from an OpMode's runtime timer or System.currentTimeMillis()).
     * @param yValue The Y-value to plot.
     * @param style The style code for the point (typically 1-5).
     */
    public void sendPointY(long timestamp, double yValue, int style) {
        // Format: <timestamp>,point_y:<y_value>,<style>
        String message = String.format(Locale.US, "%d,point_y:%.3f,%d",
                timestamp, yValue, style);
        sendMessage(message);
    }

    /**
     * Sends a Y-value to draw a line segment to. The line is drawn from the previous
     * point of the same style to this new point.
     * @param timestamp The timestamp of this line point.
     * @param yValue The Y-value of this line point.
     * @param style The style code for the line (typically 1-10).
     */
    public void sendLineY(long timestamp, double yValue, int style) {
        // Format: <timestamp>,line_y:<y_value>,<style>
        String message = String.format(Locale.US, "%d,line_y:%.3f,%d",
                timestamp, yValue, style);
        sendMessage(message);
    }

    /**
     * Sends a text annotation to be displayed as a vertical marker on the plot.
     * @param timestamp The timestamp where the vertical marker line should appear.
     * @param text The text to display. If it contains commas, consider quoting or use a format that the listener can handle.
     * @param positionKeyword "top", "mid", or "bot" to indicate text vertical position relative to the plot area.
     */
    public void sendTextMarker(long timestamp, String text, String positionKeyword) {
        // Format: <timestamp>,text:<text_string>,<position_keyword>
        // Ensure text doesn't break parsing if it has unescaped commas and the listener isn't robust.
        // For simplicity, assuming text won't conflict with the final comma for positionKeyword.
        // If text can have commas, the Python sender wraps it in quotes. The Java listener handles this.
        // Here, we'll just send it raw. If the user puts quotes in the text string, they will be sent.
        String message = String.format(Locale.US, "%d,text:%s,%s",
                timestamp, text, positionKeyword.toLowerCase(Locale.US));
        sendMessage(message);
    }

    /**
     * Sets the Y-axis limits for the plot.
     * @param timestamp A timestamp for associating this command (can be current time).
     * @param maxY The maximum value for the Y-axis.
     * @param minY The minimum value for the Y-axis.
     */
    public void sendYLimits(long timestamp, double maxY, double minY) {
        // Format: <timestamp>,set_y_limits:<max_y>,<min_y>
        String message = String.format(Locale.US, "%d,set_y_limits:%.2f,%.2f",
                timestamp, maxY, minY);
        sendMessage(message);
    }

    /**
     * Sets the unit label for the Y-axis.
     * @param timestamp A timestamp for associating this command (can be current time).
     * @param unitString The string to display as the Y-axis unit (e.g., "Volts", "Degrees").
     */
    public void sendYUnits(long timestamp, String unitString) {
        // Format: <timestamp>,set_y_units:<unit_string>
        String message = String.format(Locale.US, "%d,set_y_units:%s",
                timestamp, unitString);
        sendMessage(message);
    }

    /**
     * Sends a key-value pair to be displayed in the telemetry/data table area of the plot window.
     * @param timestamp The timestamp associated with this key-value update.
     * @param key The key string.
     * @param value The value string.
     */
    public void sendKeyValue(long timestamp, String key, String value) {
        // Format: <timestamp>,key_value:<key_string>,<value_string>
        // Ensure key/value don't contain commas that would break basic parsing on the listener if it's simple.
        // Assuming the listener's key_value parser splits only on the first comma after "key_value:".
        String message = String.format(Locale.US, "%d,key_value:%s,%s",
                timestamp, key, value);
        sendMessage(message);
    }

    /**
     * Checks if the client was initialized successfully.
     * @return true if initialized, false otherwise.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Closes the socket. This should be called when the UdpClientPlot is no longer needed,
     * for example, at the end of an OpMode.
     */
    public void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
            System.out.println("UdpClientPlot socket closed.");
        }
        initialized = false; // Mark as not usable anymore
    }

    // Optional: A main method for quick testing from a non-robot environment
    // public static void main(String[] args) {
    //     UdpClientPlot plotClient = new UdpClientPlot();
    //     if (!plotClient.isInitialized()) {
    //         System.err.println("Plot client failed to initialize. Exiting test.");
    //         return;
    //     }
    //     long startTime = System.currentTimeMillis();
    //     plotClient.sendYUnits(System.currentTimeMillis() - startTime, "Test Units");
    //     plotClient.sendYLimits(System.currentTimeMillis() - startTime, 100, 0);
    //
    //     for (int i = 0; i < 50; i++) {
    //         long time = System.currentTimeMillis() - startTime;
    //         double yVal = 50 + 40 * Math.sin(i * 0.2);
    //         if (i % 2 == 0) {
    //             plotClient.sendPointY(time, yVal, (i % 5) + 1);
    //         } else {
    //             plotClient.sendLineY(time, yVal, (i % 10) + 1);
    //         }
    //         if (i % 10 == 0) {
    //             plotClient.sendTextMarker(time, "Event " + i, "mid");
    //             plotClient.sendKeyValue(time, "Loop", String.valueOf(i));
    //         }
    //         try { Thread.sleep(100); } catch (InterruptedException e) { break; }
    //     }
    //     plotClient.sendKeyValue(System.currentTimeMillis() - startTime, "Status", "Test Complete");
    //     plotClient.close();
    // }
}


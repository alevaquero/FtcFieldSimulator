// Create this file: UdpPlotListener.java
package com.example.ftcfieldsimulator;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;

public class UdpPlotListener implements Runnable {

    public static final int DEFAULT_PLOT_LISTENER_PORT = 7778; // New port for plot data

    private final int port;
    private volatile boolean running = true;
    private DatagramSocket socket;
    private final Consumer<PlotDataEvent> eventConsumer; // Will pass parsed PlotDataEvent objects

    public UdpPlotListener(int port, Consumer<PlotDataEvent> eventConsumer) throws SocketException {
        this.port = port;
        this.eventConsumer = eventConsumer;
        this.socket = new DatagramSocket(port);
        System.out.println("UDP Plot Listener initialized on port: " + port);
    }

    public UdpPlotListener(Consumer<PlotDataEvent> eventConsumer) throws SocketException {
        this(DEFAULT_PLOT_LISTENER_PORT, eventConsumer);
    }

    public void stopListener() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        System.out.println("UDP Plot Listener stopping.");
    }

    @Override
    public void run() {
        byte[] buffer = new byte[1024]; // Buffer for incoming data
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        System.out.println("UDP Plot Listener started on port " + port + " and waiting for messages...");

        while (running && socket != null && !socket.isClosed()) {
            try {
                socket.receive(packet);
                String received = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                // System.out.println("Plot UDP Received: " + received); // For debugging
                parseAndConsume(received);
            } catch (SocketException se) {
                if (!running) { // Expected exception when stopping
                    System.out.println("UDP Plot Listener socket closed (expected during stop).");
                } else {
                    System.err.println("UDP Plot Listener SocketException: " + se.getMessage());
                    running = false; // Stop if unexpected socket error
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("UDP Plot Listener IOException: " + e.getMessage());
                    // Decide if this is fatal, or if we should try to continue
                }
            } catch (Exception e) { // Catch other parsing errors
                System.err.println("Error processing UDP plot message: '" + new String(packet.getData(), 0, packet.getLength()) + "'. Error: " + e.getMessage());
            }
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        System.out.println("UDP Plot Listener thread finished.");
    }

    private void parseAndConsume(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }

        try {
            String[] parts = message.split(",", 2); // Split timestamp from the rest
            if (parts.length < 2) {
                System.err.println("Malformed plot message (missing timestamp comma): " + message);
                return;
            }

            long timestamp = Long.parseLong(parts[0].trim());
            String commandAndArgs = parts[1].trim();

            if (commandAndArgs.startsWith("point_y:")) {
                String argsStr = commandAndArgs.substring("point_y:".length()).trim();
                String[] args = argsStr.split(",");
                if (args.length == 2) {
                    double y = Double.parseDouble(args[0].trim());
                    int style = Integer.parseInt(args[1].trim());
                    eventConsumer.accept(new PlotPointEvent(timestamp, y, style));
                } else {
                    System.err.println("Malformed point_y message: " + commandAndArgs);
                }
            } else if (commandAndArgs.startsWith("line_y:")) { // +++ NEW +++
                String argsStr = commandAndArgs.substring("line_y:".length()).trim();
                String[] args = argsStr.split(",");
                if (args.length == 2) {
                    double y = Double.parseDouble(args[0].trim());
                    int style = Integer.parseInt(args[1].trim());
                    eventConsumer.accept(new PlotLineEvent(timestamp, y, style));
                } else {
                    System.err.println("Malformed line_y message: " + commandAndArgs);
                }
            } else if (commandAndArgs.startsWith("text:")) { // +++ REVISED FOR NEW REQUIREMENT +++
                // Expected format for commandAndArgs: "text:<text_string>,<position_keyword>"
                String argsStr = commandAndArgs.substring("text:".length());

                // Text string might contain commas if quoted, or not if unquoted and simple.
                // For this format, the last argument is always the position_keyword.
                int lastCommaIndex = argsStr.lastIndexOf(',');
                if (lastCommaIndex == -1 || lastCommaIndex == 0) { // No comma or comma at the very beginning
                    System.err.println("Malformed text message (missing comma for position keyword): " + commandAndArgs);
                    return;
                }

                String textString = argsStr.substring(0, lastCommaIndex).trim(); // Text is everything before the last comma
                String positionKeyword = argsStr.substring(lastCommaIndex + 1).trim().toLowerCase();

                // Optional: Remove quotes from textString if present
                if (textString.startsWith("\"") && textString.endsWith("\"") && textString.length() >= 2) {
                    textString = textString.substring(1, textString.length() - 1);
                }

                if (!positionKeyword.equals("top") && !positionKeyword.equals("mid") && !positionKeyword.equals("bot")) {
                    System.err.println("Invalid position keyword for text message: '" + positionKeyword + "'. Defaulting to 'mid'.");
                    positionKeyword = "mid";
                }

                eventConsumer.accept(new PlotTextAnnotationEvent(timestamp, textString, positionKeyword));

            }  else if (commandAndArgs.startsWith("set_y_limits:")) { // +++ NEW +++
                String argsStr = commandAndArgs.substring("set_y_limits:".length()).trim();
                String[] args = argsStr.split(",");
                if (args.length == 2) {
                    double maxY = Double.parseDouble(args[0].trim());
                    double minY = Double.parseDouble(args[1].trim());
                    if (maxY <= minY) {
                        System.err.println("Invalid Y limits: max_y must be greater than min_y. Received: " + commandAndArgs);
                        return;
                    }
                    eventConsumer.accept(new PlotYLimitsEvent(timestamp, maxY, minY));
                } else {
                    System.err.println("Malformed set_y_limits message: " + commandAndArgs);
                }
            } else if (commandAndArgs.startsWith("set_y_units:")) { // +++ NEW +++
                String unitStr = commandAndArgs.substring("set_y_units:".length()).trim();
                // Assuming unit string does not contain commas, or is the only argument
                eventConsumer.accept(new PlotYUnitsEvent(timestamp, unitStr));
            } else if (commandAndArgs.startsWith("key_value:")) {
                String argsStr = commandAndArgs.substring("key_value:".length());
                // Expecting <key_string>,<value_string>
                // Key string or value string could theoretically contain commas if we were to support
                // quoted strings, but for simplicity now, assume they don't or the first comma is the delimiter.
                int firstCommaIndex = argsStr.indexOf(',');
                if (firstCommaIndex != -1 && firstCommaIndex > 0 && firstCommaIndex < argsStr.length() - 1) {
                    String key = argsStr.substring(0, firstCommaIndex).trim();
                    String value = argsStr.substring(firstCommaIndex + 1).trim();
                    eventConsumer.accept(new PlotKeyValueEvent(timestamp, key, value));
                } else {
                    System.err.println("Malformed key_value message: " + commandAndArgs);
                }
            } else {
                System.out.println("Unknown plot command: " + commandAndArgs);
            }
        } catch (NumberFormatException e) {
            System.err.println("Error parsing number in plot message '" + message + "': " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Generic error parsing plot message '" + message + "': " + e.getMessage());
            e.printStackTrace(); // For more detail during development
        }
    }
}

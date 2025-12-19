package com.example.ftcfieldsimulator;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.application.Platform;

public class RecordingManager {

    // --- NEW: Live Buffer for Instant Replay ---
    private static final long LIVE_BUFFER_DURATION_MS = TimeUnit.MINUTES.toMillis(10);
    private final LinkedList<RecordedEvent> liveBuffer = new LinkedList<>();
    // --- END NEW ---

    public static class RecordedEvent {
        public final long timestamp;
        public final UdpPositionListener.UdpMessageData messageData;

        public RecordedEvent(long timestamp, UdpPositionListener.UdpMessageData messageData) {
            this.timestamp = timestamp;
            this.messageData = messageData;
        }
    }

    public enum PlaybackState { IDLE, RECORDING, PLAYING, PAUSED }
    private long firstEventTimestamp = -1;
    private long recordingStartTimeMs = -1;
    private volatile PlaybackState currentState = PlaybackState.IDLE;
    private volatile int playbackIndex = 0;
    private ArrayList<RecordedEvent> recordedSession = new ArrayList<>();

    private Thread playbackThread;
    private final Consumer<UdpPositionListener.UdpMessageData> eventConsumer;
    private final Runnable onPlaybackFinished;
    private Consumer<Integer> onProgressUpdateCallback;
    private final Object pauseLock = new Object();
    private final Object stateLock = new Object();

    public RecordingManager(Consumer<UdpPositionListener.UdpMessageData> eventConsumer, Runnable onPlaybackFinished) {
        this.eventConsumer = eventConsumer;
        this.onPlaybackFinished = onPlaybackFinished;
    }

    public void setOnProgressUpdate(Consumer<Integer> callback) {
        this.onProgressUpdateCallback = callback;
    }

    public ArrayList<RecordedEvent> getRecordedSession() {
        synchronized (stateLock) {
            return new ArrayList<>(recordedSession);
        }
    }

    public void loadRecording(ArrayList<RecordedEvent> newSession) {
        synchronized (stateLock) {
            stopPlaybackInternal();
            recordedSession = newSession != null ? new ArrayList<>(newSession) : new ArrayList<>();
            playbackIndex = 0;
            if (!recordedSession.isEmpty()) {
                firstEventTimestamp = recordedSession.get(0).timestamp;
            } else {
                firstEventTimestamp = -1;
            }
            recordingStartTimeMs = -1;
            currentState = PlaybackState.IDLE;
        }
    }

    // --- NEW: Method to load from the live buffer for Instant Replay ---
    public void loadFromLiveBuffer() {
        synchronized (stateLock) {
            stopPlaybackInternal(); // Stop any current playback/recording
            recordedSession = new ArrayList<>(liveBuffer);
            playbackIndex = 0;
            if (!recordedSession.isEmpty()) {
                firstEventTimestamp = recordedSession.get(0).timestamp;
            } else {
                firstEventTimestamp = -1;
            }
            recordingStartTimeMs = -1;
            currentState = PlaybackState.IDLE;
            System.out.println("Loaded " + recordedSession.size() + " events from live buffer.");
        }
    }

    public synchronized long getCurrentEventTimeLapsed() {
        if (currentState == PlaybackState.RECORDING) {
            return getCurrentRecordingDuration();
        }
        if (recordedSession.isEmpty() || playbackIndex < 0 || playbackIndex >= recordedSession.size() || firstEventTimestamp < 0) {
            return -1;
        }
        long currentEventTimestamp = recordedSession.get(playbackIndex).timestamp;
        return Math.max(0, currentEventTimestamp - firstEventTimestamp);
    }

    public synchronized long getCurrentRecordingDuration() {
        if (currentState == PlaybackState.RECORDING && recordingStartTimeMs != -1) {
            return System.currentTimeMillis() - recordingStartTimeMs;
        }
        return -1;
    }

    public synchronized long getTimeLapsedForEventIndex(int index) {
        if (recordedSession.isEmpty() || index < 0 || index >= recordedSession.size() || firstEventTimestamp < 0) {
            return -1;
        }
        return recordedSession.get(index).timestamp - firstEventTimestamp;
    }

    public void startRecording() {
        synchronized (stateLock) {
            stopPlaybackInternal();
            if (currentState != PlaybackState.IDLE) {
                currentState = PlaybackState.IDLE;
            }
            currentState = PlaybackState.RECORDING;
            recordedSession.clear(); // Clear for a new manual recording
            playbackIndex = 0;
            recordingStartTimeMs = System.currentTimeMillis();
            firstEventTimestamp = -1;
            System.out.println("Recording started at: " + recordingStartTimeMs);
        }
    }

    // --- MODIFIED: Renamed to addLiveEvent and handles both live buffer and manual recording ---
    public void addLiveEvent(UdpPositionListener.UdpMessageData messageData) {
        long absoluteTimestamp = System.currentTimeMillis();
        RecordedEvent newEvent = new RecordedEvent(absoluteTimestamp, messageData);

        synchronized (stateLock) {
            // Always add to the live buffer
            liveBuffer.add(newEvent);
            trimLiveBuffer(absoluteTimestamp);

            // If also manually recording, add to the main session
            if (currentState == PlaybackState.RECORDING) {
                if (recordedSession.isEmpty()) {
                    firstEventTimestamp = absoluteTimestamp;
                }
                recordedSession.add(newEvent);
            }
        }
    }

    // --- NEW: Helper to trim the live buffer ---
    private void trimLiveBuffer(long currentTime) {
        while (!liveBuffer.isEmpty() && (currentTime - liveBuffer.getFirst().timestamp > LIVE_BUFFER_DURATION_MS)) {
            liveBuffer.removeFirst();
        }
    }

    public void stopRecording() {
        synchronized (stateLock) {
            if (currentState != PlaybackState.RECORDING) return;
            currentState = PlaybackState.IDLE;
            recordingStartTimeMs = -1;
        }
    }

    public void play() {
        synchronized (stateLock) {
            if (recordedSession.isEmpty()) {
                return;
            }
            if (playbackIndex >= recordedSession.size() || (playbackIndex == recordedSession.size() - 1 && currentState != PlaybackState.PAUSED && currentState != PlaybackState.PLAYING) ) {
                playbackIndex = 0;
                if (firstEventTimestamp == -1 && !recordedSession.isEmpty()) {
                    firstEventTimestamp = recordedSession.get(0).timestamp;
                }
                if (currentState == PlaybackState.PLAYING && playbackThread != null && playbackThread.isAlive()) {
                    stopPlaybackInternal();
                }
                currentState = PlaybackState.IDLE;
            }

            if (firstEventTimestamp == -1 && !recordedSession.isEmpty()) {
                firstEventTimestamp = recordedSession.get(0).timestamp;
            }

            if (playbackIndex < 0 || playbackIndex >= recordedSession.size()) {
                playbackIndex = 0;
            }

            if (currentState == PlaybackState.PAUSED) {
                currentState = PlaybackState.PLAYING;
                synchronized (pauseLock) {
                    pauseLock.notifyAll();
                }
                return;
            }

            currentState = PlaybackState.PLAYING;
            if (playbackThread != null && playbackThread.isAlive()) {
                stopPlaybackInternal();
            }
            playbackThread = new Thread(this::runPlaybackLoop);
            playbackThread.setDaemon(true);
            playbackThread.start();
        }
    }

    public void pause() {
        synchronized (stateLock) {
            if (currentState != PlaybackState.PLAYING) return;
            currentState = PlaybackState.PAUSED;
        }
    }

    public void seekTo(int eventIndex) {
        synchronized (stateLock) {
            if (recordedSession.isEmpty()) {
                return;
            }

            int targetRawIndex = Math.max(0, Math.min(eventIndex, recordedSession.size() - 1));
            int snappedTargetPositionDataIndex = findClosestPositionEventIndex(targetRawIndex);

            if (snappedTargetPositionDataIndex < 0 || snappedTargetPositionDataIndex >= recordedSession.size()) {
                if (recordedSession.isEmpty()) return;
                snappedTargetPositionDataIndex = recordedSession.isEmpty() ? -1 : Math.max(0, Math.min(targetRawIndex, recordedSession.size() - 1));
                 if (snappedTargetPositionDataIndex == -1) return;
            }

            boolean wasPlayingOrPaused = (currentState == PlaybackState.PLAYING || currentState == PlaybackState.PAUSED);
            if (wasPlayingOrPaused) {
                stopPlaybackInternal();
            }
            currentState = PlaybackState.IDLE;

            int dispatchStartIndex = 0;
            for (int i = snappedTargetPositionDataIndex - 1; i >= 0; i--) {
                if (recordedSession.get(i).messageData instanceof UdpPositionListener.PositionData) {
                    dispatchStartIndex = i;
                    break;
                }
            }

            for (int i = dispatchStartIndex; i <= snappedTargetPositionDataIndex; i++) {
                playbackIndex = i;
                dispatchCurrentEvent();
            }
        }
    }

    public void stepForward() {
        synchronized (stateLock) {
            if (recordedSession.isEmpty() || playbackIndex >= recordedSession.size() - 1) {
                if (!recordedSession.isEmpty() && playbackIndex < recordedSession.size()){
                    dispatchCurrentEvent();
                }
                return;
            }

            if (currentState == PlaybackState.PLAYING || currentState == PlaybackState.PAUSED) {
                stopPlaybackInternal();
            }
            currentState = PlaybackState.IDLE;

            int startIndex = playbackIndex;
            int targetIndex = -1;

            for (int i = startIndex + 1; i < recordedSession.size(); i++) {
                if (recordedSession.get(i).messageData instanceof UdpPositionListener.PositionData) {
                    targetIndex = i;
                    break;
                }
            }

            if (targetIndex == -1) {
                targetIndex = recordedSession.size() - 1;
            }

            for (int i = startIndex + 1; i <= targetIndex; i++) {
                playbackIndex = i;
                dispatchCurrentEvent();
            }
        }
    }

    public void stepBackward() {
        synchronized (stateLock) {
            if (recordedSession.isEmpty() || playbackIndex <= 0) {
                if (!recordedSession.isEmpty() && playbackIndex < recordedSession.size() && playbackIndex >= 0){
                    dispatchCurrentEvent();
                }
                return;
            }

            if (currentState == PlaybackState.PLAYING || currentState == PlaybackState.PAUSED) {
                stopPlaybackInternal();
            }
            currentState = PlaybackState.IDLE;

            int currentIndex = playbackIndex;
            int targetPositionDataIndex = -1;

            for (int i = currentIndex - 1; i >= 0; i--) {
                if (recordedSession.get(i).messageData instanceof UdpPositionListener.PositionData) {
                    targetPositionDataIndex = i;
                    break;
                }
            }

            if (targetPositionDataIndex == -1 && currentIndex > 0) {
                targetPositionDataIndex = 0;
            } else if (targetPositionDataIndex == -1 && currentIndex == 0) {
                dispatchCurrentEvent();
                return;
            }

            if (targetPositionDataIndex != -1) {
                int dispatchStartIndex = 0;
                for(int i = targetPositionDataIndex - 1; i >= 0; i--) {
                    if (recordedSession.get(i).messageData instanceof UdpPositionListener.PositionData) {
                        dispatchStartIndex = i;
                        break;
                    }
                }

                for (int i = dispatchStartIndex; i <= targetPositionDataIndex; i++) {
                    playbackIndex = i;
                    dispatchCurrentEvent();
                }
            } else {
                if (playbackIndex >=0 && playbackIndex < recordedSession.size()){
                    dispatchCurrentEvent();
                }
            }
        }
    }

    private void dispatchCurrentEvent() {
        if (playbackIndex >= 0 && playbackIndex < recordedSession.size()) {
            final RecordedEvent currentEventToDispatch = recordedSession.get(playbackIndex);
            final int currentIndexToDispatch = playbackIndex;

            Platform.runLater(() -> {
                eventConsumer.accept(currentEventToDispatch.messageData);
                if (onProgressUpdateCallback != null) {
                    onProgressUpdateCallback.accept(currentIndexToDispatch);
                }
            });
        }
    }

    private void runPlaybackLoop() {
        synchronized(stateLock) {
            if (playbackIndex < recordedSession.size()) {
                dispatchCurrentEvent();
            }
        }

        try {
            while (true) {
                synchronized (pauseLock) {
                    while (currentState == PlaybackState.PAUSED) {
                        pauseLock.wait();
                    }
                }

                synchronized (stateLock) {
                    if (currentState != PlaybackState.PLAYING) {
                        break;
                    }
                    if (playbackIndex >= recordedSession.size() - 1) {
                        break;
                    }
                }
                long delayMillis = recordedSession.get(playbackIndex + 1).timestamp - recordedSession.get(playbackIndex).timestamp;
                if (delayMillis < 0) delayMillis = 0;

                Thread.sleep(delayMillis == 0 ? 1 : delayMillis);

                synchronized(stateLock) {
                    if (currentState != PlaybackState.PLAYING) {
                        break;
                    }
                    playbackIndex++;
                    dispatchCurrentEvent();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (stateLock) {
                if (currentState == PlaybackState.PLAYING || currentState == PlaybackState.PAUSED) {
                    currentState = PlaybackState.IDLE;
                    if (onPlaybackFinished != null) {
                        Platform.runLater(onPlaybackFinished);
                    }
                }
            }
        }
    }

    private void stopPlaybackInternal() {
        PlaybackState stateBeforeStopping = currentState;
        boolean wasPlayingOrPaused = (stateBeforeStopping == PlaybackState.PLAYING || stateBeforeStopping == PlaybackState.PAUSED);
        currentState = PlaybackState.IDLE;

        if (playbackThread != null) {
            if (playbackThread.isAlive()) {
                playbackThread.interrupt();
                try {
                    playbackThread.join(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            playbackThread = null;
        }

        if (wasPlayingOrPaused && onPlaybackFinished != null) {
            Platform.runLater(onPlaybackFinished);
        }
    }

    public void stopPlayback() {
        synchronized(stateLock) {
            stopPlaybackInternal();
        }
    }

    public PlaybackState getCurrentState() {
        synchronized (stateLock) {
            return currentState;
        }
    }

    public boolean hasRecording() {
        synchronized (stateLock) {
            return !recordedSession.isEmpty();
        }
    }

    public int getPlaybackIndex() {
        synchronized (stateLock) {
            return playbackIndex;
        }
    }

    public int getTotalEvents() {
        synchronized (stateLock) {
            return recordedSession.size();
        }
    }

    private int findClosestPositionEventIndex(int targetIndex) {
        if (recordedSession.isEmpty()) {
            return targetIndex;
        }

        targetIndex = Math.max(0, Math.min(targetIndex, recordedSession.size() - 1));

        if (recordedSession.get(targetIndex).messageData instanceof UdpPositionListener.PositionData) {
            return targetIndex;
        }

        int prevPosIndex = -1;
        for (int i = targetIndex; i >= 0; i--) {
            if (recordedSession.get(i).messageData instanceof UdpPositionListener.PositionData) {
                prevPosIndex = i;
                break;
            }
        }

        int nextPosIndex = -1;
        for (int i = targetIndex; i < recordedSession.size(); i++) {
            if (recordedSession.get(i).messageData instanceof UdpPositionListener.PositionData) {
                nextPosIndex = i;
                break;
            }
        }

        if (prevPosIndex != -1 && nextPosIndex != -1) {
            if (Math.abs(targetIndex - prevPosIndex) <= Math.abs(targetIndex - nextPosIndex)) {
                return prevPosIndex;
            } else {
                return nextPosIndex;
            }
        } else if (prevPosIndex != -1) {
            return prevPosIndex;
        } else if (nextPosIndex != -1) {
            return nextPosIndex;
        }

        if (!recordedSession.isEmpty()) return 0;
        return targetIndex;
    }
}

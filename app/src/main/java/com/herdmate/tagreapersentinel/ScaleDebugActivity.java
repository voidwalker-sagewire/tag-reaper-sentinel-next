package com.herdmate.tagreapersentinel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.rscja.CWDeviceInfo;
import com.rscja.deviceapi.Module;
import com.rscja.team.qcom.DeviceConfiguration_qcom;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Raw RS-232 diagnostic screen for the C316H external serial port.
 *
 * This screen intentionally does not parse or save weights yet. Its only job is
 * to prove which UART is open and show exactly what bytes the indicator sends.
 */
public class ScaleDebugActivity extends AppCompatActivity {

    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = 1;
    private static final int PARITY_NONE = 0;
    private static final int MAX_LOG_CHARS = 50000;

    private TextView txtSerialStatus;
    private TextView txtDeviceInfo;
    private TextView txtCounters;
    private TextView txtLatestLine;
    private TextView txtAsciiLog;
    private TextView txtHexLog;
    private Spinner spinnerDevicePath;
    private Spinner spinnerBaud;
    private Button btnStartSerial;
    private Button btnStopSerial;
    private Button btnClearSerial;
    private Button btnCopySerial;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean uiRefreshPending = new AtomicBoolean(false);
    private final Object logLock = new Object();
    private final StringBuilder asciiLog = new StringBuilder();
    private final StringBuilder hexLog = new StringBuilder();
    private final StringBuilder lineBuffer = new StringBuilder();
    private final SimpleDateFormat timestampFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private volatile boolean serialRunning;
    private volatile Thread serialThread;
    private volatile Module serialModule;
    private volatile long bytesReceived;
    private volatile long chunkCount;
    private volatile long lineCount;
    private volatile String latestLine = "—";
    private volatile String activeDevicePath = "";
    private volatile int activeBaud = 9600;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scale_debug);
        bindViews();
        configureSelectors();
        configureButtons();
        updateDeviceInfo();
        updateControls();
        addSystemLog("Scale Debug Log opened. No weight parser is active.");
    }

    private void bindViews() {
        txtSerialStatus = findViewById(R.id.txtSerialStatus);
        txtDeviceInfo = findViewById(R.id.txtScaleDeviceInfo);
        txtCounters = findViewById(R.id.txtScaleCounters);
        txtLatestLine = findViewById(R.id.txtScaleLatestLine);
        txtAsciiLog = findViewById(R.id.txtScaleAsciiLog);
        txtHexLog = findViewById(R.id.txtScaleHexLog);
        spinnerDevicePath = findViewById(R.id.spinnerScaleDevicePath);
        spinnerBaud = findViewById(R.id.spinnerScaleBaud);
        btnStartSerial = findViewById(R.id.btnStartSerial);
        btnStopSerial = findViewById(R.id.btnStopSerial);
        btnClearSerial = findViewById(R.id.btnClearSerial);
        btnCopySerial = findViewById(R.id.btnCopySerial);
    }

    private void configureSelectors() {
        Set<String> paths = new LinkedHashSet<>();
        String detected = detectExtendedUartPath();
        if (detected != null && !detected.trim().isEmpty()) paths.add(detected.trim());

        // Known Chainway extended-UART paths kept as manual diagnostic fallbacks.
        paths.add("/dev/ttyHS2");
        paths.add("/dev/ttyS3");
        paths.add("/dev/ttyHSL0");
        paths.add("/dev/ttyMSM1");
        paths.add("/dev/ttyS1");
        paths.add("/dev/ttyS0");

        List<String> pathItems = new ArrayList<>(paths);
        ArrayAdapter<String> pathAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                pathItems
        );
        pathAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevicePath.setAdapter(pathAdapter);

        String[] baudItems = {"9600", "4800", "2400", "1200", "19200", "38400", "115200"};
        ArrayAdapter<String> baudAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                baudItems
        );
        baudAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBaud.setAdapter(baudAdapter);
        spinnerBaud.setSelection(0);
    }

    private void configureButtons() {
        btnStartSerial.setOnClickListener(v -> startSerial());
        btnStopSerial.setOnClickListener(v -> stopSerial(true));
        btnClearSerial.setOnClickListener(v -> clearLogs());
        btnCopySerial.setOnClickListener(v -> copyLogs());
    }

    private String detectExtendedUartPath() {
        try {
            return DeviceConfiguration_qcom
                    .builderA8ExtendedUartConfiguration()
                    .getUart();
        } catch (Throwable error) {
            return "";
        }
    }

    private void updateDeviceInfo() {
        String model = "unknown";
        String cpu = "unknown";
        String platform = "unknown";
        String detectedPath = detectExtendedUartPath();
        try {
            CWDeviceInfo info = CWDeviceInfo.getDeviceInfo();
            if (info != null) {
                model = safe(info.getModel());
                cpu = safe(info.getCpuType());
                platform = safe(info.getPlatform());
            }
        } catch (Throwable ignored) {
        }

        txtDeviceInfo.setText(
                "DEVICE | Model: " + model
                        + " | CPU: " + cpu
                        + " | Platform: " + platform
                        + "\nSDK extended UART: "
                        + (detectedPath == null || detectedPath.isEmpty() ? "not detected" : detectedPath)
                        + " | Serial format: 8-N-1"
        );
    }

    private String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value.trim();
    }

    private void startSerial() {
        if (serialRunning) return;

        Object selectedPath = spinnerDevicePath.getSelectedItem();
        Object selectedBaud = spinnerBaud.getSelectedItem();
        if (selectedPath == null || selectedBaud == null) {
            setStatus("ERROR | Select a UART path and baud rate");
            return;
        }

        activeDevicePath = selectedPath.toString().trim();
        try {
            activeBaud = Integer.parseInt(selectedBaud.toString());
        } catch (NumberFormatException error) {
            setStatus("ERROR | Invalid baud rate");
            return;
        }

        setStatus("OPENING | " + activeDevicePath + " @ " + activeBaud + " 8-N-1");
        addSystemLog("Opening " + activeDevicePath + " @ " + activeBaud + " 8-N-1");
        btnStartSerial.setEnabled(false);
        spinnerDevicePath.setEnabled(false);
        spinnerBaud.setEnabled(false);

        serialThread = new Thread(() -> {
            try {
                serialModule = Module.getInstance();
                boolean opened = serialModule.openSerail(
                        activeDevicePath,
                        activeBaud,
                        DATA_BITS,
                        STOP_BITS,
                        PARITY_NONE
                );

                if (!opened) {
                    addSystemLog("OPEN FAILED | SDK rejected " + activeDevicePath);
                    mainHandler.post(() -> {
                        setStatus("ERROR | Could not open " + activeDevicePath);
                        serialRunning = false;
                        updateControls();
                    });
                    return;
                }

                serialRunning = true;
                mainHandler.post(() -> {
                    setStatus("OPEN | " + activeDevicePath + " @ " + activeBaud + " 8-N-1");
                    updateControls();
                });
                addSystemLog("OPEN SUCCESS | Waiting for indicator bytes...");

                while (serialRunning && !Thread.currentThread().isInterrupted()) {
                    byte[] data = serialModule.receiveEx();
                    if (data != null && data.length > 0) {
                        appendReceivedBytes(data);
                    } else {
                        try {
                            Thread.sleep(20L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (Throwable error) {
                addSystemLog("SERIAL ERROR | " + error.getClass().getSimpleName() + ": " + safeMessage(error));
                mainHandler.post(() -> setStatus(
                        "ERROR | " + error.getClass().getSimpleName() + ": " + safeMessage(error)
                ));
            } finally {
                closeSerialModuleQuietly();
                serialRunning = false;
                serialThread = null;
                mainHandler.post(this::updateControls);
            }
        }, "sentinel-scale-serial");
        serialThread.start();
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "no message" : message.trim();
    }

    private void stopSerial(boolean userRequested) {
        if (!serialRunning && serialThread == null) {
            setStatus("CLOSED | Serial listener is not running");
            updateControls();
            return;
        }

        serialRunning = false;
        Thread runningThread = serialThread;
        if (runningThread != null) runningThread.interrupt();
        closeSerialModuleQuietly();
        serialThread = null;

        if (userRequested) addSystemLog("Serial listener stopped by operator.");
        setStatus("CLOSED | " + activeDevicePath);
        updateControls();
    }

    private void closeSerialModuleQuietly() {
        Module module = serialModule;
        serialModule = null;
        if (module != null) {
            try {
                module.closeSerail();
            } catch (Throwable ignored) {
            }
        }
    }

    private void appendReceivedBytes(byte[] data) {
        String timestamp = timestampFormat.format(new Date());
        StringBuilder printable = new StringBuilder();
        StringBuilder hex = new StringBuilder();

        for (byte value : data) {
            int unsigned = value & 0xFF;
            hex.append(String.format(Locale.US, "%02X ", unsigned));

            if (unsigned == '\r') {
                printable.append("<CR>");
            } else if (unsigned == '\n') {
                printable.append("<LF>\n");
            } else if (unsigned >= 32 && unsigned <= 126) {
                printable.append((char) unsigned);
            } else {
                printable.append(String.format(Locale.US, "<%02X>", unsigned));
            }
        }

        synchronized (logLock) {
            bytesReceived += data.length;
            chunkCount++;
            asciiLog.append('[').append(timestamp).append("] RX ")
                    .append(data.length).append(" bytes | ")
                    .append(printable).append('\n');
            hexLog.append('[').append(timestamp).append("] ")
                    .append(hex).append('\n');
            trimLog(asciiLog);
            trimLog(hexLog);
            parseLines(data);
        }
        scheduleUiRefresh();
    }

    private void parseLines(byte[] data) {
        String text = new String(data, StandardCharsets.US_ASCII);
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\r' || character == '\n') {
                if (lineBuffer.length() > 0) {
                    latestLine = sanitizeLine(lineBuffer.toString());
                    lineCount++;
                    lineBuffer.setLength(0);
                }
            } else if (character >= 32 && character <= 126) {
                lineBuffer.append(character);
                if (lineBuffer.length() > 512) {
                    latestLine = sanitizeLine(lineBuffer.substring(0, 512));
                    lineBuffer.setLength(0);
                    lineCount++;
                }
            } else {
                lineBuffer.append(String.format(Locale.US, "<%02X>", (int) character & 0xFF));
            }
        }
    }

    private String sanitizeLine(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? "—" : trimmed;
    }

    private void addSystemLog(String message) {
        String timestamp = timestampFormat.format(new Date());
        synchronized (logLock) {
            asciiLog.append('[').append(timestamp).append("] SYSTEM | ")
                    .append(message).append('\n');
            trimLog(asciiLog);
        }
        scheduleUiRefresh();
    }

    private void trimLog(StringBuilder builder) {
        if (builder.length() <= MAX_LOG_CHARS) return;
        int removeCount = builder.length() - MAX_LOG_CHARS;
        builder.delete(0, removeCount);
    }

    private void scheduleUiRefresh() {
        if (!uiRefreshPending.compareAndSet(false, true)) return;
        mainHandler.postDelayed(() -> {
            uiRefreshPending.set(false);
            refreshLogsOnScreen();
        }, 100L);
    }

    private void refreshLogsOnScreen() {
        String ascii;
        String hex;
        synchronized (logLock) {
            ascii = asciiLog.toString();
            hex = hexLog.toString();
        }
        txtAsciiLog.setText(ascii);
        txtHexLog.setText(hex);
        txtCounters.setText(
                "Bytes: " + bytesReceived
                        + " | Chunks: " + chunkCount
                        + " | Complete lines: " + lineCount
        );
        txtLatestLine.setText("LATEST COMPLETE LINE | " + latestLine);
    }

    private void clearLogs() {
        synchronized (logLock) {
            asciiLog.setLength(0);
            hexLog.setLength(0);
            lineBuffer.setLength(0);
            bytesReceived = 0L;
            chunkCount = 0L;
            lineCount = 0L;
            latestLine = "—";
        }
        addSystemLog("Log cleared.");
        refreshLogsOnScreen();
    }

    private void copyLogs() {
        String ascii;
        String hex;
        synchronized (logLock) {
            ascii = asciiLog.toString();
            hex = hexLog.toString();
        }
        String report = txtDeviceInfo.getText()
                + "\n" + txtSerialStatus.getText()
                + "\n" + txtCounters.getText()
                + "\n" + txtLatestLine.getText()
                + "\n\nASCII LOG\n" + ascii
                + "\nHEX LOG\n" + hex;

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Tag Reaper Scale Debug", report));
            Toast.makeText(this, "Scale debug log copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void setStatus(String value) {
        txtSerialStatus.setText("SERIAL STATUS | " + value);
    }

    private void updateControls() {
        boolean running = serialRunning;
        btnStartSerial.setEnabled(!running);
        btnStopSerial.setEnabled(running);
        spinnerDevicePath.setEnabled(!running);
        spinnerBaud.setEnabled(!running);
    }

    @Override
    protected void onDestroy() {
        stopSerial(false);
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

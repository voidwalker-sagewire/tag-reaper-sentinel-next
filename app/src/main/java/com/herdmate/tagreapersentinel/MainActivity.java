package com.herdmate.tagreapersentinel;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.rscja.deviceapi.RFIDWithUHFA4;
import com.rscja.deviceapi.entity.AntennaState;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.enums.AntennaEnum;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME =
            "tag_reaper_sentinel";

    private static final String PREF_ACTIVE_SESSION =
            "active_session";

    private static final String PREF_SESSION_HISTORY =
            "session_history";

    private static final long SAVE_THROTTLE_MS = 1000L;
    private static final long DISPLAY_THROTTLE_MS = 250L;

    private static final int MIN_POWER_DBM = 5;
    private static final int MAX_POWER_DBM = 30;

    private static final int REQUEST_EXPORT_CSV = 1001;
    private static final int REQUEST_EXPORT_JSON = 1002;

    private RFIDWithUHFA4 reader;
    private boolean readerConnected = false;

    private Button btnConnect;
    private Button btnNewSession;
    private Button btnStart;
    private Button btnPause;
    private Button btnEndSession;
    private Button btnClear;
    private Button btnHistory;
    private Button btnExportCsv;
    private Button btnExportJson;
    private Button btnShare;

    private CheckBox chkAnt1;
    private CheckBox chkAnt2;
    private CheckBox chkAnt3;
    private CheckBox chkAnt4;

    private SeekBar seekAnt1Power;
    private SeekBar seekAnt2Power;
    private SeekBar seekAnt3Power;
    private SeekBar seekAnt4Power;

    private TextView txtAnt1Power;
    private TextView txtAnt2Power;
    private TextView txtAnt3Power;
    private TextView txtAnt4Power;

    private TextView txtHeader;
    private TextView txtTotalReads;
    private TextView txtUniqueTags;

    private ListView listTags;
    private ArrayAdapter<String> adapter;

    private final List<String> logLines =
            new ArrayList<>();

    private final LinkedHashMap<String, TagRecord> uniqueTags =
            new LinkedHashMap<>();

    private SharedPreferences preferences;

    private SessionState sessionState =
            SessionState.NO_SESSION;

    private String sessionId;

    private long sessionStartedAt;
    private long sessionEndedAt;
    private long pauseStartedAt;
    private long totalPausedMillis;
    private long totalActiveMillis;
    private long activeStartedAt;
    private long lastSaveAt;
    private long lastDisplayAt;

    private int totalReads;

    private String currentStatus =
            "Not connected";

    private String pendingExportText;

    private final Handler sessionClockHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable sessionClockRunnable =
            new Runnable() {

                @Override
                public void run() {
                    updateHeader();

                    sessionClockHandler.postDelayed(
                            this,
                            1000L
                    );
                }
            };

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat(
                    "HH:mm:ss",
                    Locale.US
            );

    private final SimpleDateFormat dateTimeFormat =
            new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.US
            );

    private final SimpleDateFormat fileTimeFormat =
            new SimpleDateFormat(
                    "yyyyMMdd-HHmmss",
                    Locale.US
            );

    private enum SessionState {
        NO_SESSION,
        READY,
        SCANNING,
        PAUSED,
        ENDED
    }

    private static class AntennaRecord {
        int count;

        double strongestRssi = -9999.0;
        double latestRssi = -9999.0;

        long firstSeenAt;
        long lastSeenAt;
    }

    private static class TagRecord {
        String epc;

        int count;

        long firstSeenAt;
        long lastSeenAt;

        double strongestRssi = -9999.0;

        final LinkedHashMap<String, AntennaRecord> antennas =
                new LinkedHashMap<>();
    }

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_main
        );

        preferences =
                getSharedPreferences(
                        PREFS_NAME,
                        MODE_PRIVATE
                );

        bindViews();
        setupPowerControls();

        adapter =
                new ArrayAdapter<>(
                        this,
                        R.layout.list_item_tag,
                        logLines
                );

        listTags.setAdapter(adapter);

        btnConnect.setOnClickListener(
                view -> connectReader()
        );

        btnNewSession.setOnClickListener(
                view -> createNewSession()
        );

        btnStart.setOnClickListener(
                view -> startOrResumeScanning()
        );

        btnPause.setOnClickListener(
                view -> pauseScanning()
        );

        btnEndSession.setOnClickListener(
                view -> endSession()
        );

        btnClear.setOnClickListener(
                view -> clearSession()
        );

        btnHistory.setOnClickListener(
                view -> showHistory()
        );

        btnExportCsv.setOnClickListener(
                view -> beginExport(true)
        );

        btnExportJson.setOnClickListener(
                view -> beginExport(false)
        );

        btnShare.setOnClickListener(
                view -> shareCurrentSession()
        );

        restoreSession();
        refreshDisplay();
        updateControls();

        sessionClockHandler.post(
                sessionClockRunnable
        );
    }

    private void bindViews() {
        btnConnect =
                findViewById(
                        R.id.btnConnect
                );

        btnNewSession =
                findViewById(
                        R.id.btnNewSession
                );

        btnStart =
                findViewById(
                        R.id.btnStart
                );

        btnPause =
                findViewById(
                        R.id.btnPause
                );

        btnEndSession =
                findViewById(
                        R.id.btnEndSession
                );

        btnClear =
                findViewById(
                        R.id.btnClear
                );

        btnHistory =
                findViewById(
                        R.id.btnHistory
                );

        btnExportCsv =
                findViewById(
                        R.id.btnExportCsv
                );

        btnExportJson =
                findViewById(
                        R.id.btnExportJson
                );

        btnShare =
                findViewById(
                        R.id.btnShare
                );

        chkAnt1 =
                findViewById(
                        R.id.chkAnt1
                );

        chkAnt2 =
                findViewById(
                        R.id.chkAnt2
                );

        chkAnt3 =
                findViewById(
                        R.id.chkAnt3
                );

        chkAnt4 =
                findViewById(
                        R.id.chkAnt4
                );

        seekAnt1Power =
                findViewById(
                        R.id.seekAnt1Power
                );

        seekAnt2Power =
                findViewById(
                        R.id.seekAnt2Power
                );

        seekAnt3Power =
                findViewById(
                        R.id.seekAnt3Power
                );

        seekAnt4Power =
                findViewById(
                        R.id.seekAnt4Power
                );

        txtAnt1Power =
                findViewById(
                        R.id.txtAnt1Power
                );

        txtAnt2Power =
                findViewById(
                        R.id.txtAnt2Power
                );

        txtAnt3Power =
                findViewById(
                        R.id.txtAnt3Power
                );

        txtAnt4Power =
                findViewById(
                        R.id.txtAnt4Power
                );

        txtHeader =
                findViewById(
                        R.id.txtHeader
                );

        txtTotalReads =
                findViewById(
                        R.id.txtTotalReads
                );

        txtUniqueTags =
                findViewById(
                        R.id.txtUniqueTags
                );

        listTags =
                findViewById(
                        R.id.listTags
                );
    }

    private void setupPowerControls() {
        setupPowerControl(
                1,
                seekAnt1Power,
                txtAnt1Power,
                30
        );

        setupPowerControl(
                2,
                seekAnt2Power,
                txtAnt2Power,
                30
        );

        setupPowerControl(
                3,
                seekAnt3Power,
                txtAnt3Power,
                30
        );

        setupPowerControl(
                4,
                seekAnt4Power,
                txtAnt4Power,
                30
        );
    }

    private void setupPowerControl(
            int antennaNumber,
            SeekBar seekBar,
            TextView label,
            int initialDbm
    ) {
        seekBar.setMax(
                MAX_POWER_DBM - MIN_POWER_DBM
        );

        seekBar.setProgress(
                initialDbm - MIN_POWER_DBM
        );

        updatePowerLabel(
                antennaNumber,
                label,
                initialDbm
        );

        seekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar bar,
                            int progress,
                            boolean fromUser
                    ) {
                        updatePowerLabel(
                                antennaNumber,
                                label,
                                MIN_POWER_DBM + progress
                        );
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar bar
                    ) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar bar
                    ) {
                        saveSessionNow();
                    }
                }
        );
    }

    private void updatePowerLabel(
            int antennaNumber,
            TextView label,
            int power
    ) {
        label.setText(
                String.format(
                        Locale.US,
                        "ANT %d  •  %d dBm",
                        antennaNumber,
                        power
                )
        );
    }

    private int selectedPower(
            SeekBar seekBar
    ) {
        return MIN_POWER_DBM
                + seekBar.getProgress();
    }

    private void connectReader() {
        setStatus("Connecting");

        btnConnect.setEnabled(false);

        new Thread(() -> {
            boolean connected;
            String error = null;

            try {
                reader =
                        RFIDWithUHFA4.getInstance();

                connected =
                        reader.init(
                                getApplicationContext()
                        );
            } catch (Throwable throwable) {
                connected = false;

                error =
                        throwable
                                .getClass()
                                .getSimpleName()
                                + ": "
                                + throwable.getMessage();
            }

            final boolean finalConnected =
                    connected;

            final String finalError =
                    error;

            runOnUiThread(() -> {
                readerConnected =
                        finalConnected;

                if (finalConnected) {
                    reader.setInventoryCallback(
                            inventoryCallback
                    );

                    setStatus(
                            "Reader connected"
                    );
                } else if (finalError != null) {
                    setStatus(
                            "Connection failed: "
                                    + finalError
                    );
                } else {
                    setStatus(
                            "Connection failed: init returned false"
                    );
                }

                updateControls();
            });
        }).start();
    }

    private void createNewSession() {
        if (sessionState
                == SessionState.SCANNING) {

            setStatus(
                    "Pause or end the current session first"
            );

            return;
        }

        uniqueTags.clear();
        totalReads = 0;

        sessionId =
                UUID.randomUUID().toString();

        sessionStartedAt =
                System.currentTimeMillis();

        sessionEndedAt = 0L;
        pauseStartedAt = 0L;
        totalPausedMillis = 0L;
        totalActiveMillis = 0L;
        activeStartedAt = 0L;

        sessionState =
                SessionState.READY;

        saveSessionNow();
        refreshDisplay();

        setStatus(
                "New session ready"
        );

        updateControls();
    }

    private void startOrResumeScanning() {
        if (!readerConnected
                || reader == null) {

            setStatus(
                    "Connect the reader first"
            );

            return;
        }

        if (sessionState
                != SessionState.READY
                && sessionState
                != SessionState.PAUSED) {

            setStatus(
                    "Start a new session first"
            );

            return;
        }

        if (!hasSelectedAntenna()) {
            setStatus(
                    "Select at least one antenna"
            );

            return;
        }

        applyAntennaSelection();

        new Thread(() -> {
            String powerError =
                    applyAndVerifyAntennaPower();

            if (powerError != null) {
                runOnUiThread(() -> {
                    setStatus(powerError);
                    updateControls();
                });

                return;
            }

            boolean started;
            String error = null;

            try {
                started =
                        reader.startInventoryTag();
            } catch (Throwable throwable) {
                started = false;

                error =
                        throwable
                                .getClass()
                                .getSimpleName()
                                + ": "
                                + throwable.getMessage();
            }

            final boolean finalStarted =
                    started;

            final String finalError =
                    error;

            runOnUiThread(() -> {
                if (finalStarted) {
                    long now =
                            System.currentTimeMillis();

                    if (pauseStartedAt > 0L) {
                        totalPausedMillis +=
                                now - pauseStartedAt;

                        pauseStartedAt = 0L;
                    }

                    sessionState =
                            SessionState.SCANNING;

                    activeStartedAt = now;

                    saveSessionNow();

                    setStatus(
                            "Scanning"
                    );
                } else if (finalError != null) {
                    setStatus(
                            "Failed to start: "
                                    + finalError
                    );
                } else {
                    setStatus(
                            "Failed to start scan"
                    );
                }

                updateControls();
            });
        }).start();
    }

    private void pauseScanning() {
        if (sessionState
                != SessionState.SCANNING) {

            return;
        }

        new Thread(() -> {
            String error = null;

            try {
                if (reader != null) {
                    reader.stopInventory();
                }
            } catch (Throwable throwable) {
                error =
                        throwable
                                .getClass()
                                .getSimpleName()
                                + ": "
                                + throwable.getMessage();
            }

            final String finalError =
                    error;

            runOnUiThread(() -> {
                long now =
                        System.currentTimeMillis();

                if (activeStartedAt > 0L) {
                    totalActiveMillis +=
                            now - activeStartedAt;

                    activeStartedAt = 0L;
                }

                sessionState =
                        SessionState.PAUSED;

                pauseStartedAt = now;

                saveSessionNow();

                if (finalError == null) {
                    setStatus(
                            "Session paused"
                    );
                } else {
                    setStatus(
                            "Paused; reader warning: "
                                    + finalError
                    );
                }

                updateControls();
            });
        }).start();
    }

    private void endSession() {
        if (sessionState
                == SessionState.NO_SESSION
                || sessionState
                == SessionState.ENDED) {

            return;
        }

        new Thread(() -> {
            if (reader != null
                    && sessionState
                    == SessionState.SCANNING) {

                try {
                    reader.stopInventory();
                } catch (Throwable ignored) {
                }
            }

            runOnUiThread(() -> {
                long now =
                        System.currentTimeMillis();

                if (activeStartedAt > 0L) {
                    totalActiveMillis +=
                            now - activeStartedAt;

                    activeStartedAt = 0L;
                }

                if (pauseStartedAt > 0L) {
                    totalPausedMillis +=
                            now - pauseStartedAt;

                    pauseStartedAt = 0L;
                }

                sessionEndedAt = now;

                sessionState =
                        SessionState.ENDED;

                saveSessionNow();
                archiveCurrentSession();

                setStatus(
                        "Session ended and saved"
                );

                updateControls();
            });
        }).start();
    }

    private void clearSession() {
        if (sessionState
                == SessionState.SCANNING) {

            setStatus(
                    "Pause before clearing session data"
            );

            return;
        }

        uniqueTags.clear();
        totalReads = 0;

        sessionId = null;
        sessionStartedAt = 0L;
        sessionEndedAt = 0L;
        pauseStartedAt = 0L;
        totalPausedMillis = 0L;
        totalActiveMillis = 0L;
        activeStartedAt = 0L;

        sessionState =
                SessionState.NO_SESSION;

        preferences
                .edit()
                .remove(
                        PREF_ACTIVE_SESSION
                )
                .apply();

        refreshDisplay();

        if (readerConnected) {
            setStatus(
                    "Reader connected; no session"
            );
        } else {
            setStatus(
                    "Not connected"
            );
        }

        updateControls();
    }

    private boolean hasSelectedAntenna() {
        return chkAnt1.isChecked()
                || chkAnt2.isChecked()
                || chkAnt3.isChecked()
                || chkAnt4.isChecked();
    }

    private void applyAntennaSelection() {
        List<AntennaState> states =
                new ArrayList<>();

        states.add(
                new AntennaState(
                        AntennaEnum.ANT1,
                        chkAnt1.isChecked()
                )
        );

        states.add(
                new AntennaState(
                        AntennaEnum.ANT2,
                        chkAnt2.isChecked()
                )
        );

        states.add(
                new AntennaState(
                        AntennaEnum.ANT3,
                        chkAnt3.isChecked()
                )
        );

        states.add(
                new AntennaState(
                        AntennaEnum.ANT4,
                        chkAnt4.isChecked()
                )
        );

        reader.setANT(states);
    }

    private String applyAndVerifyAntennaPower() {
        try {
            AntennaEnum[] antennas = {
                    AntennaEnum.ANT1,
                    AntennaEnum.ANT2,
                    AntennaEnum.ANT3,
                    AntennaEnum.ANT4
            };

            SeekBar[] controls = {
                    seekAnt1Power,
                    seekAnt2Power,
                    seekAnt3Power,
                    seekAnt4Power
            };

            for (int index = 0;
                 index < antennas.length;
                 index++) {

                int requested =
                        selectedPower(
                                controls[index]
                        );

                boolean accepted =
                        reader.setAntennaPower(
                                antennas[index],
                                requested
                        );

                if (!accepted) {
                    return "ANT "
                            + (index + 1)
                            + " power rejected";
                }

                int confirmed =
                        reader.getAntennaPower(
                                antennas[index]
                        );

                if (confirmed != requested) {
                    return "ANT "
                            + (index + 1)
                            + " power mismatch: "
                            + requested
                            + " requested, "
                            + confirmed
                            + " confirmed";
                }
            }

            return null;
        } catch (Throwable throwable) {
            return "Power setup failed: "
                    + throwable
                    .getClass()
                    .getSimpleName()
                    + ": "
                    + throwable.getMessage();
        }
    }

    private final IUHFInventoryCallback inventoryCallback =
            new IUHFInventoryCallback() {

                @Override
                public void callback(
                        UHFTAGInfo tagInfo
                ) {
                    if (tagInfo == null
                            || tagInfo.getEPC() == null) {

                        return;
                    }

                    runOnUiThread(() -> {
                        if (sessionState
                                != SessionState.SCANNING) {

                            return;
                        }

                        long now =
                                System.currentTimeMillis();

                        String epc =
                                tagInfo.getEPC();

                        String antenna =
                                normalizeAntenna(
                                        tagInfo.getAnt()
                                );

                        double rssi =
                                safeRssi(tagInfo);

                        TagRecord record =
                                uniqueTags.get(epc);

                        if (record == null) {
                            record =
                                    new TagRecord();

                            record.epc = epc;
                            record.firstSeenAt = now;

                            uniqueTags.put(
                                    epc,
                                    record
                            );
                        }

                        record.count++;
                        record.lastSeenAt = now;

                        if (rssi
                                > record.strongestRssi) {

                            record.strongestRssi =
                                    rssi;
                        }

                        AntennaRecord antennaRecord =
                                record.antennas.get(
                                        antenna
                                );

                        if (antennaRecord == null) {
                            antennaRecord =
                                    new AntennaRecord();

                            antennaRecord.firstSeenAt =
                                    now;

                            record.antennas.put(
                                    antenna,
                                    antennaRecord
                            );
                        }

                        antennaRecord.count++;
                        antennaRecord.lastSeenAt = now;
                        antennaRecord.latestRssi = rssi;

                        if (rssi
                                > antennaRecord
                                .strongestRssi) {

                            antennaRecord
                                    .strongestRssi =
                                    rssi;
                        }

                        totalReads++;

                        refreshDisplayThrottled();
                        saveSessionThrottled();
                    });
                }
            };

    private String normalizeAntenna(
            String rawAntenna
    ) {
        if (rawAntenna == null
                || rawAntenna
                .trim()
                .isEmpty()) {

            return "?";
        }

        String digits =
                rawAntenna.replaceAll(
                        "[^0-9]",
                        ""
                );

        if (digits.isEmpty()) {
            return rawAntenna.trim();
        }

        return digits;
    }

    private double safeRssi(
            UHFTAGInfo tagInfo
    ) {
        try {
            String value =
                    tagInfo.getRssi();

            if (value == null) {
                return -9999.0;
            }

            return Double.parseDouble(
                    value
            );
        } catch (Exception ignored) {
            return -9999.0;
        }
    }

    private void refreshDisplayThrottled() {
        long now =
                System.currentTimeMillis();

        if (now - lastDisplayAt
                >= DISPLAY_THROTTLE_MS) {

            lastDisplayAt = now;

            refreshDisplay();
        }
    }

    private void refreshDisplay() {
        logLines.clear();

        for (TagRecord record
                : uniqueTags.values()) {

            String line =
                    String.format(
                            Locale.US,
                            "%s | x%d | best %s | %s-%s\n"
                                    + "A1 %d | A2 %d | A3 %d | A4 %d",
                            record.epc,
                            record.count,
                            formatRssi(
                                    record.strongestRssi
                            ),
                            formatTime(
                                    record.firstSeenAt
                            ),
                            formatTime(
                                    record.lastSeenAt
                            ),
                            antennaCount(
                                    record,
                                    "1"
                            ),
                            antennaCount(
                                    record,
                                    "2"
                            ),
                            antennaCount(
                                    record,
                                    "3"
                            ),
                            antennaCount(
                                    record,
                                    "4"
                            )
                    );

            logLines.add(line);
        }

        adapter.notifyDataSetChanged();

        txtTotalReads.setText(
                "Reads: " + totalReads
        );

        txtUniqueTags.setText(
                "Tags: " + uniqueTags.size()
        );

        updateHeader();
    }

    private int antennaCount(
            TagRecord record,
            String antenna
    ) {
        AntennaRecord antennaRecord =
                record.antennas.get(
                        antenna
                );

        if (antennaRecord == null) {
            return 0;
        }

        return antennaRecord.count;
    }

    private String formatRssi(
            double rssi
    ) {
        if (rssi <= -9998.0) {
            return "?";
        }

        return String.format(
                Locale.US,
                "%.2f",
                rssi
        );
    }

    private String formatTime(
            long timeMillis
    ) {
        if (timeMillis <= 0L) {
            return "--:--:--";
        }

        return timeFormat.format(
                new Date(timeMillis)
        );
    }

    private String formatDateTime(
            long timeMillis
    ) {
        if (timeMillis <= 0L) {
            return "unknown";
        }

        return dateTimeFormat.format(
                new Date(timeMillis)
        );
    }

    private void setStatus(
            String message
    ) {
        currentStatus = message;
        updateHeader();
    }

    private void updateHeader() {
        String connection =
                readerConnected
                        ? "Connected"
                        : "Disconnected";

        if (sessionId == null) {
            txtHeader.setText(
                    "TAG REAPER SENTINEL | "
                            + connection
                            + " | "
                            + currentStatus
                            + " | No session"
            );

            return;
        }

        String shortId;

        if (sessionId.length() > 8) {
            shortId =
                    sessionId.substring(
                            0,
                            8
                    );
        } else {
            shortId = sessionId;
        }

        txtHeader.setText(
                String.format(
                        Locale.US,
                        "TAG REAPER SENTINEL | %s | %s | %s | "
                                + "E %s A %s P %s | %d reads | %d tags",
                        connection,
                        shortId,
                        sessionState.name(),
                        formatDuration(
                                currentElapsedMillis()
                        ),
                        formatDuration(
                                currentActiveMillis()
                        ),
                        formatDuration(
                                currentPausedMillis()
                        ),
                        totalReads,
                        uniqueTags.size()
                )
        );
    }

    private long currentElapsedMillis() {
        if (sessionStartedAt <= 0L) {
            return 0L;
        }

        long endTime;

        if (sessionEndedAt > 0L) {
            endTime = sessionEndedAt;
        } else {
            endTime =
                    System.currentTimeMillis();
        }

        return Math.max(
                0L,
                endTime - sessionStartedAt
        );
    }

    private long currentActiveMillis() {
        long currentActive = 0L;

        if (activeStartedAt > 0L) {
            currentActive =
                    System.currentTimeMillis()
                            - activeStartedAt;
        }

        return totalActiveMillis
                + currentActive;
    }

    private long currentPausedMillis() {
        long currentPause = 0L;

        if (pauseStartedAt > 0L) {
            currentPause =
                    System.currentTimeMillis()
                            - pauseStartedAt;
        }

        return totalPausedMillis
                + currentPause;
    }

    private String formatDuration(
            long timeMillis
    ) {
        long totalSeconds =
                Math.max(
                        0L,
                        timeMillis
                ) / 1000L;

        long hours =
                totalSeconds / 3600L;

        long minutes =
                (totalSeconds % 3600L)
                        / 60L;

        long seconds =
                totalSeconds % 60L;

        if (hours > 0L) {
            return String.format(
                    Locale.US,
                    "%d:%02d:%02d",
                    hours,
                    minutes,
                    seconds
            );
        }

        return String.format(
                Locale.US,
                "%02d:%02d",
                minutes,
                seconds
        );
    }

    private void updateControls() {
        boolean openSession =
                sessionState
                == SessionState.READY
                || sessionState
                == SessionState.SCANNING
                || sessionState
                == SessionState.PAUSED;

        boolean hasSessionData =
                sessionId != null;

        boolean canExport =
                hasSessionData
                && sessionState
                == SessionState.ENDED;

        boolean antennaControlsEnabled =
                sessionState
                != SessionState.SCANNING;

        btnConnect.setEnabled(
                !readerConnected
        );

        btnNewSession.setEnabled(
                sessionState
                != SessionState.SCANNING
        );

        btnStart.setEnabled(
                readerConnected
                        && (
                        sessionState
                        == SessionState.READY
                                || sessionState
                                == SessionState.PAUSED
                )
        );

        if (sessionState
                == SessionState.PAUSED) {

            btnStart.setText(
                    "Resume Scan"
            );
        } else {
            btnStart.setText(
                    "Start Scan"
            );
        }

        btnPause.setEnabled(
                sessionState
                == SessionState.SCANNING
        );

        btnEndSession.setEnabled(
                openSession
        );

        btnClear.setEnabled(
                sessionState
                != SessionState.SCANNING
        );

        btnExportCsv.setEnabled(
                canExport
        );

        btnExportJson.setEnabled(
                canExport
        );

        btnShare.setEnabled(
                canExport
        );

        chkAnt1.setEnabled(
                antennaControlsEnabled
        );

        chkAnt2.setEnabled(
                antennaControlsEnabled
        );

        chkAnt3.setEnabled(
                antennaControlsEnabled
        );

        chkAnt4.setEnabled(
                antennaControlsEnabled
        );

        seekAnt1Power.setEnabled(
                antennaControlsEnabled
        );

        seekAnt2Power.setEnabled(
                antennaControlsEnabled
        );

        seekAnt3Power.setEnabled(
                antennaControlsEnabled
        );

        seekAnt4Power.setEnabled(
                antennaControlsEnabled
        );

        updateHeader();
    }

    private void saveSessionThrottled() {
        long now =
                System.currentTimeMillis();

        if (now - lastSaveAt
                >= SAVE_THROTTLE_MS) {

            saveSessionNow();
        }
    }

    private void saveSessionNow() {
        lastSaveAt =
                System.currentTimeMillis();

        if (sessionId == null) {
            return;
        }

        try {
            preferences
                    .edit()
                    .putString(
                            PREF_ACTIVE_SESSION,
                            buildSessionJson()
                                    .toString()
                    )
                    .apply();
        } catch (Exception exception) {
            setStatus(
                    "Local save failed: "
                            + exception.getMessage()
            );
        }
    }

    private JSONObject buildSessionJson()
            throws Exception {

        JSONObject root =
                new JSONObject();

        root.put(
                "sessionId",
                sessionId
        );

        root.put(
                "state",
                sessionState.name()
        );

        root.put(
                "startedAt",
                sessionStartedAt
        );

        root.put(
                "endedAt",
                sessionEndedAt
        );

        root.put(
                "pauseStartedAt",
                pauseStartedAt
        );

        root.put(
                "totalPausedMillis",
                totalPausedMillis
        );

        root.put(
                "totalActiveMillis",
                totalActiveMillis
        );

        root.put(
                "activeStartedAt",
                activeStartedAt
        );

        root.put(
                "elapsedMillis",
                currentElapsedMillis()
        );

        root.put(
                "activeMillis",
                currentActiveMillis()
        );

        root.put(
                "pausedMillis",
                currentPausedMillis()
        );

        root.put(
                "totalReads",
                totalReads
        );

        JSONObject configuration =
                new JSONObject();

        configuration.put(
                "ant1Enabled",
                chkAnt1.isChecked()
        );

        configuration.put(
                "ant2Enabled",
                chkAnt2.isChecked()
        );

        configuration.put(
                "ant3Enabled",
                chkAnt3.isChecked()
        );

        configuration.put(
                "ant4Enabled",
                chkAnt4.isChecked()
        );

        configuration.put(
                "ant1PowerDbm",
                selectedPower(
                        seekAnt1Power
                )
        );

        configuration.put(
                "ant2PowerDbm",
                selectedPower(
                        seekAnt2Power
                )
        );

        configuration.put(
                "ant3PowerDbm",
                selectedPower(
                        seekAnt3Power
                )
        );

        configuration.put(
                "ant4PowerDbm",
                selectedPower(
                        seekAnt4Power
                )
        );

        root.put(
                "configuration",
                configuration
        );

        JSONArray tags =
                new JSONArray();

        for (TagRecord record
                : uniqueTags.values()) {

            JSONObject tag =
                    new JSONObject();

            tag.put(
                    "epc",
                    record.epc
            );

            tag.put(
                    "count",
                    record.count
            );

            tag.put(
                    "firstSeenAt",
                    record.firstSeenAt
            );

            tag.put(
                    "lastSeenAt",
                    record.lastSeenAt
            );

            tag.put(
                    "strongestRssi",
                    record.strongestRssi
            );

            JSONArray antennas =
                    new JSONArray();

            for (
                    Map.Entry<String, AntennaRecord> entry
                    : record.antennas.entrySet()
            ) {
                AntennaRecord antennaRecord =
                        entry.getValue();

                JSONObject antenna =
                        new JSONObject();

                antenna.put(
                        "id",
                        entry.getKey()
                );

                antenna.put(
                        "count",
                        antennaRecord.count
                );

                antenna.put(
                        "strongestRssi",
                        antennaRecord
                                .strongestRssi
                );

                antenna.put(
                        "latestRssi",
                        antennaRecord.latestRssi
                );

                antenna.put(
                        "firstSeenAt",
                        antennaRecord.firstSeenAt
                );

                antenna.put(
                        "lastSeenAt",
                        antennaRecord.lastSeenAt
                );

                antennas.put(antenna);
            }

            tag.put(
                    "antennas",
                    antennas
            );

            tags.put(tag);
        }

        root.put(
                "tags",
                tags
        );

        return root;
    }

    private void restoreSession() {
        String storedSession =
                preferences.getString(
                        PREF_ACTIVE_SESSION,
                        null
                );

        if (storedSession == null) {
            return;
        }

        try {
            loadSessionFromJson(
                    new JSONObject(
                            storedSession
                    ),
                    true
            );

            saveSessionNow();
        } catch (Exception exception) {
            preferences
                    .edit()
                    .remove(
                            PREF_ACTIVE_SESSION
                    )
                    .apply();

            sessionState =
                    SessionState.NO_SESSION;

            setStatus(
                    "Saved session could not be restored"
            );
        }
    }

    private void loadSessionFromJson(
            JSONObject root,
            boolean recoverScanningAsPaused
    ) throws Exception {

        sessionId =
                root.optString(
                        "sessionId",
                        null
                );

        sessionState =
                SessionState.valueOf(
                        root.optString(
                                "state",
                                SessionState
                                        .PAUSED
                                        .name()
                        )
                );

        sessionStartedAt =
                root.optLong(
                        "startedAt",
                        0L
                );

        sessionEndedAt =
                root.optLong(
                        "endedAt",
                        0L
                );

        pauseStartedAt =
                root.optLong(
                        "pauseStartedAt",
                        0L
                );

        totalPausedMillis =
                root.optLong(
                        "totalPausedMillis",
                        0L
                );

        totalActiveMillis =
                root.optLong(
                        "totalActiveMillis",
                        0L
                );

        activeStartedAt =
                root.optLong(
                        "activeStartedAt",
                        0L
                );

        totalReads =
                root.optInt(
                        "totalReads",
                        0
                );

        if (recoverScanningAsPaused
                && sessionState
                == SessionState.SCANNING) {

            long now =
                    System.currentTimeMillis();

            if (activeStartedAt > 0L) {
                totalActiveMillis +=
                        now - activeStartedAt;

                activeStartedAt = 0L;
            }

            sessionState =
                    SessionState.PAUSED;

            pauseStartedAt = now;

            setStatus(
                    "Recovered session; paused for safety"
            );
        } else {
            activeStartedAt = 0L;

            if (sessionState
                    != SessionState.ENDED) {

                setStatus(
                        "Recovered saved session"
                );
            }
        }

        JSONObject configuration =
                root.optJSONObject(
                        "configuration"
                );

        if (configuration != null) {
            chkAnt1.setChecked(
                    configuration.optBoolean(
                            "ant1Enabled",
                            true
                    )
            );

            chkAnt2.setChecked(
                    configuration.optBoolean(
                            "ant2Enabled",
                            true
                    )
            );

            chkAnt3.setChecked(
                    configuration.optBoolean(
                            "ant3Enabled",
                            true
                    )
            );

            chkAnt4.setChecked(
                    configuration.optBoolean(
                            "ant4Enabled",
                            true
                    )
            );

            setPower(
                    seekAnt1Power,
                    configuration.optInt(
                            "ant1PowerDbm",
                            30
                    )
            );

            setPower(
                    seekAnt2Power,
                    configuration.optInt(
                            "ant2PowerDbm",
                            30
                    )
            );

            setPower(
                    seekAnt3Power,
                    configuration.optInt(
                            "ant3PowerDbm",
                            30
                    )
            );

            setPower(
                    seekAnt4Power,
                    configuration.optInt(
                            "ant4PowerDbm",
                            30
                    )
            );
        }

        uniqueTags.clear();

        JSONArray tags =
                root.optJSONArray(
                        "tags"
                );

        if (tags != null) {
            for (
                    int index = 0;
                    index < tags.length();
                    index++
            ) {
                JSONObject tag =
                        tags.getJSONObject(
                                index
                        );

                TagRecord record =
                        new TagRecord();

                record.epc =
                        tag.optString(
                                "epc",
                                ""
                        );

                record.count =
                        tag.optInt(
                                "count",
                                0
                        );

                record.firstSeenAt =
                        tag.optLong(
                                "firstSeenAt",
                                0L
                        );

                record.lastSeenAt =
                        tag.optLong(
                                "lastSeenAt",
                                0L
                        );

                record.strongestRssi =
                        tag.optDouble(
                                "strongestRssi",
                                -9999.0
                        );

                JSONArray antennas =
                        tag.optJSONArray(
                                "antennas"
                        );

                if (antennas != null) {
                    for (
                            int antennaIndex = 0;
                            antennaIndex
                                    < antennas.length();
                            antennaIndex++
                    ) {
                        JSONObject antenna =
                                antennas.getJSONObject(
                                        antennaIndex
                                );

                        AntennaRecord antennaRecord =
                                new AntennaRecord();

                        antennaRecord.count =
                                antenna.optInt(
                                        "count",
                                        0
                                );

                        antennaRecord.strongestRssi =
                                antenna.optDouble(
                                        "strongestRssi",
                                        -9999.0
                                );

                        antennaRecord.latestRssi =
                                antenna.optDouble(
                                        "latestRssi",
                                        -9999.0
                                );

                        antennaRecord.firstSeenAt =
                                antenna.optLong(
                                        "firstSeenAt",
                                        0L
                                );

                        antennaRecord.lastSeenAt =
                                antenna.optLong(
                                        "lastSeenAt",
                                        0L
                                );

                        record.antennas.put(
                                antenna.optString(
                                        "id",
                                        "?"
                                ),
                                antennaRecord
                        );
                    }
                }

                if (!record.epc.isEmpty()) {
                    uniqueTags.put(
                            record.epc,
                            record
                    );
                }
            }
        }

        refreshDisplay();
        updateControls();
    }

    private void setPower(
            SeekBar seekBar,
            int dbm
    ) {
        int clamped =
                Math.max(
                        MIN_POWER_DBM,
                        Math.min(
                                MAX_POWER_DBM,
                                dbm
                        )
                );

        seekBar.setProgress(
                clamped - MIN_POWER_DBM
        );
    }

    private void archiveCurrentSession() {
        try {
            JSONObject current =
                    buildSessionJson();

            JSONArray history =
                    getHistoryArray();

            JSONArray updated =
                    new JSONArray();

            String currentId =
                    current.optString(
                            "sessionId",
                            ""
                    );

            updated.put(current);

            for (
                    int index = 0;
                    index < history.length();
                    index++
            ) {
                JSONObject old =
                        history.getJSONObject(
                                index
                        );

                String oldId =
                        old.optString(
                                "sessionId",
                                ""
                        );

                if (!currentId.equals(oldId)) {
                    updated.put(old);
                }

                if (updated.length() >= 100) {
                    break;
                }
            }

            preferences
                    .edit()
                    .putString(
                            PREF_SESSION_HISTORY,
                            updated.toString()
                    )
                    .apply();
        } catch (Exception exception) {
            setStatus(
                    "History save failed: "
                            + exception.getMessage()
            );
        }
    }

    private JSONArray getHistoryArray() {
        try {
            String stored =
                    preferences.getString(
                            PREF_SESSION_HISTORY,
                            "[]"
                    );

            return new JSONArray(stored);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void showHistory() {
        JSONArray history =
                getHistoryArray();

        if (history.length() == 0) {
            setStatus(
                    "No completed sessions in history"
            );

            return;
        }

        String[] labels =
                new String[
                        history.length()
                        ];

        for (
                int index = 0;
                index < history.length();
                index++
        ) {
            JSONObject session =
                    history.optJSONObject(
                            index
                    );

            if (session == null) {
                labels[index] =
                        "Unknown session";

                continue;
            }

            String id =
                    session.optString(
                            "sessionId",
                            "????????"
                    );

            String shortId;

            if (id.length() > 8) {
                shortId =
                        id.substring(
                                0,
                                8
                        );
            } else {
                shortId = id;
            }

            JSONArray tags =
                    session.optJSONArray(
                            "tags"
                    );

            int tagCount =
                    tags == null
                            ? 0
                            : tags.length();

            labels[index] =
                    String.format(
                            Locale.US,
                            "%s | %s | %d reads | %d tags",
                            shortId,
                            formatDateTime(
                                    session.optLong(
                                            "startedAt",
                                            0L
                                    )
                            ),
                            session.optInt(
                                    "totalReads",
                                    0
                            ),
                            tagCount
                    );
        }

        new AlertDialog.Builder(this)
                .setTitle(
                        "Completed Sessions"
                )
                .setItems(
                        labels,
                        (dialog, selectedIndex) -> {
                            JSONObject selected =
                                    history.optJSONObject(
                                            selectedIndex
                                    );

                            if (selected == null) {
                                return;
                            }

                            try {
                                loadSessionFromJson(
                                        selected,
                                        false
                                );

                                sessionState =
                                        SessionState.ENDED;

                                sessionEndedAt =
                                        selected.optLong(
                                                "endedAt",
                                                sessionEndedAt
                                        );

                                saveSessionNow();

                                setStatus(
                                        "Opened history session"
                                );

                                updateControls();
                            } catch (Exception exception) {
                                setStatus(
                                        "Could not open history: "
                                                + exception
                                                .getMessage()
                                );
                            }
                        }
                )
                .setNegativeButton(
                        "Close",
                        null
                )
                .show();
    }

    private void beginExport(
            boolean csv
    ) {
        if (sessionId == null
                || sessionState
                != SessionState.ENDED) {

            setStatus(
                    "End the session before exporting"
            );

            return;
        }

        try {
            if (csv) {
                pendingExportText =
                        buildCsv();
            } else {
                pendingExportText =
                        buildSessionJson()
                                .toString(2);
            }

            Intent intent =
                    new Intent(
                            Intent.ACTION_CREATE_DOCUMENT
                    );

            intent.addCategory(
                    Intent.CATEGORY_OPENABLE
            );

            if (csv) {
                intent.setType(
                        "text/csv"
                );
            } else {
                intent.setType(
                        "application/json"
                );
            }

            intent.putExtra(
                    Intent.EXTRA_TITLE,
                    exportFileName(csv)
            );

            startActivityForResult(
                    intent,
                    csv
                            ? REQUEST_EXPORT_CSV
                            : REQUEST_EXPORT_JSON
            );
        } catch (Exception exception) {
            setStatus(
                    "Export preparation failed: "
                            + exception.getMessage()
            );
        }
    }

    private String exportFileName(
            boolean csv
    ) {
        String shortId;

        if (sessionId == null) {
            shortId = "session";
        } else {
            shortId =
                    sessionId.substring(
                            0,
                            Math.min(
                                    8,
                                    sessionId.length()
                            )
                    );
        }

        long stamp;

        if (sessionEndedAt > 0L) {
            stamp = sessionEndedAt;
        } else {
            stamp =
                    System.currentTimeMillis();
        }

        return "sentinel-"
                + shortId
                + "-"
                + fileTimeFormat.format(
                        new Date(stamp)
                )
                + (
                csv
                        ? ".csv"
                        : ".json"
        );
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        boolean validRequest =
                requestCode
                == REQUEST_EXPORT_CSV
                || requestCode
                == REQUEST_EXPORT_JSON;

        if (!validRequest
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null
                || pendingExportText == null) {

            return;
        }

        Uri uri =
                data.getData();

        try (
                OutputStream output =
                        getContentResolver()
                                .openOutputStream(uri)
        ) {
            if (output == null) {
                throw new IllegalStateException(
                        "Could not open selected file"
                );
            }

            output.write(
                    pendingExportText.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

            output.flush();

            if (requestCode
                    == REQUEST_EXPORT_CSV) {

                setStatus(
                        "CSV exported"
                );
            } else {
                setStatus(
                        "JSON exported"
                );
            }
        } catch (Exception exception) {
            setStatus(
                    "Export failed: "
                            + exception.getMessage()
            );
        } finally {
            pendingExportText = null;
        }
    }

    private String buildCsv() {
        StringBuilder csv =
                new StringBuilder();

        csv.append(
                "session_id,"
        );

        csv.append(
                "session_started,"
        );

        csv.append(
                "session_ended,"
        );

        csv.append(
                "elapsed_ms,"
        );

        csv.append(
                "active_ms,"
        );

        csv.append(
                "paused_ms,"
        );

        csv.append(
                "epc,"
        );

        csv.append(
                "total_reads,"
        );

        csv.append(
                "first_seen,"
        );

        csv.append(
                "last_seen,"
        );

        csv.append(
                "strongest_rssi,"
        );

        csv.append(
                "ant1_enabled,"
        );

        csv.append(
                "ant1_power_dbm,"
        );

        csv.append(
                "ant1_reads,"
        );

        csv.append(
                "ant1_strongest_rssi,"
        );

        csv.append(
                "ant2_enabled,"
        );

        csv.append(
                "ant2_power_dbm,"
        );

        csv.append(
                "ant2_reads,"
        );

        csv.append(
                "ant2_strongest_rssi,"
        );

        csv.append(
                "ant3_enabled,"
        );

        csv.append(
                "ant3_power_dbm,"
        );

        csv.append(
                "ant3_reads,"
        );

        csv.append(
                "ant3_strongest_rssi,"
        );

        csv.append(
                "ant4_enabled,"
        );

        csv.append(
                "ant4_power_dbm,"
        );

        csv.append(
                "ant4_reads,"
        );

        csv.append(
                "ant4_strongest_rssi\n"
        );

        for (TagRecord record
                : uniqueTags.values()) {

            csv.append(
                    csvCell(sessionId)
            ).append(',');

            csv.append(
                    csvCell(
                            formatDateTime(
                                    sessionStartedAt
                            )
                    )
            ).append(',');

            csv.append(
                    csvCell(
                            formatDateTime(
                                    sessionEndedAt
                            )
                    )
            ).append(',');

            csv.append(
                    currentElapsedMillis()
            ).append(',');

            csv.append(
                    currentActiveMillis()
            ).append(',');

            csv.append(
                    currentPausedMillis()
            ).append(',');

            csv.append(
                    csvCell(record.epc)
            ).append(',');

            csv.append(
                    record.count
            ).append(',');

            csv.append(
                    csvCell(
                            formatDateTime(
                                    record.firstSeenAt
                            )
                    )
            ).append(',');

            csv.append(
                    csvCell(
                            formatDateTime(
                                    record.lastSeenAt
                            )
                    )
            ).append(',');

            csv.append(
                    csvCell(
                            formatRssi(
                                    record.strongestRssi
                            )
                    )
            ).append(',');

            appendAntennaCsv(
                    csv,
                    chkAnt1.isChecked(),
                    selectedPower(
                            seekAnt1Power
                    ),
                    record.antennas.get(
                            "1"
                    )
            );

            appendAntennaCsv(
                    csv,
                    chkAnt2.isChecked(),
                    selectedPower(
                            seekAnt2Power
                    ),
                    record.antennas.get(
                            "2"
                    )
            );

            appendAntennaCsv(
                    csv,
                    chkAnt3.isChecked(),
                    selectedPower(
                            seekAnt3Power
                    ),
                    record.antennas.get(
                            "3"
                    )
            );

            appendAntennaCsv(
                    csv,
                    chkAnt4.isChecked(),
                    selectedPower(
                            seekAnt4Power
                    ),
                    record.antennas.get(
                            "4"
                    )
            );

            csv.setLength(
                    csv.length() - 1
            );

            csv.append('\n');
        }

        if (uniqueTags.isEmpty()) {
            csv.append(
                    csvCell(sessionId)
            ).append(',');

            csv.append(
                    csvCell(
                            formatDateTime(
                                    sessionStartedAt
                            )
                    )
            ).append(',');

            csv.append(
                    csvCell(
                            formatDateTime(
                                    sessionEndedAt
                            )
                    )
            ).append(',');

            csv.append(
                    currentElapsedMillis()
            ).append(',');

            csv.append(
                    currentActiveMillis()
            ).append(',');

            csv.append(
                    currentPausedMillis()
            );

            csv.append(
                    ",,0,,,,"
            );

            csv.append(
                    chkAnt1.isChecked()
            ).append(',');

            csv.append(
                    selectedPower(
                            seekAnt1Power
                    )
            ).append(
                    ",0,,"
            );

            csv.append(
                    chkAnt2.isChecked()
            ).append(',');

            csv.append(
                    selectedPower(
                            seekAnt2Power
                    )
            ).append(
                    ",0,,"
            );

            csv.append(
                    chkAnt3.isChecked()
            ).append(',');

            csv.append(
                    selectedPower(
                            seekAnt3Power
                    )
            ).append(
                    ",0,,"
            );

            csv.append(
                    chkAnt4.isChecked()
            ).append(',');

            csv.append(
                    selectedPower(
                            seekAnt4Power
                    )
            ).append(
                    ",0,\n"
            );
        }

        return csv.toString();
    }

    private void appendAntennaCsv(
            StringBuilder csv,
            boolean enabled,
            int power,
            AntennaRecord record
    ) {
        csv.append(
                enabled
        ).append(',');

        csv.append(
                power
        ).append(',');

        if (record == null) {
            csv.append(0);
        } else {
            csv.append(
                    record.count
            );
        }

        csv.append(',');

        if (record == null) {
            csv.append(
                    csvCell("")
            );
        } else {
            csv.append(
                    csvCell(
                            formatRssi(
                                    record.strongestRssi
                            )
                    )
            );
        }

        csv.append(',');
    }

    private String csvCell(
            String value
    ) {
        String safe;

        if (value == null) {
            safe = "";
        } else {
            safe =
                    value.replace(
                            "\"",
                            "\"\""
                    );
        }

        return "\""
                + safe
                + "\"";
    }

    private void shareCurrentSession() {
        if (sessionId == null
                || sessionState
                != SessionState.ENDED) {

            setStatus(
                    "End the session before sharing"
            );

            return;
        }

        try {
            Intent send =
                    new Intent(
                            Intent.ACTION_SEND
                    );

            send.setType(
                    "application/json"
            );

            send.putExtra(
                    Intent.EXTRA_SUBJECT,
                    "Tag Reaper Sentinel "
                            + exportFileName(false)
            );

            send.putExtra(
                    Intent.EXTRA_TEXT,
                    buildSessionJson()
                            .toString(2)
            );

            startActivity(
                    Intent.createChooser(
                            send,
                            "Share Sentinel session"
                    )
            );
        } catch (Exception exception) {
            setStatus(
                    "Share failed: "
                            + exception.getMessage()
            );
        }
    }

    @Override
    protected void onDestroy() {
        sessionClockHandler
                .removeCallbacks(
                        sessionClockRunnable
                );

        saveSessionNow();

        if (reader != null) {
            try {
                reader.stopInventory();
                reader.free();
            } catch (Throwable ignored) {
            }
        }

        super.onDestroy();
    }
                }

package com.herdmate.tagreapersentinel;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

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
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "tag_reaper_sentinel";
    private static final String PREF_ACTIVE = "active_session";
    private static final String PREF_HISTORY = "session_history";
    private static final String PREF_PROFILES = "reader_profiles";
    private static final String PREF_LAST_SITE = "last_site";
    private static final String PREF_LAST_OPERATOR = "last_operator";
    private static final String PREF_LAST_PROFILE = "last_profile";
    private static final String PREF_ANIMAL_CACHE = "animal_lookup_cache";
    private static final String PREF_ANIMAL_API_BASE = "animal_api_base";
    private static final String PREF_HERDMATE_SHEET_ID = "herdmate_sheet_id";
    private static final String DEFAULT_ANIMAL_API_BASE = "https://dave.sagewire.dev";

    private static final int REQ_CSV = 1001;
    private static final int REQ_JSON = 1002;
    private static final int REQ_LOCATION = 2001;
    private static final int MIN_DBM = 5;
    private static final int MAX_DBM = 30;
    private static final long GPS_TIMEOUT_MS = 60000L;
    private static final long ANIMAL_CACHE_TTL_MS = 24L * 60L * 60L * 1000L;

    private RFIDWithUHFA4 reader;
    private boolean readerConnected;

    private Button btnConnect, btnNewSession, btnStart, btnPause, btnEndSession, btnClear;
    private Button btnProfiles, btnHistory, btnExportCsv, btnExportJson, btnShare, btnSort, btnScaleDebug;
    private Button btnCaptureContext, btnRetryContext;
    private CheckBox chkAnt1, chkAnt2, chkAnt3, chkAnt4;
    private SeekBar seekAnt1Power, seekAnt2Power, seekAnt3Power, seekAnt4Power;
    private TextView txtAnt1Power, txtAnt2Power, txtAnt3Power, txtAnt4Power;
    private TextView txtHeader, txtTotalReads, txtUniqueTags, txtContextStatus;
    private EditText editSearch;
    private ListView listTags;
    private ArrayAdapter<String> adapter;

    private final List<String> displayLines = new ArrayList<>();
    private final List<TagRecord> displayTagRecords = new ArrayList<>();
    private final LinkedHashMap<String, TagRecord> tags = new LinkedHashMap<>();
    private final LinkedHashMap<String, AnimalLookupClient.AnimalRecord> animalCache = new LinkedHashMap<>();
    private final ExecutorService animalLookupExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private boolean animalSettingsPromptShown;

    private SessionState state = SessionState.NO_SESSION;
    private SortMode sortMode = SortMode.FIRST_SEEN;
    private String sessionId, sessionName = "", sessionSite = "", sessionOperator = "", sessionNotes = "", sessionProfile = "";
    private long startedAt, endedAt, pauseStartedAt, totalPausedMs, totalActiveMs, activeStartedAt;
    private int totalReads;
    private String status = "Not connected";
    private String pendingExport;

    private LocationManager locationManager;
    private boolean contextSyncRunning;
    private boolean gpsSearchRunning;
    private boolean gpsCaptured;
    private long gpsSearchStartedAt;
    private LocationListener gpsLocationListener;
    private LocationListener networkLocationListener;
    private final Handler gpsHandler = new Handler(Looper.getMainLooper());
    private final Runnable gpsTimeoutRunnable = () -> {
        if (!gpsSearchRunning || gpsCaptured) return;
        stopGpsSearch();
        setStatus("GPS timed out — tap Capture GPS to retry");
        updateContextBar();
        updateControls();
    };
    private double latitude, longitude, altitude;
    private float accuracy, speed, heading;
    private long gpsTimestamp;
    private String locationStatus = "NOT_CAPTURED", locationRaw = "", locationError = "";
    private int locationHttpCode;
    private String weatherStatus = "NOT_CAPTURED", weatherRaw = "", weatherError = "";
    private int weatherHttpCode;
    private String temperature = "", condition = "", wind = "", humidity = "", weatherFetchedAt = "";
    private boolean weatherCached;

    private final SimpleDateFormat dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final SimpleDateFormat fileTime = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() { updateHeader(); clock.postDelayed(this, 1000L); }
    };

    private enum SessionState { NO_SESSION, READY, SCANNING, PAUSED, ENDED }
    private enum SortMode { FIRST_SEEN, MOST_READS, EPC }

    private static class AntennaRecord {
        int count;
        double strongestRssi = -9999, latestRssi = -9999;
        long firstSeenAt, lastSeenAt;
    }

    private static class TagRecord {
        String epc;
        int count;
        long firstSeenAt, lastSeenAt;
        double strongestRssi = -9999;
        String animalLookupStatus = "NOT_REQUESTED";
        String animalLookupError = "";
        AnimalLookupClient.AnimalRecord animal;
        final LinkedHashMap<String, AntennaRecord> antennas = new LinkedHashMap<>();
    }

    private static class ReaderProfile {
        String name = "", description = "";
        boolean a1, a2, a3, a4;
        int p1 = 30, p2 = 30, p3 = 30, p4 = 30;
        long lastUsedAt;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        bindViews();
        setupPowerControls();
        setupListeners();
        adapter = new ArrayAdapter<>(this, R.layout.list_item_tag, displayLines);
        listTags.setAdapter(adapter);
        loadAnimalCache();
        restoreSession();
        refreshDisplay();
        updateContextBar();
        updateControls();
        clock.post(clockTick);
    }

    private void bindViews() {
        btnConnect = findViewById(R.id.btnConnect); btnNewSession = findViewById(R.id.btnNewSession);
        btnStart = findViewById(R.id.btnStart); btnPause = findViewById(R.id.btnPause);
        btnEndSession = findViewById(R.id.btnEndSession); btnClear = findViewById(R.id.btnClear);
        btnProfiles = findViewById(R.id.btnProfiles); btnHistory = findViewById(R.id.btnHistory);
        btnExportCsv = findViewById(R.id.btnExportCsv); btnExportJson = findViewById(R.id.btnExportJson);
        btnShare = findViewById(R.id.btnShare); btnSort = findViewById(R.id.btnSort);
        btnScaleDebug = findViewById(R.id.btnScaleDebug);
        btnCaptureContext = findViewById(R.id.btnCaptureContext); btnRetryContext = findViewById(R.id.btnRetryContext);
        chkAnt1 = findViewById(R.id.chkAnt1); chkAnt2 = findViewById(R.id.chkAnt2);
        chkAnt3 = findViewById(R.id.chkAnt3); chkAnt4 = findViewById(R.id.chkAnt4);
        seekAnt1Power = findViewById(R.id.seekAnt1Power); seekAnt2Power = findViewById(R.id.seekAnt2Power);
        seekAnt3Power = findViewById(R.id.seekAnt3Power); seekAnt4Power = findViewById(R.id.seekAnt4Power);
        txtAnt1Power = findViewById(R.id.txtAnt1Power); txtAnt2Power = findViewById(R.id.txtAnt2Power);
        txtAnt3Power = findViewById(R.id.txtAnt3Power); txtAnt4Power = findViewById(R.id.txtAnt4Power);
        txtHeader = findViewById(R.id.txtHeader); txtTotalReads = findViewById(R.id.txtTotalReads);
        txtUniqueTags = findViewById(R.id.txtUniqueTags); txtContextStatus = findViewById(R.id.txtContextStatus);
        editSearch = findViewById(R.id.editSearch); listTags = findViewById(R.id.listTags);
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> connectReader());
        btnNewSession.setOnClickListener(v -> showNewSessionDialog());
        btnStart.setOnClickListener(v -> startOrResume());
        btnPause.setOnClickListener(v -> pauseScanning());
        btnEndSession.setOnClickListener(v -> endSession());
        btnClear.setOnClickListener(v -> clearSession());
        btnProfiles.setOnClickListener(v -> showProfilesDialog());
        btnHistory.setOnClickListener(v -> showHistory());
        btnExportCsv.setOnClickListener(v -> beginExport(true));
        btnExportJson.setOnClickListener(v -> beginExport(false));
        btnShare.setOnClickListener(v -> shareSession());
        btnScaleDebug.setOnClickListener(v -> startActivity(new Intent(this, ScaleDebugActivity.class)));
        btnSort.setOnClickListener(v -> cycleSort());
        btnCaptureContext.setOnClickListener(v -> captureContext());
        btnRetryContext.setOnClickListener(v -> syncContext());
        listTags.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < displayTagRecords.size()) {
                showAnimalCard(displayTagRecords.get(position));
            }
        });
        editSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { refreshDisplay(); }
            public void afterTextChanged(Editable e) {}
        });
    }

    private void setupPowerControls() {
        setupPower(seekAnt1Power, txtAnt1Power, 1); setupPower(seekAnt2Power, txtAnt2Power, 2);
        setupPower(seekAnt3Power, txtAnt3Power, 3); setupPower(seekAnt4Power, txtAnt4Power, 4);
    }

    private void setupPower(SeekBar bar, TextView label, int ant) {
        bar.setMax(MAX_DBM - MIN_DBM); bar.setProgress(MAX_DBM - MIN_DBM);
        label.setText(String.format(Locale.US, "ANT %d • %d dBm", ant, MAX_DBM));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                label.setText(String.format(Locale.US, "ANT %d • %d dBm", ant, MIN_DBM + p));
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) { saveSession(); }
        });
    }

    private int power(SeekBar bar) { return MIN_DBM + bar.getProgress(); }
    private void setPower(SeekBar bar, int value) { bar.setProgress(Math.max(0, Math.min(MAX_DBM - MIN_DBM, value - MIN_DBM))); }

    private void showNewSessionDialog() {
        if (state == SessionState.SCANNING) { setStatus("Pause or end the current session first"); return; }
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density); form.setPadding(pad, 8, pad, 0);
        EditText name = field("Session name", "");
        EditText site = field("Farm or site", prefs.getString(PREF_LAST_SITE, ""));
        EditText operator = field("Operator", prefs.getString(PREF_LAST_OPERATOR, ""));
        EditText notes = field("Notes", ""); notes.setMinLines(2); notes.setMaxLines(4);
        notes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        Spinner spinner = new Spinner(this);
        List<ReaderProfile> profiles = loadProfiles(); List<String> names = new ArrayList<>();
        names.add("Current antenna settings"); for (ReaderProfile p : profiles) names.add(p.name);
        ArrayAdapter<String> pa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(pa);
        String last = prefs.getString(PREF_LAST_PROFILE, "");
        for (int i = 0; i < names.size(); i++) if (names.get(i).equals(last)) spinner.setSelection(i);
        TextView pl = new TextView(this); pl.setText("Reader profile"); pl.setPadding(0, 12, 0, 4);
        form.addView(name); form.addView(site); form.addView(operator); form.addView(notes); form.addView(pl); form.addView(spinner);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("New Field Session").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Create", null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim(); if (n.isEmpty()) { name.setError("Session name is required"); return; }
            String selected = spinner.getSelectedItem().toString();
            if (!selected.equals("Current antenna settings")) {
                ReaderProfile p = findProfile(selected); if (p != null) { applyProfile(p); p.lastUsedAt = System.currentTimeMillis(); saveProfile(p); }
            }
            createSession(n, site.getText().toString().trim(), operator.getText().toString().trim(),
                    notes.getText().toString().trim(), selected.equals("Current antenna settings") ? "" : selected);
            prefs.edit().putString(PREF_LAST_SITE, sessionSite).putString(PREF_LAST_OPERATOR, sessionOperator)
                    .putString(PREF_LAST_PROFILE, selected).apply();
            dialog.dismiss();
        })); dialog.show();
    }

    private EditText field(String hint, String value) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true); e.setText(value); return e; }

    private void createSession(String name, String site, String operator, String notes, String profile) {
        tags.clear(); totalReads = 0; sessionId = UUID.randomUUID().toString(); sessionName = name;
        sessionSite = site; sessionOperator = operator; sessionNotes = notes; sessionProfile = profile;
        startedAt = System.currentTimeMillis(); endedAt = pauseStartedAt = totalPausedMs = totalActiveMs = activeStartedAt = 0L;
        resetContext(); animalSettingsPromptShown = false; state = SessionState.READY; editSearch.setText(""); saveSession(); refreshDisplay();
        setStatus("New session ready"); updateControls(); captureContext();
    }

    private void connectReader() {
        setStatus("Connecting"); btnConnect.setEnabled(false);
        new Thread(() -> {
            boolean ok; String err = null;
            try { reader = RFIDWithUHFA4.getInstance(); ok = reader.init(getApplicationContext()); }
            catch (Throwable t) { ok = false; err = t.getClass().getSimpleName() + ": " + t.getMessage(); }
            boolean result = ok; String error = err;
            runOnUiThread(() -> { readerConnected = result; if (result) { reader.setInventoryCallback(callback); setStatus("Reader connected"); }
                else setStatus(error == null ? "Connection failed" : "Connection failed: " + error); updateControls(); });
        }).start();
    }

    private void startOrResume() {
        if (!readerConnected || reader == null) { setStatus("Connect the reader first"); return; }
        if (state != SessionState.READY && state != SessionState.PAUSED) { setStatus("Start a new session first"); return; }
        if (!chkAnt1.isChecked() && !chkAnt2.isChecked() && !chkAnt3.isChecked() && !chkAnt4.isChecked()) { setStatus("Select at least one antenna"); return; }
        applyAntennaSelection();
        new Thread(() -> {
            String pe = applyPower(); if (pe != null) { runOnUiThread(() -> setStatus(pe)); return; }
            boolean ok; String err = null; try { ok = reader.startInventoryTag(); } catch (Throwable t) { ok = false; err = t.getMessage(); }
            boolean result = ok; String error = err;
            runOnUiThread(() -> { if (result) { long now = System.currentTimeMillis(); if (pauseStartedAt > 0) { totalPausedMs += now - pauseStartedAt; pauseStartedAt = 0; }
                    state = SessionState.SCANNING; activeStartedAt = now; saveSession(); setStatus("Scanning"); if (!gpsCaptured) captureContext(); }
                else setStatus("Failed to start" + (error == null ? "" : ": " + error)); updateControls(); });
        }).start();
    }

    private void pauseScanning() {
        if (state != SessionState.SCANNING) return;
        new Thread(() -> { try { if (reader != null) reader.stopInventory(); } catch (Throwable ignored) {}
            runOnUiThread(() -> { long now = System.currentTimeMillis(); if (activeStartedAt > 0) { totalActiveMs += now - activeStartedAt; activeStartedAt = 0; }
                state = SessionState.PAUSED; pauseStartedAt = now; saveSession(); refreshDisplay(); setStatus("Session paused"); updateControls(); }); }).start();
    }

    private void endSession() {
        if (state == SessionState.NO_SESSION || state == SessionState.ENDED) return;
        new Thread(() -> { if (reader != null && state == SessionState.SCANNING) try { reader.stopInventory(); } catch (Throwable ignored) {}
            runOnUiThread(() -> { long now = System.currentTimeMillis(); if (activeStartedAt > 0) { totalActiveMs += now - activeStartedAt; activeStartedAt = 0; }
                if (pauseStartedAt > 0) { totalPausedMs += now - pauseStartedAt; pauseStartedAt = 0; }
                endedAt = now; state = SessionState.ENDED; refreshDisplay(); saveSession(); archiveSession(); refreshDisplay();
                setStatus("Session ended and saved"); updateControls(); }); }).start();
    }

    private void clearSession() {
        if (state == SessionState.SCANNING) { setStatus("Pause before clearing session data"); return; }
        tags.clear(); totalReads = 0; sessionId = null; sessionName = sessionSite = sessionOperator = sessionNotes = sessionProfile = "";
        startedAt = endedAt = pauseStartedAt = totalPausedMs = totalActiveMs = activeStartedAt = 0L; resetContext(); state = SessionState.NO_SESSION;
        editSearch.setText(""); prefs.edit().remove(PREF_ACTIVE).apply(); refreshDisplay(); updateContextBar();
        setStatus(readerConnected ? "Reader connected; no session" : "Not connected"); updateControls();
    }

    private void applyAntennaSelection() {
        List<AntennaState> s = new ArrayList<>(); s.add(new AntennaState(AntennaEnum.ANT1, chkAnt1.isChecked()));
        s.add(new AntennaState(AntennaEnum.ANT2, chkAnt2.isChecked())); s.add(new AntennaState(AntennaEnum.ANT3, chkAnt3.isChecked()));
        s.add(new AntennaState(AntennaEnum.ANT4, chkAnt4.isChecked())); reader.setANT(s);
    }

    private String applyPower() {
        try {
            AntennaEnum[] a = {AntennaEnum.ANT1, AntennaEnum.ANT2, AntennaEnum.ANT3, AntennaEnum.ANT4};
            SeekBar[] b = {seekAnt1Power, seekAnt2Power, seekAnt3Power, seekAnt4Power};
            for (int i = 0; i < 4; i++) {
                int requested = power(b[i]); if (!reader.setAntennaPower(a[i], requested)) return "Power rejected on ANT " + (i + 1);
                int actual = reader.getAntennaPower(a[i]); if (actual != requested) return "ANT " + (i + 1) + " power mismatch: " + actual + " dBm";
            }
            return null;
        } catch (Throwable t) { return "Power setup failed: " + t.getMessage(); }
    }

    private final IUHFInventoryCallback callback = new IUHFInventoryCallback() {
        @Override public void callback(UHFTAGInfo info) {
            if (info == null || state != SessionState.SCANNING) return;
            String epc = info.getEPC(); if (epc == null || epc.trim().isEmpty()) return;
            epc = epc.trim();
            String ant = String.valueOf(info.getAnt());
            double rssi = parseDouble(info.getRssi());
            long now = System.currentTimeMillis();
            boolean lookupNeeded = false;

            synchronized (tags) {
                TagRecord t = tags.get(epc);
                if (t == null) {
                    t = new TagRecord();
                    t.epc = epc;
                    t.firstSeenAt = now;
                    tags.put(epc, t);

                    AnimalLookupClient.AnimalRecord cached = getCachedAnimal(epc);
                    if (cached != null) {
                        t.animal = cached;
                        if (isAnimalCacheFresh(cached)) {
                            t.animalLookupStatus = "CACHED";
                        } else {
                            t.animalLookupStatus = "STALE_REFRESH";
                            lookupNeeded = true;
                        }
                    } else {
                        t.animalLookupStatus = "LOOKUP_PENDING";
                        lookupNeeded = true;
                    }
                }

                t.count++;
                t.lastSeenAt = now;
                t.strongestRssi = Math.max(t.strongestRssi, rssi);

                AntennaRecord ar = t.antennas.get(ant);
                if (ar == null) {
                    ar = new AntennaRecord();
                    ar.firstSeenAt = now;
                    t.antennas.put(ant, ar);
                }
                ar.count++;
                ar.lastSeenAt = now;
                ar.latestRssi = rssi;
                ar.strongestRssi = Math.max(ar.strongestRssi, rssi);
                totalReads++;
            }

            boolean shouldLookup = lookupNeeded;
            String lookupEpc = epc;
            runOnUiThread(() -> {
                if (shouldLookup) startAnimalLookup(lookupEpc, false);
                refreshDisplay();
                saveSession();
            });
        }
    };

    private double parseDouble(String value) { try { return Double.parseDouble(value); } catch (Exception e) { return -9999; } }

    private int antennaCount(TagRecord tag, String antennaNumber) {
        int total = 0;
        for (Map.Entry<String, AntennaRecord> entry : tag.antennas.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toUpperCase(Locale.US);
            if (key.equals(antennaNumber)
                    || key.equals("ANT" + antennaNumber)
                    || key.equals("ANT_" + antennaNumber)
                    || key.endsWith(antennaNumber)) {
                total += entry.getValue().count;
            }
        }
        return total;
    }

    private void startAnimalLookup(String epc, boolean force) {
        if (epc == null || epc.trim().isEmpty()) return;
        String cleanEpc = epc.trim();
        String sheetId = prefs.getString(PREF_HERDMATE_SHEET_ID, "").trim();
        String apiBase = prefs.getString(PREF_ANIMAL_API_BASE, DEFAULT_ANIMAL_API_BASE).trim();

        TagRecord tag;
        synchronized (tags) {
            tag = tags.get(cleanEpc);
            if (tag == null) return;
            if ("LOOKING_UP".equals(tag.animalLookupStatus)) return;
            if (!force && tag.animal != null && isAnimalCacheFresh(tag.animal)) return;

            if (sheetId.isEmpty()) {
                tag.animalLookupStatus = "SETUP_REQUIRED";
                tag.animalLookupError = "Google Sheet ID is not configured";
                refreshDisplay();
                saveSession();
                if (!animalSettingsPromptShown) {
                    animalSettingsPromptShown = true;
                    showAnimalLookupSettings();
                }
                return;
            }

            tag.animalLookupStatus = "LOOKING_UP";
            tag.animalLookupError = "";
        }

        refreshDisplay();

        animalLookupExecutor.submit(() -> {
            AnimalLookupClient.Result result = AnimalLookupClient.lookup(
                    apiBase,
                    sheetId,
                    cleanEpc,
                    sessionSite.isEmpty() ? "Tag Reaper Sentinel" : sessionSite
            );

            runOnUiThread(() -> {
                synchronized (tags) {
                    TagRecord current = tags.get(cleanEpc);
                    if (current == null) return;

                    if (result.success && result.found && result.animal != null) {
                        current.animal = result.animal;
                        current.animalLookupStatus = "FOUND";
                        current.animalLookupError = "";
                        animalCache.put(animalCacheKey(cleanEpc), result.animal);
                        saveAnimalCache();
                    } else if (result.success) {
                        current.animal = null;
                        current.animalLookupStatus = "NOT_FOUND";
                        current.animalLookupError = "No HerdMate animal matched this EPC";
                    } else {
                        current.animalLookupStatus = "ERROR";
                        current.animalLookupError = result.error == null ? "Lookup failed" : result.error;
                    }
                }
                refreshDisplay();
                saveSession();
            });
        });
    }

    private void forceAnimalLookup(String epc) {
        synchronized (tags) {
            TagRecord tag = tags.get(epc);
            if (tag != null) {
                tag.animalLookupStatus = "NOT_REQUESTED";
                tag.animalLookupError = "";
            }
        }
        startAnimalLookup(epc, true);
    }

    private void showAnimalLookupSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, 8, pad, 0);

        EditText api = field(
                "Animal API URL",
                prefs.getString(PREF_ANIMAL_API_BASE, DEFAULT_ANIMAL_API_BASE)
        );
        EditText sheet = field(
                "Google Sheet ID or full Sheet URL",
                prefs.getString(PREF_HERDMATE_SHEET_ID, "")
        );
        TextView help = new TextView(this);
        help.setText("Sentinel stores this on the reader. The Sheet ID is the text between /d/ and /edit in a Google Sheet URL.");
        help.setPadding(0, 10, 0, 0);

        form.addView(api);
        form.addView(sheet);
        form.addView(help);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("HerdMate Animal Lookup")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear Cache", null)
                .setPositiveButton("Save", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String cleanApi = api.getText().toString().trim();
                while (cleanApi.endsWith("/")) {
                    cleanApi = cleanApi.substring(0, cleanApi.length() - 1);
                }
                String cleanSheet = normalizeSheetId(sheet.getText().toString());

                if (cleanApi.isEmpty()) {
                    api.setError("API URL is required");
                    return;
                }
                if (cleanSheet.isEmpty()) {
                    sheet.setError("Google Sheet ID is required");
                    return;
                }

                String previousSheet = prefs.getString(PREF_HERDMATE_SHEET_ID, "");
                prefs.edit()
                        .putString(PREF_ANIMAL_API_BASE, cleanApi)
                        .putString(PREF_HERDMATE_SHEET_ID, cleanSheet)
                        .apply();

                if (!previousSheet.equals(cleanSheet)) {
                    clearAnimalCache();
                    synchronized (tags) {
                        for (TagRecord tag : tags.values()) {
                            tag.animal = null;
                            tag.animalLookupStatus = "NOT_REQUESTED";
                            tag.animalLookupError = "";
                        }
                    }
                }

                animalSettingsPromptShown = true;
                setStatus("HerdMate animal lookup configured");
                dialog.dismiss();
                retryUnresolvedAnimalLookups();
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                clearAnimalCache();
                synchronized (tags) {
                    for (TagRecord tag : tags.values()) {
                        tag.animal = null;
                        tag.animalLookupStatus = "NOT_REQUESTED";
                        tag.animalLookupError = "";
                    }
                }
                refreshDisplay();
                saveSession();
                setStatus("Animal cache cleared");
            });
        });

        dialog.show();
    }

    private String normalizeSheetId(String value) {
        if (value == null) return "";
        String clean = value.trim();
        int marker = clean.indexOf("/d/");
        if (marker >= 0) {
            int start = marker + 3;
            int end = clean.indexOf('/', start);
            if (end < 0) end = clean.length();
            clean = clean.substring(start, end);
        }
        return clean.trim();
    }

    private void retryUnresolvedAnimalLookups() {
        List<String> epcs = new ArrayList<>();
        synchronized (tags) {
            for (TagRecord tag : tags.values()) {
                if (tag.animal == null
                        || "ERROR".equals(tag.animalLookupStatus)
                        || "NOT_FOUND".equals(tag.animalLookupStatus)
                        || "SETUP_REQUIRED".equals(tag.animalLookupStatus)) {
                    epcs.add(tag.epc);
                }
            }
        }
        for (String epc : epcs) {
            startAnimalLookup(epc, true);
        }
    }

    private String animalCacheKey(String epc) {
        return prefs.getString(PREF_HERDMATE_SHEET_ID, "").trim() + "|" + epc.trim();
    }

    private AnimalLookupClient.AnimalRecord getCachedAnimal(String epc) {
        return animalCache.get(animalCacheKey(epc));
    }

    private boolean isAnimalCacheFresh(AnimalLookupClient.AnimalRecord animal) {
        if (animal == null || animal.cachedAt <= 0L) return false;
        return System.currentTimeMillis() - animal.cachedAt <= ANIMAL_CACHE_TTL_MS;
    }

    private void loadAnimalCache() {
        animalCache.clear();
        try {
            JSONObject root = new JSONObject(prefs.getString(PREF_ANIMAL_CACHE, "{}"));
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject value = root.optJSONObject(key);
                if (value != null) {
                    animalCache.put(key, AnimalLookupClient.AnimalRecord.fromJson(value));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveAnimalCache() {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, AnimalLookupClient.AnimalRecord> entry : animalCache.entrySet()) {
                root.put(entry.getKey(), entry.getValue().toJson());
            }
            prefs.edit().putString(PREF_ANIMAL_CACHE, root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void clearAnimalCache() {
        animalCache.clear();
        prefs.edit().remove(PREF_ANIMAL_CACHE).apply();
    }

    private void showAnimalCard(TagRecord tag) {
        if (tag == null) return;

        String title;
        String message;
        if (tag.animal != null) {
            title = tag.animal.displayTitle();
            message = tag.animal.detailText();
        } else {
            title = "Unknown animal";
            String error = tag.animalLookupError == null ? "" : tag.animalLookupError.trim();
            message = "EPC: " + tag.epc
                    + "\nLookup status: " + tag.animalLookupStatus
                    + (error.isEmpty() ? "" : "\nReason: " + error);
        }

        message += String.format(
                Locale.US,
                "\n\nSession evidence\nReads: %d\nStrongest RSSI: %.1f\nA1:%d  A2:%d  A3:%d  A4:%d",
                tag.count,
                tag.strongestRssi,
                antennaCount(tag, "1"),
                antennaCount(tag, "2"),
                antennaCount(tag, "3"),
                antennaCount(tag, "4")
        );

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Refresh Record", (dialog, which) -> forceAnimalLookup(tag.epc))
                .setNeutralButton("Lookup Settings", (dialog, which) -> showAnimalLookupSettings())
                .setNegativeButton("Close", null)
                .show();
    }

    private String animalDisplayStatus(TagRecord tag) {
        if (tag.animal != null) {
            String suffix = "CACHED".equals(tag.animalLookupStatus)
                    ? "  [LOCAL CACHE]"
                    : "STALE_REFRESH".equals(tag.animalLookupStatus)
                    ? "  [REFRESHING]"
                    : "";
            return tag.animal.displayTitle() + suffix;
        }

        if ("LOOKING_UP".equals(tag.animalLookupStatus)
                || "LOOKUP_PENDING".equals(tag.animalLookupStatus)) {
            return "LOOKING UP ANIMAL…";
        }
        if ("SETUP_REQUIRED".equals(tag.animalLookupStatus)) {
            return "HERDMATE LOOKUP NEEDS SETUP";
        }
        if ("NOT_FOUND".equals(tag.animalLookupStatus)) {
            return "UNKNOWN ANIMAL — NO SHEET MATCH";
        }
        if ("ERROR".equals(tag.animalLookupStatus)) {
            return "ANIMAL LOOKUP ERROR";
        }
        return "ANIMAL NOT LOOKED UP";
    }

    private boolean matchesSearch(TagRecord tag, String query) {
        if (query.isEmpty()) return true;
        if (tag.epc != null && tag.epc.toLowerCase(Locale.US).contains(query)) return true;
        if (tag.animal == null) return false;
        return tag.animal.displayTitle().toLowerCase(Locale.US).contains(query)
                || tag.animal.detailText().toLowerCase(Locale.US).contains(query);
    }

    private void captureContext() {
        if (state == SessionState.NO_SESSION) {
            setStatus("Create a session first");
            return;
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQ_LOCATION
            );
            return;
        }

        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!gpsEnabled && !networkEnabled) {
            new AlertDialog.Builder(this)
                    .setTitle("Location is off")
                    .setMessage("Turn on device location, then tap Capture GPS again.")
                    .setPositiveButton(
                            "Open Settings",
                            (dialog, which) -> startActivity(
                                    new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            )
                    )
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        stopGpsSearch();
        gpsSearchRunning = true;
        gpsSearchStartedAt = System.currentTimeMillis();
        txtContextStatus.setText("CONTEXT | GPS searching…");
        setStatus("GPS searching");
        btnCaptureContext.setEnabled(false);
        btnRetryContext.setEnabled(false);

        gpsLocationListener = new LocationListener() {
            @Override public void onLocationChanged(@NonNull Location location) { considerLocation(location); }
            @Override public void onProviderEnabled(@NonNull String provider) {}
            @Override public void onProviderDisabled(@NonNull String provider) {}
        };

        networkLocationListener = new LocationListener() {
            @Override public void onLocationChanged(@NonNull Location location) { considerLocation(location); }
            @Override public void onProviderEnabled(@NonNull String provider) {}
            @Override public void onProviderDisabled(@NonNull String provider) {}
        };

        try {
            Location bestLastKnown = null;

            if (gpsEnabled) {
                bestLastKnown = betterLocation(
                        bestLastKnown,
                        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                );
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L,
                        0f,
                        gpsLocationListener,
                        Looper.getMainLooper()
                );
            }

            if (networkEnabled) {
                bestLastKnown = betterLocation(
                        bestLastKnown,
                        locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                );
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000L,
                        0f,
                        networkLocationListener,
                        Looper.getMainLooper()
                );
            }

            gpsHandler.postDelayed(gpsTimeoutRunnable, GPS_TIMEOUT_MS);

            if (isFreshUsableLocation(bestLastKnown)) {
                acceptLocation(bestLastKnown);
            }
        } catch (SecurityException exception) {
            stopGpsSearch();
            setStatus("Location permission denied");
            showLocationPermissionHelp();
            updateContextBar();
            updateControls();
        } catch (Exception exception) {
            stopGpsSearch();
            setStatus("GPS error: " + exception.getMessage());
            updateContextBar();
            updateControls();
        }
    }

    private void considerLocation(Location location) {
        if (!gpsSearchRunning || location == null) return;
        if (location.getLatitude() == 0.0 && location.getLongitude() == 0.0) return;

        float locationAccuracy = location.hasAccuracy() ? location.getAccuracy() : 9999f;
        long searchingFor = System.currentTimeMillis() - gpsSearchStartedAt;

        if (locationAccuracy <= 200f || searchingFor >= 15000L) {
            acceptLocation(location);
        }
    }

    private boolean isFreshUsableLocation(Location location) {
        if (location == null) return false;
        long age = System.currentTimeMillis() - location.getTime();
        return age >= 0L
                && age <= 120000L
                && !(location.getLatitude() == 0.0 && location.getLongitude() == 0.0);
    }

    private Location betterLocation(Location current, Location candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;

        long candidateAge = System.currentTimeMillis() - candidate.getTime();
        long currentAge = System.currentTimeMillis() - current.getTime();

        if (candidateAge < currentAge - 30000L) return candidate;
        if (candidate.hasAccuracy()
                && (!current.hasAccuracy() || candidate.getAccuracy() < current.getAccuracy())) {
            return candidate;
        }
        return current;
    }

    private void acceptLocation(Location location) {
        if (location == null) return;

        stopGpsSearch();
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        altitude = location.hasAltitude() ? location.getAltitude() : 0.0;
        accuracy = location.hasAccuracy() ? location.getAccuracy() : 0f;
        speed = location.hasSpeed() ? location.getSpeed() : 0f;
        heading = location.hasBearing() ? location.getBearing() : 0f;
        gpsTimestamp = location.getTime() > 0L ? location.getTime() : System.currentTimeMillis();
        gpsCaptured = true;
        locationStatus = "PENDING";
        weatherStatus = "PENDING";
        saveSession();
        setStatus("GPS captured");
        updateContextBar();
        updateControls();
        syncContext();
    }

    private void stopGpsSearch() {
        gpsSearchRunning = false;
        gpsHandler.removeCallbacks(gpsTimeoutRunnable);

        try {
            if (gpsLocationListener != null) locationManager.removeUpdates(gpsLocationListener);
        } catch (Exception ignored) {}

        try {
            if (networkLocationListener != null) locationManager.removeUpdates(networkLocationListener);
        } catch (Exception ignored) {}

        gpsLocationListener = null;
        networkLocationListener = null;
    }

    private void syncContext() {
        if (!gpsCaptured || contextSyncRunning) return;
        contextSyncRunning = true; updateContextBar(); updateControls();
        new Thread(() -> {
            SageWireContextClient.Result r = SageWireContextClient.enrich(latitude, longitude, accuracy, altitude, speed, heading,
                    gpsTimestamp, sessionId == null ? "" : sessionId, sessionSite);
            runOnUiThread(() -> {
                contextSyncRunning = false; locationStatus = r.locationStatus; locationHttpCode = r.locationHttpCode;
                locationRaw = r.locationRaw; locationError = r.locationError; weatherStatus = r.weatherStatus;
                weatherHttpCode = r.weatherHttpCode; weatherRaw = r.weatherRaw; weatherError = r.weatherError;
                temperature = r.temperature; condition = r.condition; wind = r.wind; humidity = r.humidity;
                weatherCached = r.weatherCached; weatherFetchedAt = r.weatherFetchedAt; saveSession(); updateContextBar(); updateControls();
            });
        }).start();
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                captureContext();
            } else {
                setStatus("Location permission denied");
                showLocationPermissionHelp();
                updateContextBar();
                updateControls();
            }
        }
    }

    private void showLocationPermissionHelp() {
        boolean canAskAgain =
                ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                );

        AlertDialog.Builder builder =
                new AlertDialog.Builder(this)
                        .setTitle("Location permission needed")
                        .setMessage(
                                canAskAgain
                                        ? "Tag Reaper needs location permission to capture GPS and request SageWire weather."
                                        : "Android has blocked location permission for Tag Reaper. Open App Settings, then allow Location."
                        )
                        .setNegativeButton("Not now", null);

        if (canAskAgain) {
            builder.setPositiveButton(
                    "Ask again",
                    (dialog, which) -> ActivityCompat.requestPermissions(
                            this,
                            new String[]{
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                            },
                            REQ_LOCATION
                    )
            );
        } else {
            builder.setPositiveButton(
                    "Open App Settings",
                    (dialog, which) -> {
                        Intent intent =
                                new Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                );

                        intent.setData(
                                Uri.parse(
                                        "package:" + getPackageName()
                                )
                        );

                        startActivity(intent);
                    }
            );
        }

        builder.show();
    }

    private void updateContextBar() {
        if (txtContextStatus == null) return;
        if (state == SessionState.NO_SESSION) txtContextStatus.setText("CONTEXT | Create a session to capture GPS");
        else if (gpsSearchRunning) txtContextStatus.setText("CONTEXT | GPS searching…");
        else if (!gpsCaptured) txtContextStatus.setText("CONTEXT | GPS not captured");
        else if (contextSyncRunning) txtContextStatus.setText(String.format(Locale.US, "CONTEXT | %.5f, %.5f | Syncing SageWire…", latitude, longitude));
        else if ("COMPLETE".equals(locationStatus) && "COMPLETE".equals(weatherStatus)) {
            String wx = (temperature + " " + condition).trim();
            txtContextStatus.setText(String.format(Locale.US, "CONTEXT | %.5f, %.5f ±%.0fm | %s", latitude, longitude, accuracy, wx));
        } else txtContextStatus.setText(String.format(Locale.US, "CONTEXT | %.5f, %.5f ±%.0fm | Enrichment pending", latitude, longitude, accuracy));
    }

    private void resetContext() {
        stopGpsSearch(); gpsCaptured = false; contextSyncRunning = false; latitude = longitude = altitude = 0; accuracy = speed = heading = 0; gpsTimestamp = 0;
        locationStatus = weatherStatus = "NOT_CAPTURED"; locationRaw = locationError = weatherRaw = weatherError = "";
        locationHttpCode = weatherHttpCode = 0; temperature = condition = wind = humidity = weatherFetchedAt = ""; weatherCached = false;
    }

    private void refreshDisplay() {
        List<TagRecord> list;
        synchronized (tags) {
            list = new ArrayList<>(tags.values());
        }

        if (sortMode == SortMode.MOST_READS) {
            Collections.sort(list, (a, b) -> Integer.compare(b.count, a.count));
        } else if (sortMode == SortMode.EPC) {
            Collections.sort(list, Comparator.comparing(a -> a.epc));
        } else {
            Collections.sort(list, Comparator.comparingLong(a -> a.firstSeenAt));
        }

        String query = editSearch == null
                ? ""
                : editSearch.getText().toString().trim().toLowerCase(Locale.US);

        displayLines.clear();
        displayTagRecords.clear();

        for (TagRecord tag : list) {
            if (!matchesSearch(tag, query)) continue;

            int a1 = antennaCount(tag, "1");
            int a2 = antennaCount(tag, "2");
            int a3 = antennaCount(tag, "3");
            int a4 = antennaCount(tag, "4");

            displayLines.add(String.format(
                    Locale.US,
                    "%s\n%s   Reads %d   RSSI %.1f   A1:%d A2:%d A3:%d A4:%d   %s → %s",
                    animalDisplayStatus(tag),
                    tag.epc,
                    tag.count,
                    tag.strongestRssi,
                    a1,
                    a2,
                    a3,
                    a4,
                    dateTime.format(new Date(tag.firstSeenAt)),
                    dateTime.format(new Date(tag.lastSeenAt))
            ));
            displayTagRecords.add(tag);
        }

        if (adapter != null) adapter.notifyDataSetChanged();
        if (txtTotalReads != null) txtTotalReads.setText("Reads: " + totalReads);
        if (txtUniqueTags != null) txtUniqueTags.setText("Tags: " + tags.size());
        updateHeader();
    }

    private void cycleSort() {
        if (sortMode == SortMode.FIRST_SEEN) { sortMode = SortMode.MOST_READS; btnSort.setText("Sort: Reads"); }
        else if (sortMode == SortMode.MOST_READS) { sortMode = SortMode.EPC; btnSort.setText("Sort: EPC"); }
        else { sortMode = SortMode.FIRST_SEEN; btnSort.setText("Sort: First"); }
        refreshDisplay();
    }

    private void updateHeader() {
        String session = state == SessionState.NO_SESSION ? "No session" : sessionName + " | " + state.name();
        long active = totalActiveMs + (state == SessionState.SCANNING && activeStartedAt > 0 ? System.currentTimeMillis() - activeStartedAt : 0);
        txtHeader.setText(String.format(Locale.US, "TAG REAPER SENTINEL | %s | %s | %s | Reads %d | Tags %d",
                status, session, formatDuration(active), totalReads, tags.size()));
    }

    private String formatDuration(long ms) { long s = Math.max(0, ms / 1000), h = s / 3600, m = (s % 3600) / 60; return String.format(Locale.US, "%02d:%02d:%02d", h, m, s % 60); }
    private void setStatus(String value) { status = value; updateHeader(); }

    private void updateControls() {
        boolean open = state == SessionState.READY || state == SessionState.SCANNING || state == SessionState.PAUSED;
        boolean configUnlocked = state != SessionState.SCANNING && state != SessionState.PAUSED;
        boolean ended = state == SessionState.ENDED;
        btnConnect.setEnabled(!readerConnected); btnNewSession.setEnabled(state != SessionState.SCANNING);
        btnStart.setEnabled(readerConnected && (state == SessionState.READY || state == SessionState.PAUSED));
        btnStart.setText(state == SessionState.PAUSED ? "Resume Scan" : "Start Scan");
        btnPause.setEnabled(state == SessionState.SCANNING); btnEndSession.setEnabled(open); btnClear.setEnabled(state != SessionState.SCANNING);
        btnProfiles.setEnabled(configUnlocked); btnExportCsv.setEnabled(ended); btnExportJson.setEnabled(ended); btnShare.setEnabled(ended);
        chkAnt1.setEnabled(configUnlocked); chkAnt2.setEnabled(configUnlocked); chkAnt3.setEnabled(configUnlocked); chkAnt4.setEnabled(configUnlocked);
        seekAnt1Power.setEnabled(configUnlocked); seekAnt2Power.setEnabled(configUnlocked); seekAnt3Power.setEnabled(configUnlocked); seekAnt4Power.setEnabled(configUnlocked);
        btnCaptureContext.setEnabled(state != SessionState.NO_SESSION && !contextSyncRunning && !gpsSearchRunning);
        btnRetryContext.setEnabled(gpsCaptured && !contextSyncRunning && !gpsSearchRunning && (!("COMPLETE".equals(locationStatus)) || !("COMPLETE".equals(weatherStatus))));
        updateContextBar(); updateHeader();
    }

    private JSONObject buildSessionJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("schemaVersion", "0.7"); root.put("sessionId", sessionId == null ? "" : sessionId);
            root.put("sessionName", sessionName); root.put("site", sessionSite); root.put("operator", sessionOperator);
            root.put("notes", sessionNotes); root.put("readerProfile", sessionProfile); root.put("state", state.name());
            root.put("startedAt", startedAt); root.put("endedAt", endedAt); root.put("totalReads", totalReads); root.put("uniqueTags", tags.size());
            root.put("activeMillis", totalActiveMs + (state == SessionState.SCANNING && activeStartedAt > 0 ? System.currentTimeMillis() - activeStartedAt : 0));
            root.put("pausedMillis", totalPausedMs);
            JSONObject cfg = new JSONObject();
            cfg.put("ant1Enabled", chkAnt1.isChecked()); cfg.put("ant2Enabled", chkAnt2.isChecked()); cfg.put("ant3Enabled", chkAnt3.isChecked()); cfg.put("ant4Enabled", chkAnt4.isChecked());
            cfg.put("ant1Power", power(seekAnt1Power)); cfg.put("ant2Power", power(seekAnt2Power)); cfg.put("ant3Power", power(seekAnt3Power)); cfg.put("ant4Power", power(seekAnt4Power)); root.put("configuration", cfg);
            JSONObject ctx = new JSONObject(); ctx.put("gpsCaptured", gpsCaptured); ctx.put("latitude", latitude); ctx.put("longitude", longitude);
            ctx.put("accuracyMeters", accuracy); ctx.put("altitudeMeters", altitude); ctx.put("speedMetersPerSecond", speed); ctx.put("headingDegrees", heading); ctx.put("deviceTimestamp", gpsTimestamp);
            JSONObject loc = new JSONObject(); loc.put("status", locationStatus); loc.put("httpCode", locationHttpCode); loc.put("raw", locationRaw); loc.put("error", locationError); ctx.put("sagewireLocation", loc);
            JSONObject wx = new JSONObject(); wx.put("status", weatherStatus); wx.put("httpCode", weatherHttpCode); wx.put("raw", weatherRaw); wx.put("error", weatherError);
            wx.put("temperature", temperature); wx.put("condition", condition); wx.put("wind", wind); wx.put("humidity", humidity); wx.put("cached", weatherCached); wx.put("fetchedAt", weatherFetchedAt); ctx.put("weather", wx); root.put("context", ctx);
            JSONArray arr = new JSONArray(); synchronized (tags) { for (TagRecord t : tags.values()) {
                JSONObject o = new JSONObject(); o.put("epc", t.epc); o.put("count", t.count); o.put("firstSeenAt", t.firstSeenAt); o.put("lastSeenAt", t.lastSeenAt); o.put("strongestRssi", t.strongestRssi);
                JSONArray ants = new JSONArray(); for (Map.Entry<String, AntennaRecord> e : t.antennas.entrySet()) { AntennaRecord ar = e.getValue(); JSONObject ao = new JSONObject();
                    ao.put("antenna", e.getKey()); ao.put("count", ar.count); ao.put("strongestRssi", ar.strongestRssi); ao.put("latestRssi", ar.latestRssi); ao.put("firstSeenAt", ar.firstSeenAt); ao.put("lastSeenAt", ar.lastSeenAt); ants.put(ao); }
                o.put("antennas", ants);
                o.put("animalLookupStatus", t.animalLookupStatus);
                o.put("animalLookupError", t.animalLookupError);
                if (t.animal != null) o.put("animal", t.animal.toJson());
                arr.put(o); } }
            root.put("tags", arr);
        } catch (Exception ignored) {}
        return root;
    }

    private String buildCsv() {
        StringBuilder builder = new StringBuilder();
        builder.append("session_id,session_name,site,operator,notes,profile,started_at,ended_at,total_reads,unique_tags,latitude,longitude,accuracy_m,altitude_m,speed_mps,heading_deg,location_status,weather_status,temp,condition,wind,humidity,epc,animal_lookup_status,animal_tag,animal_status,animal_sex,animal_type,animal_breed,animal_source,animal_pasture,tag_reads,first_seen,last_seen,strongest_rssi,antenna_counts\n");

        synchronized (tags) {
            for (TagRecord tag : tags.values()) {
                StringBuilder antennaCounts = new StringBuilder();
                for (Map.Entry<String, AntennaRecord> entry : tag.antennas.entrySet()) {
                    if (antennaCounts.length() > 0) antennaCounts.append(";");
                    antennaCounts.append(entry.getKey()).append(":").append(entry.getValue().count);
                }

                AnimalLookupClient.AnimalRecord animal = tag.animal;
                row(
                        builder,
                        sessionId,
                        sessionName,
                        sessionSite,
                        sessionOperator,
                        sessionNotes,
                        sessionProfile,
                        fmt(startedAt),
                        fmt(endedAt),
                        String.valueOf(totalReads),
                        String.valueOf(tags.size()),
                        gpsCaptured ? String.valueOf(latitude) : "",
                        gpsCaptured ? String.valueOf(longitude) : "",
                        gpsCaptured ? String.valueOf(accuracy) : "",
                        gpsCaptured ? String.valueOf(altitude) : "",
                        gpsCaptured ? String.valueOf(speed) : "",
                        gpsCaptured ? String.valueOf(heading) : "",
                        locationStatus,
                        weatherStatus,
                        temperature,
                        condition,
                        wind,
                        humidity,
                        tag.epc,
                        tag.animalLookupStatus,
                        animal == null ? "" : animal.primaryId(),
                        animal == null ? "" : animal.status,
                        animal == null ? "" : animal.sex,
                        animal == null ? "" : animal.type,
                        animal == null ? "" : animal.breed,
                        animal == null ? "" : animal.source,
                        animal == null ? "" : animal.pasture,
                        String.valueOf(tag.count),
                        fmt(tag.firstSeenAt),
                        fmt(tag.lastSeenAt),
                        String.valueOf(tag.strongestRssi),
                        antennaCounts.toString()
                );
            }
        }
        return builder.toString();
    }

    private void row(StringBuilder b, String... values) { for (int i = 0; i < values.length; i++) { if (i > 0) b.append(','); b.append(csv(values[i])); } b.append('\n'); }
    private String csv(String s) { if (s == null) s = ""; return "\"" + s.replace("\"", "\"\"") + "\""; }
    private String fmt(long t) { return t <= 0 ? "" : dateTime.format(new Date(t)); }

    private void saveSession() { if (sessionId != null) prefs.edit().putString(PREF_ACTIVE, buildSessionJson().toString()).apply(); }

    private void restoreSession() {
        String raw = prefs.getString(PREF_ACTIVE, ""); if (raw.isEmpty()) return;
        try { loadSession(new JSONObject(raw)); if (state == SessionState.SCANNING) { state = SessionState.PAUSED; pauseStartedAt = System.currentTimeMillis(); activeStartedAt = 0; }
            setStatus("Session restored"); } catch (Exception e) { prefs.edit().remove(PREF_ACTIVE).apply(); }
    }

    private void loadSession(JSONObject root) throws Exception {
        sessionId = root.optString("sessionId", ""); sessionName = root.optString("sessionName", ""); sessionSite = root.optString("site", "");
        sessionOperator = root.optString("operator", ""); sessionNotes = root.optString("notes", ""); sessionProfile = root.optString("readerProfile", "");
        try { state = SessionState.valueOf(root.optString("state", "READY")); } catch (Exception e) { state = SessionState.READY; }
        startedAt = root.optLong("startedAt"); endedAt = root.optLong("endedAt"); totalReads = root.optInt("totalReads"); totalActiveMs = root.optLong("activeMillis"); totalPausedMs = root.optLong("pausedMillis");
        JSONObject cfg = root.optJSONObject("configuration"); if (cfg != null) { chkAnt1.setChecked(cfg.optBoolean("ant1Enabled", true)); chkAnt2.setChecked(cfg.optBoolean("ant2Enabled", true)); chkAnt3.setChecked(cfg.optBoolean("ant3Enabled", true)); chkAnt4.setChecked(cfg.optBoolean("ant4Enabled", true)); setPower(seekAnt1Power, cfg.optInt("ant1Power", 30)); setPower(seekAnt2Power, cfg.optInt("ant2Power", 30)); setPower(seekAnt3Power, cfg.optInt("ant3Power", 30)); setPower(seekAnt4Power, cfg.optInt("ant4Power", 30)); }
        resetContext(); JSONObject ctx = root.optJSONObject("context"); if (ctx != null) { gpsCaptured = ctx.optBoolean("gpsCaptured"); latitude = ctx.optDouble("latitude"); longitude = ctx.optDouble("longitude"); accuracy = (float) ctx.optDouble("accuracyMeters"); altitude = ctx.optDouble("altitudeMeters"); speed = (float) ctx.optDouble("speedMetersPerSecond"); heading = (float) ctx.optDouble("headingDegrees"); gpsTimestamp = ctx.optLong("deviceTimestamp");
            JSONObject loc = ctx.optJSONObject("sagewireLocation"); if (loc != null) { locationStatus = loc.optString("status", "PENDING"); locationHttpCode = loc.optInt("httpCode"); locationRaw = loc.optString("raw"); locationError = loc.optString("error"); }
            JSONObject wx = ctx.optJSONObject("weather"); if (wx != null) { weatherStatus = wx.optString("status", "PENDING"); weatherHttpCode = wx.optInt("httpCode"); weatherRaw = wx.optString("raw"); weatherError = wx.optString("error"); temperature = wx.optString("temperature"); condition = wx.optString("condition"); wind = wx.optString("wind"); humidity = wx.optString("humidity"); weatherCached = wx.optBoolean("cached"); weatherFetchedAt = wx.optString("fetchedAt"); } }
        tags.clear();
        JSONArray arr = root.optJSONArray("tags");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            TagRecord t = new TagRecord();
            t.epc = o.optString("epc");
            t.count = o.optInt("count");
            t.firstSeenAt = o.optLong("firstSeenAt");
            t.lastSeenAt = o.optLong("lastSeenAt");
            t.strongestRssi = o.optDouble("strongestRssi", -9999);
            t.animalLookupStatus = o.optString("animalLookupStatus", "NOT_REQUESTED");
            t.animalLookupError = o.optString("animalLookupError", "");
            JSONObject animal = o.optJSONObject("animal");
            if (animal != null) t.animal = AnimalLookupClient.AnimalRecord.fromJson(animal);

            JSONArray ants = o.optJSONArray("antennas");
            if (ants != null) for (int j = 0; j < ants.length(); j++) {
                JSONObject ao = ants.getJSONObject(j);
                AntennaRecord ar = new AntennaRecord();
                ar.count = ao.optInt("count");
                ar.strongestRssi = ao.optDouble("strongestRssi", -9999);
                ar.latestRssi = ao.optDouble("latestRssi", -9999);
                ar.firstSeenAt = ao.optLong("firstSeenAt");
                ar.lastSeenAt = ao.optLong("lastSeenAt");
                t.antennas.put(ao.optString("antenna"), ar);
            }
            tags.put(t.epc, t);
        }
    }

    private void archiveSession() {
        try { JSONArray h = new JSONArray(prefs.getString(PREF_HISTORY, "[]")); JSONObject current = buildSessionJson();
            JSONArray next = new JSONArray(); next.put(current); for (int i = 0; i < Math.min(h.length(), 49); i++) next.put(h.getJSONObject(i));
            prefs.edit().putString(PREF_HISTORY, next.toString()).putString(PREF_ACTIVE, current.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void showHistory() {
        try { JSONArray h = new JSONArray(prefs.getString(PREF_HISTORY, "[]")); if (h.length() == 0) { setStatus("No session history"); return; }
            String[] labels = new String[h.length()]; for (int i = 0; i < h.length(); i++) { JSONObject o = h.getJSONObject(i); labels[i] = o.optString("sessionName", "Session") + " | " + fmt(o.optLong("endedAt")) + " | " + o.optInt("totalReads") + " reads"; }
            new AlertDialog.Builder(this).setTitle("Session History").setItems(labels, (d, which) -> { try { loadSession(h.getJSONObject(which)); refreshDisplay(); updateContextBar(); updateControls(); setStatus("History session loaded"); } catch (Exception e) { setStatus("History load failed"); } }).setNegativeButton("Close", null).show();
        } catch (Exception e) { setStatus("History unavailable"); }
    }

    private void beginExport(boolean csv) {
        pendingExport = csv ? buildCsv() : buildSessionJson().toString();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(csv ? "text/csv" : "application/json");
        i.putExtra(Intent.EXTRA_TITLE, safeFile(sessionName) + "-" + fileTime.format(new Date()) + (csv ? ".csv" : ".json"));
        startActivityForResult(i, csv ? REQ_CSV : REQ_JSON);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data); if (resultCode != RESULT_OK || data == null || pendingExport == null) return;
        Uri uri = data.getData(); if (uri == null) return; try (OutputStream out = getContentResolver().openOutputStream(uri)) { if (out != null) out.write(pendingExport.getBytes(StandardCharsets.UTF_8)); setStatus("Export saved"); }
        catch (Exception e) { setStatus("Export failed: " + e.getMessage()); } pendingExport = null;
    }

    private void shareSession() {
        Intent i = new Intent(Intent.ACTION_SEND); i.setType("application/json"); i.putExtra(Intent.EXTRA_SUBJECT, "Tag Reaper Sentinel — " + sessionName); i.putExtra(Intent.EXTRA_TEXT, buildSessionJson().toString()); startActivity(Intent.createChooser(i, "Share session"));
    }

    private String safeFile(String s) { String v = s == null || s.trim().isEmpty() ? "sentinel-session" : s.trim(); return v.replaceAll("[^A-Za-z0-9._-]+", "-"); }

    private void showProfilesDialog() {
        List<ReaderProfile> profiles = loadProfiles();
        List<String> actions = new ArrayList<>();
        actions.add("HerdMate animal lookup settings");
        actions.add("Save current antenna settings as new profile");
        for (ReaderProfile profile : profiles) actions.add("Load: " + profile.name);
        actions.add("Delete a profile");

        new AlertDialog.Builder(this)
                .setTitle("Sentinel Settings and Profiles")
                .setItems(actions.toArray(new String[0]), (dialog, index) -> {
                    if (index == 0) {
                        showAnimalLookupSettings();
                    } else if (index == 1) {
                        showSaveProfile();
                    } else if (index == actions.size() - 1) {
                        showDeleteProfile();
                    } else {
                        ReaderProfile profile = profiles.get(index - 2);
                        applyProfile(profile);
                        profile.lastUsedAt = System.currentTimeMillis();
                        saveProfile(profile);
                        setStatus("Profile loaded: " + profile.name);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showSaveProfile() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); int pad = (int) (18 * getResources().getDisplayMetrics().density); form.setPadding(pad, 8, pad, 0);
        EditText n = field("Profile name", ""); EditText d = field("Description, optional", ""); form.addView(n); form.addView(d);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Save Reader Profile").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { String name = n.getText().toString().trim(); if (name.isEmpty()) { n.setError("Profile name is required"); return; }
            ReaderProfile p = captureProfile(name, d.getText().toString().trim()); saveProfile(p); setStatus("Profile saved: " + name); dialog.dismiss(); })); dialog.show();
    }

    private void showDeleteProfile() {
        List<ReaderProfile> ps = loadProfiles(); if (ps.isEmpty()) { setStatus("No profiles to delete"); return; }
        String[] names = new String[ps.size()]; for (int i = 0; i < ps.size(); i++) names[i] = ps.get(i).name;
        new AlertDialog.Builder(this).setTitle("Delete Profile").setItems(names, (d, i) -> { List<ReaderProfile> left = loadProfiles(); left.removeIf(p -> p.name.equals(names[i])); saveProfiles(left); setStatus("Profile deleted: " + names[i]); }).setNegativeButton("Cancel", null).show();
    }

    private ReaderProfile captureProfile(String name, String description) {
        ReaderProfile p = new ReaderProfile(); p.name = name; p.description = description; p.a1 = chkAnt1.isChecked(); p.a2 = chkAnt2.isChecked(); p.a3 = chkAnt3.isChecked(); p.a4 = chkAnt4.isChecked(); p.p1 = power(seekAnt1Power); p.p2 = power(seekAnt2Power); p.p3 = power(seekAnt3Power); p.p4 = power(seekAnt4Power); p.lastUsedAt = System.currentTimeMillis(); return p;
    }

    private void applyProfile(ReaderProfile p) { chkAnt1.setChecked(p.a1); chkAnt2.setChecked(p.a2); chkAnt3.setChecked(p.a3); chkAnt4.setChecked(p.a4); setPower(seekAnt1Power, p.p1); setPower(seekAnt2Power, p.p2); setPower(seekAnt3Power, p.p3); setPower(seekAnt4Power, p.p4); sessionProfile = p.name; saveSession(); }
    private ReaderProfile findProfile(String name) { for (ReaderProfile p : loadProfiles()) if (p.name.equals(name)) return p; return null; }

    private List<ReaderProfile> loadProfiles() {
        List<ReaderProfile> out = new ArrayList<>(); try { JSONArray a = new JSONArray(prefs.getString(PREF_PROFILES, "[]")); for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); ReaderProfile p = new ReaderProfile(); p.name = o.optString("name"); p.description = o.optString("description");
            p.a1 = o.has("a1") ? o.optBoolean("a1", true) : o.optBoolean("ant1Enabled", true);
            p.a2 = o.has("a2") ? o.optBoolean("a2", true) : o.optBoolean("ant2Enabled", true);
            p.a3 = o.has("a3") ? o.optBoolean("a3", true) : o.optBoolean("ant3Enabled", true);
            p.a4 = o.has("a4") ? o.optBoolean("a4", true) : o.optBoolean("ant4Enabled", true);
            p.p1 = o.has("p1") ? o.optInt("p1", 30) : o.optInt("ant1Power", 30);
            p.p2 = o.has("p2") ? o.optInt("p2", 30) : o.optInt("ant2Power", 30);
            p.p3 = o.has("p3") ? o.optInt("p3", 30) : o.optInt("ant3Power", 30);
            p.p4 = o.has("p4") ? o.optInt("p4", 30) : o.optInt("ant4Power", 30);
            p.lastUsedAt = o.optLong("lastUsedAt"); if (!p.name.isEmpty()) out.add(p); } } catch (Exception ignored) {}
        Collections.sort(out, (a, b) -> Long.compare(b.lastUsedAt, a.lastUsedAt)); return out;
    }

    private void saveProfile(ReaderProfile p) { List<ReaderProfile> ps = loadProfiles(); boolean replaced = false; for (int i = 0; i < ps.size(); i++) if (ps.get(i).name.equals(p.name)) { ps.set(i, p); replaced = true; break; } if (!replaced) ps.add(p); saveProfiles(ps); }
    private void saveProfiles(List<ReaderProfile> ps) { JSONArray a = new JSONArray(); try { for (ReaderProfile p : ps) { JSONObject o = new JSONObject(); o.put("name", p.name); o.put("description", p.description);
            o.put("a1", p.a1); o.put("a2", p.a2); o.put("a3", p.a3); o.put("a4", p.a4);
            o.put("p1", p.p1); o.put("p2", p.p2); o.put("p3", p.p3); o.put("p4", p.p4);
            o.put("ant1Enabled", p.a1); o.put("ant2Enabled", p.a2); o.put("ant3Enabled", p.a3); o.put("ant4Enabled", p.a4);
            o.put("ant1Power", p.p1); o.put("ant2Power", p.p2); o.put("ant3Power", p.p3); o.put("ant4Power", p.p4);
            o.put("lastUsedAt", p.lastUsedAt); a.put(o); } prefs.edit().putString(PREF_PROFILES, a.toString()).apply(); } catch (Exception ignored) {} }

    @Override protected void onDestroy() {
        stopGpsSearch();
        animalLookupExecutor.shutdownNow();
        clock.removeCallbacks(clockTick);
        try {
            if (reader != null && state == SessionState.SCANNING) reader.stopInventory();
        } catch (Throwable ignored) {}
        super.onDestroy();
    }
}

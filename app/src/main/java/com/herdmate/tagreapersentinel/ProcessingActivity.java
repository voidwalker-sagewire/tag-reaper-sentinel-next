package com.herdmate.tagreapersentinel;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.rscja.deviceapi.Module;
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
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Two-position cattle-processing workflow: RFID/weight on scale, services in head chute. */
public class ProcessingActivity extends AppCompatActivity {
    private static final String PREFS = "tag_reaper_sentinel";
    private static final String PREF_ACTIVE = "processing_active_session_v1";
    private static final String PREF_HISTORY = "processing_history_v1";
    private static final String PREF_ANIMAL_CACHE = "animal_lookup_cache";
    private static final String PREF_SHEET = "herdmate_sheet_id";
    private static final int MODULE_EXTENDED_UART = 10;
    private static final int BAUD = 9600;
    private static final int REQ_JSON = 3101;
    private static final int REQ_CSV = 3102;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final StableWeightLatch weightLatch = new StableWeightLatch(3, 2d);
    private final StringBuilder serialLine = new StringBuilder();
    private ProcessingPipeline pipeline = new ProcessingPipeline();
    private SharedPreferences prefs;

    private String sessionId = "";
    private String sessionName = "";
    private String sessionTemplate = "";
    private String sessionSite = "";
    private String sessionOperator = "";
    private String sessionVeterinarian = "";
    private String sessionGroup = "";
    private String sessionNotes = "";
    private long sessionStartedAt;
    private final List<String> plannedItems = new ArrayList<>();

    private RFIDWithUHFA4 reader;
    private Module scaleModule;
    private volatile Thread scaleThread;
    private volatile boolean scaleRunning;
    private boolean readerConnected;
    private boolean inventoryRunning;
    private String latestScaleFrame = "";
    private SellEtonFrameParser.Result latestScaleReading;
    private String pendingExport = "";

    private TextView txtHeader, txtHardware, txtStatus, txtScaleIdentity, txtScaleWeight;
    private TextView txtScaleEvidence, txtChuteIdentity, txtChuteWeight, txtChecklist;
    private Button btnNew, btnHardware, btnRun, btnHistory, btnJson, btnCsv;
    private Button btnResolve, btnClearScale, btnAdvance, btnRecord, btnHold, btnComplete;
    private Spinner spinnerAntenna, spinnerPower;
    private EditText editDestination, editNotes;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_processing);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        bindViews();
        configureSelectors();
        configureButtons();
        restoreSession();
        refreshUi();
    }

    private void bindViews() {
        txtHeader = findViewById(R.id.txtProcessingHeader);
        txtHardware = findViewById(R.id.txtProcessHardware);
        txtStatus = findViewById(R.id.txtProcessingStatus);
        txtScaleIdentity = findViewById(R.id.txtOnScaleIdentity);
        txtScaleWeight = findViewById(R.id.txtOnScaleWeight);
        txtScaleEvidence = findViewById(R.id.txtOnScaleEvidence);
        txtChuteIdentity = findViewById(R.id.txtChuteIdentity);
        txtChuteWeight = findViewById(R.id.txtChuteWeight);
        txtChecklist = findViewById(R.id.txtChecklist);
        btnNew = findViewById(R.id.btnProcessNew);
        btnHardware = findViewById(R.id.btnProcessHardware);
        btnRun = findViewById(R.id.btnProcessRun);
        btnHistory = findViewById(R.id.btnProcessHistory);
        btnJson = findViewById(R.id.btnProcessJson);
        btnCsv = findViewById(R.id.btnProcessCsv);
        btnResolve = findViewById(R.id.btnResolveTag);
        btnClearScale = findViewById(R.id.btnClearScale);
        btnAdvance = findViewById(R.id.btnAdvanceChute);
        btnRecord = findViewById(R.id.btnRecordItem);
        btnHold = findViewById(R.id.btnHoldEncounter);
        btnComplete = findViewById(R.id.btnCompleteEncounter);
        spinnerAntenna = findViewById(R.id.spinnerProcessAntenna);
        spinnerPower = findViewById(R.id.spinnerProcessPower);
        editDestination = findViewById(R.id.editDestination);
        editNotes = findViewById(R.id.editEncounterNotes);
    }

    private void configureSelectors() {
        ArrayAdapter<String> ant = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Antenna 1", "Antenna 2", "Antenna 3", "Antenna 4"});
        ant.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAntenna.setAdapter(ant);
        ArrayAdapter<String> power = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"30 dBm", "25 dBm", "20 dBm", "15 dBm", "10 dBm", "5 dBm"});
        power.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPower.setAdapter(power);
    }

    private void configureButtons() {
        btnNew.setOnClickListener(v -> showNewSessionDialog());
        btnHardware.setOnClickListener(v -> connectHardware());
        btnRun.setOnClickListener(v -> togglePipeline());
        btnResolve.setOnClickListener(v -> showResolveDialog());
        btnClearScale.setOnClickListener(v -> confirmClearScale());
        btnAdvance.setOnClickListener(v -> advanceToChute());
        btnRecord.setOnClickListener(v -> showRecordDialog());
        btnHold.setOnClickListener(v -> finishEncounter(true));
        btnComplete.setOnClickListener(v -> finishEncounter(false));
        btnHistory.setOnClickListener(v -> showHistory());
        btnJson.setOnClickListener(v -> beginExport(false));
        btnCsv.setOnClickListener(v -> beginExport(true));
    }

    private void showNewSessionDialog() {
        LinearLayout form = form();
        EditText name = field("Session name", "");
        Spinner template = spinner(new String[]{"Purchased Calf Arrival", "Weaning", "Separation / Turnout",
                "Bull Examination", "Illness / Injury / Veterinary", "Custom"});
        EditText site = field("Farm or site", sessionSite);
        EditText operator = field("Operator", sessionOperator);
        EditText veterinarian = field("Veterinarian (optional)", "");
        EditText group = field("Herd / group", "");
        EditText checklist = field("Planned items, comma separated", defaultItems(0));
        EditText notes = field("Session notes", "");
        form.addView(name); form.addView(template); form.addView(site); form.addView(operator);
        form.addView(veterinarian); form.addView(group); form.addView(checklist); form.addView(notes);
        template.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> checklist.setText(defaultItems(position))));
        new AlertDialog.Builder(this).setTitle("New cattle-processing session").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Create", (dialog, which) -> {
                    if (!sessionId.isEmpty()) archiveCurrentSession();
                    stopInventory();
                    pipeline = new ProcessingPipeline();
                    weightLatch.reset();
                    sessionId = UUID.randomUUID().toString();
                    sessionName = clean(name.getText().toString());
                    if (sessionName.isEmpty()) sessionName = template.getSelectedItem().toString();
                    sessionTemplate = template.getSelectedItem().toString();
                    sessionSite = clean(site.getText().toString());
                    sessionOperator = clean(operator.getText().toString());
                    sessionVeterinarian = clean(veterinarian.getText().toString());
                    sessionGroup = clean(group.getText().toString());
                    sessionNotes = clean(notes.getText().toString());
                    sessionStartedAt = System.currentTimeMillis();
                    plannedItems.clear();
                    for (String item : checklist.getText().toString().split(",")) if (!clean(item).isEmpty()) plannedItems.add(clean(item));
                    saveSession();
                    setStatus("Session ready. Connect hardware, then start the pipeline.");
                    refreshUi();
                }).show();
    }

    private String defaultItems(int position) {
        switch (position) {
            case 0: return "Identify / tag, Planned treatment 1, Planned treatment 2, Destination";
            case 1: return "Weaning protocol, Destination";
            case 2: return "Sort / assign group, Destination";
            case 3: return "Scrotal examination, Examination result, Disposition";
            case 4: return "Observations, Veterinary service, Follow-up, Disposition";
            default: return "Observation, Procedure, Disposition";
        }
    }

    private void connectHardware() {
        btnHardware.setEnabled(false);
        setStatus("Connecting RFID reader and module-10 scale serial…");
        new Thread(() -> {
            String error = "";
            try {
                reader = RFIDWithUHFA4.getInstance();
                readerConnected = reader.init(getApplicationContext());
                if (readerConnected) reader.setInventoryCallback(rfidCallback);
                else error = "RFID init returned false";
            } catch (Throwable t) { readerConnected = false; error = "RFID: " + safeMessage(t); }
            final String result = error;
            main.post(() -> {
                if (!readerConnected) setStatus(result);
                startScaleSerial();
                btnHardware.setEnabled(true);
                refreshUi();
            });
        }, "sentinel-processing-rfid").start();
    }

    private void startScaleSerial() {
        if (scaleRunning || scaleThread != null) return;
        scaleThread = new Thread(() -> {
            try {
                scaleModule = Module.getInstance();
                boolean ok = scaleModule.init(MODULE_EXTENDED_UART, BAUD, 8, 1, 0);
                if (!ok) throw new IllegalStateException("module 10 init returned false");
                scaleRunning = true;
                main.post(() -> { setStatus("Hardware connected. Start the pipeline."); refreshUi(); });
                while (scaleRunning && !Thread.currentThread().isInterrupted()) {
                    byte[] data = scaleModule.receiveEx();
                    if (data != null && data.length > 0) acceptSerialBytes(data);
                    else try { Thread.sleep(20L); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                }
            } catch (Throwable t) {
                main.post(() -> { setStatus("Scale serial error: " + safeMessage(t)); refreshUi(); });
            } finally {
                closeScale();
                scaleThread = null;
            }
        }, "sentinel-processing-scale");
        scaleThread.start();
    }

    private void acceptSerialBytes(byte[] data) {
        String text = new String(data, StandardCharsets.US_ASCII);
        synchronized (serialLine) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\r' || c == '\n') {
                    if (serialLine.length() > 0) {
                        String frame = serialLine.toString();
                        serialLine.setLength(0);
                        main.post(() -> processScaleFrame(frame));
                    }
                } else if (c >= 32 && c <= 126 && serialLine.length() < 512) serialLine.append(c);
            }
        }
    }

    private void processScaleFrame(String frame) {
        latestScaleFrame = frame.trim();
        latestScaleReading = SellEtonFrameParser.parse(frame);
        if (pipeline.onScale == null) weightLatch.reset();
        else if (!pipeline.onScale.weightLocked) {
            SellEtonFrameParser.Result locked = weightLatch.offer(latestScaleReading);
            if (locked != null && pipeline.attachWeight(locked, System.currentTimeMillis())) {
                setStatus("Stable weight locked to " + pipeline.onScale.epc + ". Ready to advance when chute is clear.");
                saveSession();
            }
        }
        refreshUi();
    }

    private void togglePipeline() {
        if (inventoryRunning) { stopInventory(); setStatus("RFID inventory stopped; session remains saved."); refreshUi(); return; }
        if (sessionId.isEmpty()) { setStatus("Create a processing session first."); return; }
        if (!readerConnected || reader == null || !scaleRunning) { setStatus("Connect both RFID and scale hardware first."); return; }
        int antIndex = spinnerAntenna.getSelectedItemPosition();
        int power = Integer.parseInt(spinnerPower.getSelectedItem().toString().split(" ")[0]);
        AntennaEnum[] antennas = {AntennaEnum.ANT1, AntennaEnum.ANT2, AntennaEnum.ANT3, AntennaEnum.ANT4};
        List<AntennaState> states = new ArrayList<>();
        for (int i = 0; i < antennas.length; i++) states.add(new AntennaState(antennas[i], i == antIndex));
        try {
            reader.setANT(states);
            if (!reader.setAntennaPower(antennas[antIndex], power)) { setStatus("RFID antenna power was rejected."); return; }
        } catch (Throwable t) { setStatus("RFID setup failed: " + safeMessage(t)); return; }
        new Thread(() -> {
            boolean ok = false; String error = "";
            try { ok = reader.startInventoryTag(); } catch (Throwable t) { error = safeMessage(t); }
            boolean started = ok; String failure = error;
            main.post(() -> {
                inventoryRunning = started;
                setStatus(started ? "Pipeline running. Scan exactly one animal on the scale."
                        : "RFID start failed: " + failure);
                refreshUi();
            });
        }, "sentinel-processing-inventory").start();
    }

    private final IUHFInventoryCallback rfidCallback = new IUHFInventoryCallback() {
        @Override public void callback(UHFTAGInfo info) {
            if (!inventoryRunning || info == null || info.getEPC() == null) return;
            String epc = info.getEPC().trim();
            String antenna = String.valueOf(info.getAnt());
            double rssi = parseDouble(info.getRssi());
            main.post(() -> {
                boolean wasEmpty = pipeline.onScale == null;
                ProcessingPipeline.ScanResult result = pipeline.scan(epc, antenna, rssi,
                        System.currentTimeMillis(), plannedItems);
                if (wasEmpty && result == ProcessingPipeline.ScanResult.CREATED) {
                    weightLatch.reset();
                    applyCachedAnimal(pipeline.onScale);
                    setStatus("Animal staged on scale. Waiting for three agreeing stable weights.");
                } else if (result == ProcessingPipeline.ScanResult.AMBIGUOUS) {
                    weightLatch.reset();
                    setStatus("MULTIPLE TAGS: weight association blocked. Tap Resolve Tag.");
                }
                saveSession(); refreshUi();
            });
        }
    };

    private void applyCachedAnimal(ProcessingPipeline.Encounter encounter) {
        if (encounter == null) return;
        try {
            JSONObject cache = new JSONObject(prefs.getString(PREF_ANIMAL_CACHE, "{}"));
            String key = prefs.getString(PREF_SHEET, "").trim() + "|" + encounter.epc;
            JSONObject animalJson = cache.optJSONObject(key);
            if (animalJson != null) {
                AnimalLookupClient.AnimalRecord animal = AnimalLookupClient.AnimalRecord.fromJson(animalJson);
                encounter.animalId = animal.primaryId();
                encounter.visualTag = animal.tag;
                encounter.lookupEvidence = animalJson.toString();
            }
        } catch (Exception ignored) {}
    }

    private void showResolveDialog() {
        if (pipeline.ambiguousEpcs.isEmpty()) { setStatus("There is no multiple-tag exception to resolve."); return; }
        String[] choices = pipeline.ambiguousEpcs.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Which EPC is physically on the scale?")
                .setItems(choices, (dialog, which) -> {
                    pipeline.resolveScaleIdentity(choices[which], System.currentTimeMillis(), plannedItems);
                    weightLatch.reset(); applyCachedAnimal(pipeline.onScale); saveSession(); refreshUi();
                    setStatus("Identity resolved to " + choices[which] + ". Waiting for a new stable weight lock.");
                }).setNegativeButton("Cancel", null).show();
    }

    private void confirmClearScale() {
        if (pipeline.onScale == null && pipeline.ambiguousEpcs.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("Reject scale encounter?")
                .setMessage("This clears only the ON_SCALE slot. The head-chute animal is not changed.")
                .setNegativeButton("Cancel", null).setPositiveButton("Reject / Clear", (d, w) -> {
                    pipeline.onScale = null; pipeline.ambiguousEpcs.clear(); weightLatch.reset();
                    saveSession(); refreshUi(); setStatus("Scale slot cleared.");
                }).show();
    }

    private void advanceToChute() {
        if (!pipeline.advance(System.currentTimeMillis())) {
            if (pipeline.inHeadChute != null) setStatus("Head chute occupied by " + pipeline.inHeadChute.epc + ". Complete or hold it first.");
            else if (!pipeline.ambiguousEpcs.isEmpty()) setStatus("Resolve the multiple-tag exception first.");
            else setStatus("A locked stable weight is required before advancing.");
            return;
        }
        weightLatch.reset(); editDestination.setText(""); editNotes.setText("");
        saveSession(); refreshUi(); setStatus("Animal protected in head chute. Scale is ready for the next animal.");
    }

    private void showRecordDialog() {
        ProcessingPipeline.Encounter encounter = pipeline.inHeadChute;
        if (encounter == null) { setStatus("No animal is in the head chute."); return; }
        if (encounter.checklist.isEmpty()) { setStatus("This session has no planned checklist items."); return; }
        List<ProcessingPipeline.ChecklistItem> items = new ArrayList<>(encounter.checklist.values());
        LinearLayout form = form();
        Spinner itemSpinner = spinner(names(items));
        Spinner kind = spinner(new String[]{"TREATMENT", "PROCEDURE", "OBSERVATION"});
        Spinner status = spinner(new String[]{"COMPLETED", "SKIPPED", "NOT_APPLICABLE", "HELD"});
        EditText product = field("Product / treatment or procedure name", "");
        EditText amount = field("Actual amount / measurement", "");
        EditText unit = field("Unit", "");
        EditText route = field("Route / site", "");
        EditText lot = field("Lot / serial", "");
        EditText expiration = field("Expiration", "");
        EditText withdrawal = field("Withdrawal / end date supplied by responsible person", "");
        EditText result = field("Procedure result / observation", "");
        EditText by = field("Administered / performed by", sessionOperator);
        EditText notes = field("Item notes", "");
        form.addView(itemSpinner); form.addView(kind); form.addView(status); form.addView(product);
        form.addView(amount); form.addView(unit); form.addView(route); form.addView(lot); form.addView(expiration);
        form.addView(withdrawal); form.addView(result); form.addView(by); form.addView(notes);
        new AlertDialog.Builder(this).setTitle("Record actual service — no dose recommendations")
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
                    ProcessingPipeline.ChecklistItem item = items.get(itemSpinner.getSelectedItemPosition());
                    item.kind = kind.getSelectedItem().toString(); item.status = status.getSelectedItem().toString();
                    item.product = clean(product.getText().toString()); item.amount = clean(amount.getText().toString());
                    item.unit = clean(unit.getText().toString()); item.routeSite = clean(route.getText().toString());
                    item.lotSerial = clean(lot.getText().toString()); item.expiration = clean(expiration.getText().toString());
                    item.withdrawalEnd = clean(withdrawal.getText().toString()); item.result = clean(result.getText().toString());
                    item.administeredBy = clean(by.getText().toString()); item.notes = clean(notes.getText().toString());
                    item.recordedAt = System.currentTimeMillis(); saveSession(); refreshUi();
                    setStatus(item.name + " recorded as " + item.status + " for " + encounter.epc + ".");
                }).show();
    }

    private void finishEncounter(boolean held) {
        ProcessingPipeline.Encounter encounter = pipeline.inHeadChute;
        if (encounter == null) { setStatus("No animal is in the head chute."); return; }
        String destination = clean(editDestination.getText().toString());
        if (!held && destination.isEmpty()) { setStatus("Enter destination / disposition before completion."); return; }
        if (!held && encounter.hasPendingItems()) { setStatus("Record every checklist item as completed, skipped, not applicable, or held."); return; }
        pipeline.finish(held, destination, editNotes.getText().toString(), System.currentTimeMillis(), sessionOperator);
        editDestination.setText(""); editNotes.setText(""); saveSession(); refreshUi();
        setStatus((held ? "Held" : "Completed") + " encounter for " + encounter.epc + ". Head chute released.");
    }

    private void refreshUi() {
        txtHeader.setText(sessionId.isEmpty() ? "CATTLE PROCESSING | No active session"
                : "CATTLE PROCESSING | " + sessionName + " | " + pipeline.finished.size() + " finished");
        txtHardware.setText("RFID: " + (readerConnected ? (inventoryRunning ? "RUNNING" : "connected") : "disconnected")
                + " | SCALE: " + (scaleRunning ? "OPEN module 10" : "disconnected") + " | 9600 8-N-1");
        txtHardware.setTextColor((readerConnected && scaleRunning) ? 0xff86efac : 0xfffbbf24);
        btnRun.setText(inventoryRunning ? "Stop Pipeline" : "Start Pipeline");
        btnJson.setEnabled(!sessionId.isEmpty()); btnCsv.setEnabled(!sessionId.isEmpty());

        ProcessingPipeline.Encounter scale = pipeline.onScale;
        if (scale == null) {
            txtScaleIdentity.setText(pipeline.ambiguousEpcs.isEmpty() ? "Waiting for one EPC" : "MULTIPLE TAGS — RESOLUTION REQUIRED");
            txtScaleWeight.setText(latestScaleReading == null ? "— lb" : formatPounds(latestScaleReading.pounds) + " lb LIVE");
            txtScaleEvidence.setText(latestScaleFrame.isEmpty() ? "No RFID or scale evidence" : "Unassigned scale frame: " + latestScaleFrame);
        } else {
            txtScaleIdentity.setText(identity(scale) + (scale.exception.isEmpty() ? "" : "\n⚠ " + scale.exception));
            txtScaleWeight.setText(scale.weightLocked ? formatPounds(scale.weightPounds) + " lb LOCKED"
                    : latestScaleReading == null ? "— lb" : formatPounds(latestScaleReading.pounds) + " lb LIVE");
            txtScaleEvidence.setText("Reads " + scale.readCount + " | ANT " + scale.rfidAntenna + " | RSSI "
                    + formatRssi(scale.strongestRssi) + "\n" + (scale.weightLocked ? scale.rawWeightFrame : latestScaleFrame));
        }
        btnResolve.setEnabled(!pipeline.ambiguousEpcs.isEmpty());
        btnClearScale.setEnabled(scale != null || !pipeline.ambiguousEpcs.isEmpty());
        btnAdvance.setEnabled(pipeline.canAdvance());

        ProcessingPipeline.Encounter chute = pipeline.inHeadChute;
        if (chute == null) {
            txtChuteIdentity.setText("Chute available"); txtChuteWeight.setText("Weight: —"); txtChecklist.setText("No animal in head chute");
            btnRecord.setEnabled(false); btnHold.setEnabled(false); btnComplete.setEnabled(false);
        } else {
            txtChuteIdentity.setText(identity(chute)); txtChuteWeight.setText("Weight: " + formatPounds(chute.weightPounds) + " lb");
            StringBuilder list = new StringBuilder();
            for (ProcessingPipeline.ChecklistItem item : chute.checklist.values()) list.append(statusMark(item.status)).append(' ')
                    .append(item.name).append(" — ").append(item.status).append('\n');
            txtChecklist.setText(list.length() == 0 ? "No planned items" : list.toString().trim());
            btnRecord.setEnabled(true); btnHold.setEnabled(true); btnComplete.setEnabled(true);
        }
    }

    private String identity(ProcessingPipeline.Encounter e) {
        String label = e.visualTag.isEmpty() ? e.animalId : e.visualTag;
        return (label.isEmpty() ? "" : label + " | ") + e.epc;
    }

    private String statusMark(String status) {
        if ("COMPLETED".equals(status)) return "✓";
        if ("PENDING".equals(status)) return "○";
        if ("HELD".equals(status)) return "!";
        return "—";
    }

    private void saveSession() {
        if (sessionId.isEmpty()) { prefs.edit().remove(PREF_ACTIVE).apply(); return; }
        try { prefs.edit().putString(PREF_ACTIVE, sessionToJson().toString()).apply(); }
        catch (Exception e) { setStatus("Save failed: " + e.getMessage()); }
    }

    private JSONObject sessionToJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("schema", "0.8"); root.put("sessionId", sessionId); root.put("name", sessionName);
        root.put("template", sessionTemplate); root.put("site", sessionSite); root.put("operator", sessionOperator);
        root.put("veterinarian", sessionVeterinarian); root.put("group", sessionGroup); root.put("notes", sessionNotes);
        root.put("startedAt", sessionStartedAt); root.put("savedAt", System.currentTimeMillis());
        root.put("plannedChecklist", new JSONArray(plannedItems));
        root.put("onScale", pipeline.onScale == null ? JSONObject.NULL : encounterToJson(pipeline.onScale));
        root.put("inHeadChute", pipeline.inHeadChute == null ? JSONObject.NULL : encounterToJson(pipeline.inHeadChute));
        JSONArray ambiguous = new JSONArray(); for (String epc : pipeline.ambiguousEpcs) ambiguous.put(epc); root.put("ambiguousEpcs", ambiguous);
        JSONArray finished = new JSONArray(); for (ProcessingPipeline.Encounter e : pipeline.finished) finished.put(encounterToJson(e)); root.put("encounters", finished);
        return root;
    }

    private JSONObject encounterToJson(ProcessingPipeline.Encounter e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("encounterId", e.encounterId); o.put("sessionId", sessionId); o.put("epc", e.epc); o.put("stage", e.stage);
        o.put("exception", e.exception); o.put("scannedAt", e.scannedAt); o.put("lastReadAt", e.lastReadAt);
        o.put("weighedAt", e.weighedAt); o.put("treatmentStartedAt", e.treatmentStartedAt); o.put("completedAt", e.completedAt);
        o.put("rfidAntenna", e.rfidAntenna); o.put("readCount", e.readCount); o.put("strongestRssi", e.strongestRssi);
        o.put("animalId", e.animalId); o.put("visualTag", e.visualTag); o.put("lookupEvidence", e.lookupEvidence);
        JSONObject weight = new JSONObject(); weight.put("locked", e.weightLocked); weight.put("pounds", e.weightPounds);
        weight.put("originalValue", e.originalWeight); weight.put("originalUnit", e.originalUnit); weight.put("grossNet", e.weightMode);
        weight.put("sign", e.weightSign); weight.put("stable", e.weightLocked); weight.put("rawFrame", e.rawWeightFrame); o.put("weight", weight);
        o.put("destination", e.destination); o.put("notes", e.notes); o.put("completedBy", e.completedBy);
        JSONArray checklist = new JSONArray(); for (ProcessingPipeline.ChecklistItem item : e.checklist.values()) checklist.put(itemToJson(item));
        o.put("checklist", checklist); return o;
    }

    private JSONObject itemToJson(ProcessingPipeline.ChecklistItem i) throws Exception {
        JSONObject o = new JSONObject(); o.put("itemId", i.itemId); o.put("name", i.name); o.put("kind", i.kind); o.put("status", i.status);
        o.put("product", i.product); o.put("amount", i.amount); o.put("unit", i.unit); o.put("routeSite", i.routeSite);
        o.put("lotSerial", i.lotSerial); o.put("expiration", i.expiration); o.put("withdrawalEnd", i.withdrawalEnd);
        o.put("result", i.result); o.put("administeredBy", i.administeredBy); o.put("notes", i.notes); o.put("recordedAt", i.recordedAt); return o;
    }

    private void restoreSession() {
        String raw = prefs.getString(PREF_ACTIVE, ""); if (raw.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(raw); if (!"0.8".equals(root.optString("schema"))) return;
            sessionId = root.optString("sessionId"); sessionName = root.optString("name"); sessionTemplate = root.optString("template");
            sessionSite = root.optString("site"); sessionOperator = root.optString("operator"); sessionVeterinarian = root.optString("veterinarian");
            sessionGroup = root.optString("group"); sessionNotes = root.optString("notes"); sessionStartedAt = root.optLong("startedAt");
            plannedItems.clear(); JSONArray p = root.optJSONArray("plannedChecklist"); if (p != null) for (int i = 0; i < p.length(); i++) plannedItems.add(p.optString(i));
            pipeline = new ProcessingPipeline();
            JSONObject scale = root.optJSONObject("onScale"); if (scale != null) pipeline.onScale = encounterFromJson(scale);
            JSONObject chute = root.optJSONObject("inHeadChute"); if (chute != null) pipeline.inHeadChute = encounterFromJson(chute);
            JSONArray a = root.optJSONArray("ambiguousEpcs"); if (a != null) for (int i = 0; i < a.length(); i++) pipeline.ambiguousEpcs.add(a.optString(i));
            JSONArray finished = root.optJSONArray("encounters"); if (finished != null) for (int i = 0; i < finished.length(); i++) pipeline.finished.add(encounterFromJson(finished.getJSONObject(i)));
            if (pipeline.inHeadChute != null) { editDestination.setText(pipeline.inHeadChute.destination); editNotes.setText(pipeline.inHeadChute.notes); }
            setStatus("Restored active session and both protected live slots.");
        } catch (Exception e) { setStatus("Could not restore processing session: " + e.getMessage()); }
    }

    private ProcessingPipeline.Encounter encounterFromJson(JSONObject o) throws Exception {
        List<String> empty = new ArrayList<>(); ProcessingPipeline.Encounter e = new ProcessingPipeline.Encounter(o.optString("epc"), o.optLong("scannedAt"), empty);
        e.encounterId = o.optString("encounterId", e.encounterId); e.stage = o.optString("stage", "ON_SCALE"); e.exception = o.optString("exception");
        e.lastReadAt = o.optLong("lastReadAt"); e.weighedAt = o.optLong("weighedAt"); e.treatmentStartedAt = o.optLong("treatmentStartedAt"); e.completedAt = o.optLong("completedAt");
        e.rfidAntenna = o.optString("rfidAntenna"); e.readCount = o.optInt("readCount"); e.strongestRssi = o.optDouble("strongestRssi", -9999d);
        e.animalId = o.optString("animalId"); e.visualTag = o.optString("visualTag"); e.lookupEvidence = o.optString("lookupEvidence");
        JSONObject w = o.optJSONObject("weight"); if (w != null) { e.weightLocked = w.optBoolean("locked"); e.weightPounds = w.optDouble("pounds"); e.originalWeight = w.optDouble("originalValue"); e.originalUnit = w.optString("originalUnit"); e.weightMode = w.optString("grossNet"); e.weightSign = w.optString("sign"); e.rawWeightFrame = w.optString("rawFrame"); }
        e.destination = o.optString("destination"); e.notes = o.optString("notes"); e.completedBy = o.optString("completedBy");
        JSONArray items = o.optJSONArray("checklist"); if (items != null) for (int i = 0; i < items.length(); i++) { ProcessingPipeline.ChecklistItem item = itemFromJson(items.getJSONObject(i)); e.checklist.put(item.name, item); }
        return e;
    }

    private ProcessingPipeline.ChecklistItem itemFromJson(JSONObject o) {
        ProcessingPipeline.ChecklistItem i = new ProcessingPipeline.ChecklistItem(o.optString("name")); i.itemId = o.optString("itemId", i.itemId);
        i.kind = o.optString("kind", "TREATMENT"); i.status = o.optString("status", "PENDING"); i.product = o.optString("product"); i.amount = o.optString("amount");
        i.unit = o.optString("unit"); i.routeSite = o.optString("routeSite"); i.lotSerial = o.optString("lotSerial"); i.expiration = o.optString("expiration");
        i.withdrawalEnd = o.optString("withdrawalEnd"); i.result = o.optString("result"); i.administeredBy = o.optString("administeredBy"); i.notes = o.optString("notes"); i.recordedAt = o.optLong("recordedAt"); return i;
    }

    private void archiveCurrentSession() {
        try {
            JSONArray history = new JSONArray(prefs.getString(PREF_HISTORY, "[]")); history.put(sessionToJson());
            while (history.length() > 50) { JSONArray trimmed = new JSONArray(); for (int i = 1; i < history.length(); i++) trimmed.put(history.get(i)); history = trimmed; }
            prefs.edit().putString(PREF_HISTORY, history.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void showHistory() {
        try {
            JSONArray history = new JSONArray(prefs.getString(PREF_HISTORY, "[]"));
            if (history.length() == 0) { setStatus("No archived processing sessions yet."); return; }
            String[] rows = new String[history.length()];
            for (int i = 0; i < history.length(); i++) { JSONObject s = history.getJSONObject(i); rows[i] = s.optString("name") + " — " + dateTime.format(new Date(s.optLong("startedAt"))) + " — " + s.optJSONArray("encounters").length() + " finished"; }
            new AlertDialog.Builder(this).setTitle("Processing history").setItems(rows, null).setPositiveButton("Close", null).show();
        } catch (Exception e) { setStatus("History error: " + e.getMessage()); }
    }

    private void beginExport(boolean csv) {
        if (sessionId.isEmpty()) return;
        pendingExport = csv ? buildCsv() : buildJson();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(csv ? "text/csv" : "application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "sentinel-processing-" + safeFile(sessionName) + (csv ? ".csv" : ".json"));
        startActivityForResult(intent, csv ? REQ_CSV : REQ_JSON);
    }

    private String buildJson() { try { return sessionToJson().toString(2); } catch (Exception e) { return "{}"; } }

    private String buildCsv() {
        StringBuilder out = new StringBuilder("session_id,session_name,template,site,operator,veterinarian,encounter_id,epc,animal_id,visual_tag,stage,weight_lb,raw_weight_frame,rfid_antenna,read_count,strongest_rssi,item,kind,status,product,amount,unit,route_site,lot_serial,expiration,withdrawal_end,result,performed_by,item_notes,destination,encounter_notes,scanned_at,weighed_at,completed_at\n");
        List<ProcessingPipeline.Encounter> all = new ArrayList<>(pipeline.finished); if (pipeline.inHeadChute != null) all.add(pipeline.inHeadChute); if (pipeline.onScale != null) all.add(pipeline.onScale);
        for (ProcessingPipeline.Encounter e : all) {
            if (e.checklist.isEmpty()) appendCsvRow(out, e, null); else for (ProcessingPipeline.ChecklistItem item : e.checklist.values()) appendCsvRow(out, e, item);
        }
        return out.toString();
    }

    private void appendCsvRow(StringBuilder out, ProcessingPipeline.Encounter e, ProcessingPipeline.ChecklistItem i) {
        String[] values = {sessionId, sessionName, sessionTemplate, sessionSite, sessionOperator, sessionVeterinarian, e.encounterId, e.epc, e.animalId, e.visualTag, e.stage,
                e.weightLocked ? formatPounds(e.weightPounds) : "", e.rawWeightFrame, e.rfidAntenna, String.valueOf(e.readCount), formatRssi(e.strongestRssi),
                i == null ? "" : i.name, i == null ? "" : i.kind, i == null ? "" : i.status, i == null ? "" : i.product, i == null ? "" : i.amount,
                i == null ? "" : i.unit, i == null ? "" : i.routeSite, i == null ? "" : i.lotSerial, i == null ? "" : i.expiration,
                i == null ? "" : i.withdrawalEnd, i == null ? "" : i.result, i == null ? "" : i.administeredBy, i == null ? "" : i.notes,
                e.destination, e.notes, stamp(e.scannedAt), stamp(e.weighedAt), stamp(e.completedAt)};
        for (int x = 0; x < values.length; x++) { if (x > 0) out.append(','); out.append(csv(values[x])); } out.append('\n');
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != REQ_JSON && requestCode != REQ_CSV) || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData(); if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("No output stream");
            output.write(pendingExport.getBytes(StandardCharsets.UTF_8)); Toast.makeText(this, "Processing export saved", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { setStatus("Export failed: " + e.getMessage()); }
    }

    private void stopInventory() {
        inventoryRunning = false;
        try { if (reader != null) reader.stopInventory(); } catch (Throwable ignored) {}
    }

    private void closeScale() {
        scaleRunning = false; Module module = scaleModule; scaleModule = null;
        if (module != null) try { module.closeSerail(); } catch (Throwable ignored) {}
    }

    @Override protected void onDestroy() {
        saveSession(); stopInventory();
        Thread thread = scaleThread; scaleRunning = false; if (thread != null) thread.interrupt(); closeScale();
        main.removeCallbacksAndMessages(null); super.onDestroy();
    }

    private LinearLayout form() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); int p = (int)(16 * getResources().getDisplayMetrics().density); l.setPadding(p, 6, p, 0); return l; }
    private EditText field(String hint, String value) { EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); e.setInputType(InputType.TYPE_CLASS_TEXT); return e; }
    private Spinner spinner(String[] values) { Spinner s = new Spinner(this); ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values); a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); return s; }
    private String[] names(List<ProcessingPipeline.ChecklistItem> items) { String[] values = new String[items.size()]; for (int i = 0; i < items.size(); i++) values[i] = items.get(i).name + " — " + items.get(i).status; return values; }
    private void setStatus(String value) { txtStatus.setText(value); }
    private String clean(String value) { return value == null ? "" : value.trim(); }
    private double parseDouble(String value) { try { return Double.parseDouble(value); } catch (Exception e) { return -9999d; } }
    private String safeMessage(Throwable t) { return t == null || t.getMessage() == null ? "no message" : t.getMessage(); }
    private String formatPounds(double value) { return Math.abs(value - Math.rint(value)) < 0.001d ? String.format(Locale.US, "%.0f", value) : String.format(Locale.US, "%.1f", value); }
    private String formatRssi(double value) { return value <= -9998d ? "—" : String.format(Locale.US, "%.1f", value); }
    private String stamp(long at) { return at <= 0 ? "" : dateTime.format(new Date(at)); }
    private String safeFile(String value) { String clean = value == null ? "session" : value.replaceAll("[^A-Za-z0-9._-]+", "-"); return clean.isEmpty() ? "session" : clean; }
    private String csv(String value) { String v = value == null ? "" : value; return "\"" + v.replace("\"", "\"\"") + "\""; }

    private static final class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        interface Selection { void selected(int position); }
        private final Selection selection;
        SimpleItemSelectedListener(Selection selection) { this.selection = selection; }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) { selection.selected(position); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }
}

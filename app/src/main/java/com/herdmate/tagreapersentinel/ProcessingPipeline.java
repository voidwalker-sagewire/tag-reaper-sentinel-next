package com.herdmate.tagreapersentinel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure domain state machine that protects animal identity across the two physical positions. */
public final class ProcessingPipeline {
    public Encounter onScale;
    public Encounter inHeadChute;
    public final List<Encounter> finished = new ArrayList<>();
    public final Set<String> ambiguousEpcs = new LinkedHashSet<>();

    public ScanResult scan(String epc, String antenna, double rssi, long at, List<String> plannedItems) {
        String clean = epc == null ? "" : epc.trim();
        if (clean.isEmpty()) return ScanResult.IGNORED;
        if (onScale == null) {
            onScale = new Encounter(clean, at, plannedItems);
            onScale.rfidAntenna = antenna == null ? "" : antenna;
            onScale.strongestRssi = rssi;
            onScale.readCount = 1;
            return ScanResult.CREATED;
        }
        if (onScale.epc.equals(clean)) {
            onScale.readCount++;
            onScale.lastReadAt = at;
            onScale.strongestRssi = Math.max(onScale.strongestRssi, rssi);
            return ScanResult.CONFIRMED;
        }
        ambiguousEpcs.add(onScale.epc);
        ambiguousEpcs.add(clean);
        onScale.exception = "MULTIPLE_TAGS";
        return ScanResult.AMBIGUOUS;
    }

    public boolean resolveScaleIdentity(String selectedEpc, long at, List<String> plannedItems) {
        if (selectedEpc == null || !ambiguousEpcs.contains(selectedEpc)) return false;
        if (onScale == null || !selectedEpc.equals(onScale.epc)) {
            onScale = new Encounter(selectedEpc, at, plannedItems);
        }
        onScale.exception = "";
        ambiguousEpcs.clear();
        return true;
    }

    public boolean attachWeight(SellEtonFrameParser.Result weight, long at) {
        if (onScale == null || weight == null || !weight.stable || !ambiguousEpcs.isEmpty()) return false;
        onScale.weightPounds = weight.pounds;
        onScale.originalWeight = weight.originalValue;
        onScale.originalUnit = weight.originalUnit;
        onScale.weightMode = weight.mode;
        onScale.weightSign = weight.sign;
        onScale.rawWeightFrame = weight.raw;
        onScale.weighedAt = at;
        onScale.weightLocked = true;
        return true;
    }

    public boolean canAdvance() {
        return onScale != null && onScale.weightLocked && ambiguousEpcs.isEmpty() && inHeadChute == null;
    }

    public boolean advance(long at) {
        if (!canAdvance()) return false;
        inHeadChute = onScale;
        inHeadChute.stage = "IN_HEAD_CHUTE";
        inHeadChute.treatmentStartedAt = at;
        onScale = null;
        ambiguousEpcs.clear();
        return true;
    }

    public boolean finish(boolean held, String destination, String notes, long at, String operator) {
        if (inHeadChute == null) return false;
        inHeadChute.stage = held ? "HELD" : "COMPLETED";
        inHeadChute.destination = destination == null ? "" : destination.trim();
        inHeadChute.notes = notes == null ? "" : notes.trim();
        inHeadChute.completedAt = at;
        inHeadChute.completedBy = operator == null ? "" : operator;
        finished.add(inHeadChute);
        inHeadChute = null;
        return true;
    }

    public enum ScanResult { CREATED, CONFIRMED, AMBIGUOUS, IGNORED }

    public static final class Encounter {
        public String encounterId = UUID.randomUUID().toString();
        public String epc;
        public String stage = "ON_SCALE";
        public String exception = "";
        public long scannedAt;
        public long lastReadAt;
        public long weighedAt;
        public long treatmentStartedAt;
        public long completedAt;
        public String rfidAntenna = "";
        public int readCount;
        public double strongestRssi = -9999d;
        public boolean weightLocked;
        public double weightPounds;
        public double originalWeight;
        public String originalUnit = "";
        public String weightMode = "";
        public String weightSign = "";
        public String rawWeightFrame = "";
        public String animalId = "";
        public String visualTag = "";
        public String lookupEvidence = "";
        public String destination = "";
        public String notes = "";
        public String completedBy = "";
        public final LinkedHashMap<String, ChecklistItem> checklist = new LinkedHashMap<>();

        public Encounter(String epc, long at, List<String> plannedItems) {
            this.epc = epc;
            this.scannedAt = at;
            this.lastReadAt = at;
            if (plannedItems != null) for (String item : plannedItems) {
                String clean = item == null ? "" : item.trim();
                if (!clean.isEmpty() && !checklist.containsKey(clean)) checklist.put(clean, new ChecklistItem(clean));
            }
        }

        public boolean hasPendingItems() {
            for (ChecklistItem item : checklist.values()) if ("PENDING".equals(item.status)) return true;
            return false;
        }
    }

    public static final class ChecklistItem {
        public String itemId = UUID.randomUUID().toString();
        public String name;
        public String status = "PENDING";
        public String kind = "TREATMENT";
        public String product = "";
        public String amount = "";
        public String unit = "";
        public String routeSite = "";
        public String lotSerial = "";
        public String expiration = "";
        public String withdrawalEnd = "";
        public String result = "";
        public String administeredBy = "";
        public String notes = "";
        public long recordedAt;

        public ChecklistItem(String name) { this.name = name; }
    }
}

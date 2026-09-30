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

    public boolean createProvisional(String provisionalIdentity, String visualTag, long at,
                                     List<String> plannedItems) {
        if (onScale != null || !ambiguousEpcs.isEmpty()) return false;
        String provisional = provisionalIdentity == null ? "" : provisionalIdentity.trim();
        if (provisional.isEmpty()) return false;
        onScale = Encounter.provisional(provisional, visualTag, at, plannedItems);
        return true;
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

    public TagResult reserveDeskTag(String epc, String antenna, double rssi, long at, String operator) {
        String clean = epc == null ? "" : epc.trim();
        if (inHeadChute == null || clean.isEmpty()) return TagResult.NO_CHUTE;
        if (isEpcInUse(clean, inHeadChute)) return TagResult.DUPLICATE;
        inHeadChute.reservedEpc = clean;
        inHeadChute.uhfIdentityState = "RESERVED";
        inHeadChute.reservedAt = at;
        inHeadChute.reservedBy = operator == null ? "" : operator;
        inHeadChute.tagAntenna = antenna == null ? "" : antenna;
        inHeadChute.tagRssi = rssi;
        inHeadChute.tagAudit.add("RESERVED|" + at + "|" + clean + "|" + inHeadChute.reservedBy);
        return TagResult.RESERVED;
    }

    public boolean markTagInstalled(long at, String operator) {
        if (inHeadChute == null || !"RESERVED".equals(inHeadChute.uhfIdentityState)
                || inHeadChute.reservedEpc.isEmpty()) return false;
        inHeadChute.epc = inHeadChute.reservedEpc;
        inHeadChute.uhfIdentityState = "INSTALLED";
        inHeadChute.installedAt = at;
        inHeadChute.installedBy = operator == null ? "" : operator;
        inHeadChute.tagAudit.add("INSTALLED|" + at + "|" + inHeadChute.epc + "|" + inHeadChute.installedBy);
        return true;
    }

    public boolean cancelReservedTag(String reason, long at, String operator) {
        if (inHeadChute == null || inHeadChute.reservedEpc.isEmpty()) return false;
        String old = inHeadChute.reservedEpc;
        inHeadChute.tagAudit.add("CANCELED|" + at + "|" + old + "|"
                + (operator == null ? "" : operator) + "|" + (reason == null ? "" : reason.trim()));
        inHeadChute.previousEpcs.add(old);
        inHeadChute.reservedEpc = "";
        inHeadChute.uhfIdentityState = inHeadChute.epc.isEmpty() ? "CANCELED" : "INSTALLED";
        return true;
    }

    private boolean isEpcInUse(String epc, Encounter target) {
        if (onScale != null && onScale != target && (epc.equals(onScale.epc) || epc.equals(onScale.reservedEpc))) return true;
        if (inHeadChute != null && inHeadChute != target && (epc.equals(inHeadChute.epc) || epc.equals(inHeadChute.reservedEpc))) return true;
        for (Encounter encounter : finished) {
            if (epc.equals(encounter.epc) || epc.equals(encounter.reservedEpc)) return true;
        }
        return false;
    }

    public enum ScanResult { CREATED, CONFIRMED, AMBIGUOUS, IGNORED }
    public enum TagResult { RESERVED, DUPLICATE, NO_CHUTE }

    public static final class Encounter {
        public String encounterId = UUID.randomUUID().toString();
        public String epc;
        public String provisionalIdentity = "";
        public String uhfIdentityState = "INSTALLED";
        public String reservedEpc = "";
        public long reservedAt;
        public String reservedBy = "";
        public long installedAt;
        public String installedBy = "";
        public String tagAntenna = "";
        public double tagRssi = -9999d;
        public final List<String> previousEpcs = new ArrayList<>();
        public final List<String> tagAudit = new ArrayList<>();
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
            this.epc = epc == null ? "" : epc;
            this.scannedAt = at;
            this.lastReadAt = at;
            if (plannedItems != null) for (String item : plannedItems) {
                String clean = item == null ? "" : item.trim();
                if (!clean.isEmpty() && !checklist.containsKey(clean)) checklist.put(clean, new ChecklistItem(clean));
            }
        }

        public static Encounter provisional(String provisionalIdentity, String visualTag, long at,
                                            List<String> plannedItems) {
            Encounter encounter = new Encounter("", at, plannedItems);
            encounter.provisionalIdentity = provisionalIdentity;
            encounter.visualTag = visualTag == null ? "" : visualTag.trim();
            encounter.uhfIdentityState = "NONE";
            return encounter;
        }

        public String displayIdentity() {
            if (!visualTag.isEmpty()) return visualTag;
            if (!epc.isEmpty()) return epc;
            return provisionalIdentity;
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

package com.herdmate.tagreapersentinel;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class AnimalLookupClient {

    private AnimalLookupClient() {
    }

    public static Result lookup(
            String apiBase,
            String sheetId,
            String epc,
            String operation
    ) {
        Result result = new Result();
        HttpURLConnection connection = null;

        try {
            String cleanBase = apiBase == null ? "" : apiBase.trim();
            while (cleanBase.endsWith("/")) {
                cleanBase = cleanBase.substring(0, cleanBase.length() - 1);
            }

            if (cleanBase.isEmpty()) {
                throw new IllegalArgumentException("Animal API URL is empty");
            }
            if (sheetId == null || sheetId.trim().isEmpty()) {
                throw new IllegalArgumentException("Google Sheet ID is empty");
            }
            if (epc == null || epc.trim().isEmpty()) {
                throw new IllegalArgumentException("EPC is empty");
            }

            URL url = new URL(cleanBase + "/animal/lookup");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");

            JSONObject request = new JSONObject();
            request.put("epc", epc.trim());
            request.put("herdmate_sheet_id", sheetId.trim());
            request.put("operation", operation == null ? "HerdMate" : operation.trim());

            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);

            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            result.httpCode = connection.getResponseCode();
            InputStream stream = result.httpCode >= 200 && result.httpCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            result.raw = readAll(stream);

            if (result.httpCode < 200 || result.httpCode >= 300) {
                result.success = false;
                result.error = extractError(result.raw, "HTTP " + result.httpCode);
                return result;
            }

            JSONObject root = new JSONObject(result.raw);
            result.success = true;
            result.found = root.optBoolean("found", false);

            if (result.found) {
                JSONObject animal = root.optJSONObject("animal");
                if (animal == null) {
                    result.success = false;
                    result.found = false;
                    result.error = "Server said found=true but returned no animal object";
                    return result;
                }
                result.animal = AnimalRecord.fromJson(animal);
                result.animal.lookupEpc = epc.trim();
                result.animal.cachedAt = System.currentTimeMillis();
            }

            return result;
        } catch (Exception exception) {
            result.success = false;
            result.error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            return result;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static String extractError(String raw, String fallback) {
        try {
            JSONObject object = new JSONObject(raw == null ? "" : raw);
            String detail = object.optString("detail", "").trim();
            if (!detail.isEmpty()) {
                return detail;
            }
            String error = object.optString("error", "").trim();
            if (!error.isEmpty()) {
                return error;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public static final class Result {
        public boolean success;
        public boolean found;
        public int httpCode;
        public String raw = "";
        public String error = "";
        public AnimalRecord animal;
    }

    public static final class AnimalRecord {
        public String lookupEpc = "";
        public String tag = "";
        public String displayId = "";
        public String uhf = "";
        public String source = "";
        public String status = "";
        public String sex = "";
        public String type = "";
        public String breed = "";
        public String color = "";
        public String birthDate = "";
        public String age = "";
        public String pasture = "";
        public String weight = "";
        public String birthWeight = "";
        public String dam = "";
        public String sire = "";
        public String dueDate = "";
        public String palpResult = "";
        public String monthsPregnant = "";
        public String bcs = "";
        public String disposition = "";
        public String notes = "";
        public String photo = "";
        public boolean ambiguous;
        public long cachedAt;

        public static AnimalRecord fromJson(JSONObject object) {
            AnimalRecord record = new AnimalRecord();
            record.lookupEpc = object.optString("lookup_epc", "");
            record.tag = object.optString("tag", "");
            record.displayId = object.optString("display_id", "");
            record.uhf = object.optString("uhf", "");
            record.source = object.optString("source", "");
            record.status = object.optString("status", "");
            record.sex = object.optString("sex", "");
            record.type = object.optString("type", "");
            record.breed = object.optString("breed", "");
            record.color = object.optString("color", "");
            record.birthDate = firstNonEmpty(
                    object.optString("birth_date", ""),
                    object.optString("date", "")
            );
            record.age = object.optString("age", "");
            record.pasture = object.optString("pasture", "");
            record.weight = object.optString("weight", "");
            record.birthWeight = object.optString("birth_weight", "");
            record.dam = firstNonEmpty(
                    object.optString("dam", ""),
                    object.optString("dam_tag", "")
            );
            record.sire = object.optString("sire", "");
            record.dueDate = object.optString("due_date", "");
            record.palpResult = object.optString("palp_result", "");
            record.monthsPregnant = object.optString("months_preg", "");
            record.bcs = firstNonEmpty(
                    object.optString("bcs", ""),
                    object.optString("dam_bcs", "")
            );
            record.disposition = object.optString("disposition", "");
            record.notes = object.optString("notes", "");
            record.photo = object.optString("photo", "");
            record.ambiguous = object.optBoolean("_ambiguous", false);
            record.cachedAt = object.optLong("cached_at", System.currentTimeMillis());
            return record;
        }

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("lookup_epc", lookupEpc);
                object.put("tag", tag);
                object.put("display_id", displayId);
                object.put("uhf", uhf);
                object.put("source", source);
                object.put("status", status);
                object.put("sex", sex);
                object.put("type", type);
                object.put("breed", breed);
                object.put("color", color);
                object.put("birth_date", birthDate);
                object.put("age", age);
                object.put("pasture", pasture);
                object.put("weight", weight);
                object.put("birth_weight", birthWeight);
                object.put("dam", dam);
                object.put("sire", sire);
                object.put("due_date", dueDate);
                object.put("palp_result", palpResult);
                object.put("months_preg", monthsPregnant);
                object.put("bcs", bcs);
                object.put("disposition", disposition);
                object.put("notes", notes);
                object.put("photo", photo);
                object.put("_ambiguous", ambiguous);
                object.put("cached_at", cachedAt);
            } catch (Exception ignored) {
            }
            return object;
        }

        public String primaryId() {
            return firstNonEmpty(tag, displayId, lookupEpc, uhf, "Unknown");
        }

        public String displayTitle() {
            String kind = firstNonEmpty(type, breed, sex, "ANIMAL").toUpperCase(Locale.US);
            String state = status == null || status.trim().isEmpty()
                    ? ""
                    : " — " + status.trim().toUpperCase(Locale.US);
            String warning = ambiguous ? " ⚠ MULTIPLE MATCHES" : "";
            return kind + " " + primaryId() + state + warning;
        }

        public String detailText() {
            StringBuilder builder = new StringBuilder();
            append(builder, "Tag", tag);
            append(builder, "Display ID", displayId);
            append(builder, "UHF EPC", firstNonEmpty(uhf, lookupEpc));
            append(builder, "Status", status);
            append(builder, "Sex", sex);
            append(builder, "Type", type);
            append(builder, "Breed", breed);
            append(builder, "Color", color);
            append(builder, "Birth date", birthDate);
            append(builder, "Age", age);
            append(builder, "Pasture", pasture);
            append(builder, "Weight", weight);
            append(builder, "Birth weight", birthWeight);
            append(builder, "Dam", dam);
            append(builder, "Sire", sire);
            append(builder, "Due date", dueDate);
            append(builder, "Palpation", palpResult);
            append(builder, "Months pregnant", monthsPregnant);
            append(builder, "BCS", bcs);
            append(builder, "Disposition", disposition);
            append(builder, "Source", source);
            append(builder, "Notes", notes);
            append(builder, "Photo", photo);
            if (ambiguous) {
                builder.append("\nWarning: multiple animals share this tag number.\n");
            }
            return builder.toString().trim();
        }

        private static void append(StringBuilder builder, String label, String value) {
            if (value != null && !value.trim().isEmpty()) {
                builder.append(label).append(": ").append(value.trim()).append('\n');
            }
        }

        private static String firstNonEmpty(String... values) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
            return "";
        }
    }
}

ackage com.herdmate.tagreapersentinel;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class SageWireContextClient {

    private static final String LOCATION_URL =
            "https://api.sagewire.dev/loc/stamp";

    private static final String WEATHER_URL =
            "https://weather.herdmate.ag/weather";

    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 15000;

    private SageWireContextClient() {
    }

    public static Result enrich(
            double latitude,
            double longitude,
            float accuracy,
            double altitude,
            float speed,
            float heading,
            long deviceTimestamp,
            String sessionId,
            String site
    ) {
        Result result = new Result();

        result.locationStatus = "PENDING";
        result.weatherStatus = "PENDING";

        try {
            JSONObject request =
                    new JSONObject();

            request.put(
                    "latitude",
                    latitude
            );

            request.put(
                    "longitude",
                    longitude
            );

            request.put(
                    "accuracy",
                    accuracy
            );

            request.put(
                    "altitude",
                    altitude
            );

            request.put(
                    "speed",
                    speed
            );

            request.put(
                    "heading",
                    heading
            );

            request.put(
                    "device_timestamp",
                    deviceTimestamp
            );

            request.put(
                    "session_id",
                    sessionId == null
                            ? ""
                            : sessionId
            );

            request.put(
                    "site",
                    site == null
                            ? ""
                            : site
            );

            HttpResult locationResult =
                    postJson(
                            LOCATION_URL,
                            request.toString()
                    );

            result.locationHttpCode =
                    locationResult.statusCode;

            result.locationRaw =
                    locationResult.body;

            if (locationResult.success) {
                result.locationStatus =
                        "COMPLETE";
            } else {
                result.locationStatus =
                        "FAILED";

                result.locationError =
                        locationResult.error;
            }
        } catch (Exception exception) {
            result.locationStatus =
                    "FAILED";

            result.locationError =
                    exception.getClass()
                            .getSimpleName()
                            + ": "
                            + exception.getMessage();
        }

        try {
            String url =
                    WEATHER_URL
                            + "?lat="
                            + URLEncoder.encode(
                            String.valueOf(latitude),
                            "UTF-8"
                    )
                            + "&lng="
                            + URLEncoder.encode(
                            String.valueOf(longitude),
                            "UTF-8"
                    );

            HttpResult weatherResult =
                    getJson(url);

            result.weatherHttpCode =
                    weatherResult.statusCode;

            result.weatherRaw =
                    weatherResult.body;

            if (weatherResult.success) {
                result.weatherStatus =
                        "COMPLETE";

                try {
                    JSONObject weather =
                            new JSONObject(
                                    weatherResult.body
                            );

                    result.temperature =
                            weather.optString(
                                    "temp",
                                    ""
                            );

                    result.condition =
                            weather.optString(
                                    "condition",
                                    ""
                            );

                    result.wind =
                            weather.optString(
                                    "wind",
                                    ""
                            );

                    result.humidity =
                            weather.optString(
                                    "humidity",
                                    ""
                            );

                    result.weatherCached =
                            weather.optBoolean(
                                    "cached",
                                    false
                            );

                    result.weatherFetchedAt =
                            weather.optString(
                                    "fetched_at",
                                    ""
                            );
                } catch (Exception parseException) {
                    result.weatherStatus =
                            "FAILED";

                    result.weatherError =
                            "Weather response parse failed: "
                                    + parseException
                                    .getMessage();
                }
            } else {
                result.weatherStatus =
                        "FAILED";

                result.weatherError =
                        weatherResult.error;
            }
        } catch (Exception exception) {
            result.weatherStatus =
                    "FAILED";

            result.weatherError =
                    exception.getClass()
                            .getSimpleName()
                            + ": "
                            + exception.getMessage();
        }

        return result;
    }

    private static HttpResult postJson(
            String endpoint,
            String body
    ) {
        HttpURLConnection connection =
                null;

        try {
            URL url =
                    new URL(endpoint);

            connection =
                    (HttpURLConnection)
                            url.openConnection();

            connection.setRequestMethod(
                    "POST"
            );

            connection.setConnectTimeout(
                    CONNECT_TIMEOUT_MS
            );

            connection.setReadTimeout(
                    READ_TIMEOUT_MS
            );

            connection.setDoOutput(true);

            connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );

            connection.setRequestProperty(
                    "Accept",
                    "application/json"
            );

            byte[] bytes =
                    body.getBytes(
                            StandardCharsets.UTF_8
                    );

            connection.setFixedLengthStreamingMode(
                    bytes.length
            );

            try (
                    OutputStream output =
                            connection
                                    .getOutputStream()
            ) {
                output.write(bytes);
                output.flush();
            }

            return readResponse(connection);
        } catch (Exception exception) {
            HttpResult result =
                    new HttpResult();

            result.success = false;

            result.statusCode = -1;

            result.error =
                    exception.getClass()
                            .getSimpleName()
                            + ": "
                            + exception.getMessage();

            return result;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpResult getJson(
            String endpoint
    ) {
        HttpURLConnection connection =
                null;

        try {
            URL url =
                    new URL(endpoint);

            connection =
                    (HttpURLConnection)
                            url.openConnection();

            connection.setRequestMethod(
                    "GET"
            );

            connection.setConnectTimeout(
                    CONNECT_TIMEOUT_MS
            );

            connection.setReadTimeout(
                    READ_TIMEOUT_MS
            );

            connection.setRequestProperty(
                    "Accept",
                    "application/json"
            );

            return readResponse(connection);
        } catch (Exception exception) {
            HttpResult result =
                    new HttpResult();

            result.success = false;

            result.statusCode = -1;

            result.error =
                    exception.getClass()
                            .getSimpleName()
                            + ": "
                            + exception.getMessage();

            return result;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpResult readResponse(
            HttpURLConnection connection
    ) throws Exception {

        HttpResult result =
                new HttpResult();

        int statusCode =
                connection.getResponseCode();

        result.statusCode =
                statusCode;

        result.success =
                statusCode >= 200
                        && statusCode < 300;

        InputStream stream;

        if (result.success) {
            stream =
                    connection.getInputStream();
        } else {
            stream =
                    connection.getErrorStream();
        }

        result.body =
                readStream(stream);

        if (!result.success) {
            result.error =
                    "HTTP "
                            + statusCode
                            + (
                            result.body == null
                                    || result.body.isEmpty()
                                    ? ""
                                    : ": " + result.body
                    );
        }

        return result;
    }

    private static String readStream(
            InputStream stream
    ) throws Exception {

        if (stream == null) {
            return "";
        }

        StringBuilder output =
                new StringBuilder();

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        stream,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {
            String line;

            while (
                    (line = reader.readLine())
                            != null
            ) {
                output.append(line);
            }
        }

        return output.toString();
    }

    private static class HttpResult {
        boolean success;
        int statusCode;
        String body = "";
        String error = "";
    }

    public static class Result {
        public String locationStatus = "";
        public int locationHttpCode;
        public String locationRaw = "";
        public String locationError = "";

        public String weatherStatus = "";
        public int weatherHttpCode;
        public String weatherRaw = "";
        public String weatherError = "";

        public String temperature = "";
        public String condition = "";
        public String wind = "";
        public String humidity = "";
        public boolean weatherCached;
        public String weatherFetchedAt = "";

        public boolean isComplete() {
            return "COMPLETE".equals(
                    locationStatus
            )
                    && "COMPLETE".equals(
                    weatherStatus
            );
        }

        public boolean needsRetry() {
            return !"COMPLETE".equals(
                    locationStatus
            )
                    || !"COMPLETE".equals(
                    weatherStatus
            );
        }
    }
              }

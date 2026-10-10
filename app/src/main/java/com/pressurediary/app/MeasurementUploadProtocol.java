package com.pressurediary.app;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TimeZone;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

/** HTTPS-only wire protocol. The platform's certificate and hostname verification stays enabled. */
final class MeasurementUploadProtocol {
    interface Connections {
        HttpsURLConnection open(URL url) throws IOException;
    }

    interface SyncGuard { void check() throws IOException; }

    private final Connections connections;

    MeasurementUploadProtocol() {
        this(url -> (HttpsURLConnection) url.openConnection());
    }

    MeasurementUploadProtocol(Connections connections) {
        this.connections = connections;
    }

    static String normalizeBaseUrl(String value) {
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                    uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
                    uri.getPort() == 0 || uri.getPort() > 65535 || value.length() > 512) {
                throw new IllegalArgumentException("请填写 HTTPS 服务器地址，例如 https://bp.282412.xyz");
            }
            String path = uri.getRawPath();
            if (path == null) path = "";
            // Do not reinterpret encoded separators or relative path segments.
            if (path.contains("%") || path.contains("\\") || !uri.normalize().equals(uri)) {
                throw new IllegalArgumentException("服务器地址不能包含转义路径或相对路径");
            }
            for (String segment : path.split("/")) {
                if (segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("服务器地址不能包含相对路径");
                }
            }
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return "https://" + uri.getRawAuthority() + path;
        } catch (URISyntaxException | NullPointerException error) {
            throw new IllegalArgumentException("请填写完整的 HTTPS 服务器地址");
        }
    }

    static String normalizeToken(String value) {
        if (value == null) throw new IllegalArgumentException("请填写服务器生成的上传密钥");
        String token = value.trim();
        if (token.length() < 32 || token.length() > 256) {
            throw new IllegalArgumentException("上传密钥长度不正确，请完整复制服务器生成的密钥");
        }
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) <= 32 || token.charAt(i) >= 127) {
                throw new IllegalArgumentException("上传密钥包含无效字符");
            }
        }
        return token;
    }

    static String recordId(String installationId, long localId) {
        if (localId <= 0) throw new IllegalArgumentException("Record was not saved locally");
        UUID.fromString(installationId);
        return UUID.nameUUIDFromBytes((installationId + ":" + localId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    static JSONObject payload(String recordId, Measurement measurement, String source, TimeZone zone)
            throws JSONException {
        UUID.fromString(recordId);
        if (measurement.systolic < 50 || measurement.systolic > 260 ||
                measurement.diastolic < 30 || measurement.diastolic > 160 ||
                measurement.diastolic >= measurement.systolic || measurement.pulse < 30 ||
                measurement.pulse > 220 || measurement.measuredAt < -2208988800000L ||
                measurement.measuredAt > 4102444800000L || measurement.note.length() > 2048 ||
                !("manual".equals(source) || "camera".equals(source))) {
            throw new IllegalArgumentException("Unsupported measurement");
        }
        JSONObject object = new JSONObject();
        object.put("schemaVersion", 1);
        object.put("recordId", recordId);
        object.put("systolic", measurement.systolic);
        object.put("diastolic", measurement.diastolic);
        object.put("pulse", measurement.pulse);
        object.put("measuredAt", measurement.measuredAt);
        object.put("note", measurement.note);
        object.put("timeZone", zone.getID());
        object.put("utcOffsetMinutes", zone.getOffset(measurement.measuredAt) / 60000);
        object.put("source", source);
        return object;
    }

    void upload(String baseUrl, String token, String recordId, Measurement measurement, String source,
                TimeZone zone) throws IOException, JSONException {
        byte[] body = payload(recordId, measurement, source, zone).toString().getBytes(StandardCharsets.UTF_8);
        if (body.length > 16 * 1024) throw new IOException("Request is too large");
        HttpsURLConnection connection = open(baseUrl, token, "/api/v1/measurements");
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            if (status != 200 && status != 201) throw new IOException("Upload rejected (HTTP " + status + ")");
            JSONObject reply = readReply(connection);
            if (!Boolean.TRUE.equals(reply.opt("saved")) || !recordId.equals(reply.optString("recordId"))) {
                throw new IOException("Invalid upload acknowledgement");
            }
        } finally { connection.disconnect(); }
    }

    String testConnection(String baseUrl, String token) throws IOException, JSONException {
        HttpsURLConnection connection = open(baseUrl, token, "/api/v1/device");
        try {
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new IOException("上传密钥不正确或已被停用");
            if (status != 200) throw new IOException("服务器暂不可用（HTTP " + status + "）");
            JSONObject reply = readReply(connection);
            try { UUID.fromString(reply.getString("deviceId")); }
            catch (IllegalArgumentException error) { throw new IOException("服务器响应不正确"); }
            String member = reply.getString("memberName");
            if (member.trim().isEmpty() || member.length() > 80) throw new IOException("服务器响应不正确");
            return member;
        } finally { connection.disconnect(); }
    }

    static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    private static byte[] page(String installationId, MeasurementSnapshot snapshot, int index) throws JSONException {
        JSONArray records = new JSONArray();
        int last = Math.min(snapshot.records.size(), (index + 1) * 25);
        for (int i = index * 25; i < last; i++) {
            Measurement item = snapshot.records.get(i);
            if (!("manual".equals(item.source) || "camera".equals(item.source) ||
                    "import".equals(item.source) || "legacy".equals(item.source))) {
                throw new IllegalArgumentException("Unsupported capture source");
            }
            JSONObject row = payload(recordId(installationId, item.id), item, "manual", TimeZone.getTimeZone("UTC"));
            row.put("schemaVersion", 2);
            row.put("localId", item.id);
            row.put("source", item.source);
            row.put("timeZone", item.timeZone);
            row.put("utcOffsetMinutes", item.utcOffsetMinutes);
            records.put(row);
        }
        return new JSONObject().put("records", records).toString().getBytes(StandardCharsets.UTF_8);
    }

    void sync(String baseUrl, String token, String installationId, MeasurementSnapshot snapshot, SyncGuard guard)
            throws IOException, JSONException {
        UUID.fromString(installationId);
        int pages = (snapshot.records.size() + 24) / 25;
        StringBuilder fingerprints = new StringBuilder();
        for (int i = 0; i < pages; i++) fingerprints.append(sha256(page(installationId, snapshot, i))).append('\n');
        String digest = sha256(fingerprints.toString().getBytes(StandardCharsets.US_ASCII));
        byte[] metadata = new JSONObject().put("installationId", installationId)
                .put("version", snapshot.version).put("recordCount", snapshot.records.size())
                .put("highWatermark", snapshot.highWatermark).put("digest", digest)
                .toString().getBytes(StandardCharsets.UTF_8);
        guard.check();
        JSONObject start = request(baseUrl, token, "/api/v2/snapshots", "POST", metadata);
        checkRevision(start, snapshot, digest);
        if (Boolean.TRUE.equals(start.opt("committed"))) {
            checkCommit(start, snapshot, digest);
            return;
        }
        if (!Boolean.FALSE.equals(start.opt("committed"))) throw new IOException("Invalid snapshot acknowledgement");
        String syncId = start.getString("syncId");
        try { UUID.fromString(syncId); } catch (IllegalArgumentException error) { throw new IOException("Invalid snapshot ID"); }
        for (int i = 0; i < pages; i++) {
            guard.check();
            JSONObject reply = request(baseUrl, token, "/api/v2/snapshots/" + syncId + "/pages/" + i,
                    "PUT", page(installationId, snapshot, i));
            if (!Boolean.TRUE.equals(reply.opt("staged")) || !syncId.equals(reply.optString("syncId")) ||
                    !(reply.opt("pageIndex") instanceof Number) || reply.getLong("pageIndex") != i) {
                throw new IOException("Invalid page acknowledgement");
            }
        }
        guard.check();
        checkCommit(request(baseUrl, token, "/api/v2/snapshots/" + syncId + "/commit", "POST", null), snapshot, digest);
    }

    private static void checkRevision(JSONObject reply, MeasurementSnapshot snapshot, String digest) throws IOException, JSONException {
        if (!(reply.opt("version") instanceof Number) || reply.getLong("version") != snapshot.version ||
                !digest.equals(reply.optString("digest"))) throw new IOException("Invalid snapshot revision");
    }

    private static void checkCommit(JSONObject reply, MeasurementSnapshot snapshot, String digest) throws IOException, JSONException {
        checkRevision(reply, snapshot, digest);
        if (!Boolean.TRUE.equals(reply.opt("committed")) || !(reply.opt("recordCount") instanceof Number) ||
                reply.getLong("recordCount") != snapshot.records.size()) throw new IOException("Invalid commit acknowledgement");
    }

    private JSONObject request(String baseUrl, String token, String path, String method, byte[] body)
            throws IOException, JSONException {
        if (body != null && body.length > 512 * 1024) throw new IOException("Request is too large");
        HttpsURLConnection connection = open(baseUrl, token, path);
        try {
            connection.setRequestMethod(method);
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status != 200 && status != 201) throw new IOException("Synchronization rejected (HTTP " + status + ")");
            return readReply(connection);
        } finally { connection.disconnect(); }
    }

    private HttpsURLConnection open(String baseUrl, String token, String path) throws IOException {
        String base = normalizeBaseUrl(baseUrl);
        String key = normalizeToken(token);
        HttpsURLConnection connection = connections.open(new URL(base + path));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private static JSONObject readReply(HttpsURLConnection connection) throws IOException, JSONException {
        String type = connection.getContentType();
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            throw new IOException("Expected JSON response");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = connection.getInputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (bytes.size() + read > 4096) throw new IOException("Response is too large");
                bytes.write(buffer, 0, read);
            }
        }
        return new JSONObject(bytes.toString("UTF-8"));
    }
}

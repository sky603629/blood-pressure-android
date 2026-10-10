package com.pressurediary.app;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.TimeZone;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

/** Checks the actual transport boundary, including credential forwarding and response validation. */
public final class UploadProtocolTests {
    private static int checks;
    private static final String INSTALLATION = "8b6ca901-bb71-46f6-8103-bd097bbf2f44";
    private static final String TOKEN = "host-only-test-key-123456789012345678901";
    private static final Measurement READING = new Measurement(7, 128, 76, 72, 1791591300123L, "早餐后\n休息五分钟");

    public static void main(String[] args) throws Exception {
        testValidation();
        testPayload();
        testTransport();
        if (args.length == 1) integration(args[0], System.getenv("BP_TEST_TOKEN"));
        System.out.println("PASS: " + checks + " assertions (HTTPS upload protocol)");
    }

    private static void testValidation() throws Exception {
        require(MeasurementUploadProtocol.normalizeBaseUrl(" HTTPS://bp.example.com/ ").equals("https://bp.example.com"), "HTTPS normalized");
        require(MeasurementUploadProtocol.normalizeBaseUrl("https://bp.example.com:8443/family/").equals("https://bp.example.com:8443/family"), "proxy path retained");
        for (String value : new String[]{"http://bp.example.com", "bp.example.com", "https://", "https://u:p@bp.example.com",
                "https://bp.example.com?key=secret", "https://bp.example.com/#x", "https://bp.example.com/../other",
                "https://bp.example.com/%2e%2e", "https://bp.example.com:0", "https://bp.example.com:70000", "https://bp.example.com/\n"}) {
            // Leading/trailing whitespace is intentionally trimmed. Embedded CR/LF must fail.
            if (value.endsWith("/\n")) value = "https://bp.example.com/x\ny";
            String invalid = value;
            rejects(() -> MeasurementUploadProtocol.normalizeBaseUrl(invalid), "unsafe base URL: " + invalid);
        }
        rejects(() -> MeasurementUploadProtocol.normalizeToken("short"), "short token");
        rejects(() -> MeasurementUploadProtocol.normalizeToken(TOKEN + "\r\nX-Other: value"), "header injection");
        rejects(() -> MeasurementUploadProtocol.normalizeToken(TOKEN + "中"), "non-ASCII token");
        String id = MeasurementUploadProtocol.recordId(INSTALLATION, READING.id);
        require(UUID.fromString(id).toString().equals(id), "UUID wire identifier");
        require(id.equals(MeasurementUploadProtocol.recordId(INSTALLATION, READING.id)), "stable duplicate identifier");
        require(!id.equals(MeasurementUploadProtocol.recordId(INSTALLATION, 8)), "different local rows stay distinct");
        require(!id.equals(MeasurementUploadProtocol.recordId("95ae0cde-37a1-4cc9-b076-6c6b940ad1e0", 7)), "installations stay distinct");
        rejects(() -> MeasurementUploadProtocol.recordId(INSTALLATION, -1), "unsaved record rejected");
    }

    private static void testPayload() throws Exception {
        String id = MeasurementUploadProtocol.recordId(INSTALLATION, READING.id);
        JSONObject body = MeasurementUploadProtocol.payload(id, READING, "camera", TimeZone.getTimeZone("Asia/Shanghai"));
        require(body.length() == 10 && body.getInt("schemaVersion") == 1, "only contract fields");
        require(body.getInt("systolic") == 128 && body.getInt("diastolic") == 76 && body.getInt("pulse") == 72, "exact numeric values");
        require(body.getLong("measuredAt") == READING.measuredAt, "millisecond timestamp unchanged");
        require(body.getString("note").equals(READING.note), "Unicode and newline retained");
        require(body.getInt("utcOffsetMinutes") == 480 && body.getString("timeZone").equals("Asia/Shanghai"), "phone timezone encoded");
        require(body.getString("source").equals("camera"), "camera source preserved");
        rejects(() -> MeasurementUploadProtocol.payload(id, READING, "import", TimeZone.getDefault()), "import is not a new-save source");
        rejects(() -> MeasurementUploadProtocol.payload(id, new Measurement(7, 80, 90, 70, READING.measuredAt, ""), "manual", TimeZone.getDefault()), "bad readings rejected");
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        Measurement winter = new Measurement(8, 120, 80, 70, 1767225600000L, "");
        Measurement summer = new Measurement(9, 120, 80, 70, 1782864000000L, "");
        require(MeasurementUploadProtocol.payload(id, winter, "manual", ny).getInt("utcOffsetMinutes") == -300, "winter offset uses measurement date");
        require(MeasurementUploadProtocol.payload(id, summer, "manual", ny).getInt("utcOffsetMinutes") == -240, "summer offset uses measurement date");
    }

    private static void testTransport() throws Exception {
        String id = MeasurementUploadProtocol.recordId(INSTALLATION, READING.id);
        FakeConnection[] captured = new FakeConnection[1];
        MeasurementUploadProtocol protocol = new MeasurementUploadProtocol(url -> captured[0] = new FakeConnection(url,
                201, "{\"saved\":true,\"recordId\":\"" + id + "\",\"created\":true}"));
        protocol.upload("https://bp.example.com", TOKEN, id, READING, "manual", TimeZone.getTimeZone("Asia/Shanghai"));
        FakeConnection connection = captured[0];
        require(connection.getURL().toString().equals("https://bp.example.com/api/v1/measurements"), "upload route");
        require("POST".equals(connection.getRequestMethod()), "POST transport");
        require(connection.getRequestProperty("Authorization").equals("Bearer " + TOKEN), "key in authorization header");
        require(!connection.getURL().toString().contains(TOKEN), "key never in URL");
        require(!connection.getInstanceFollowRedirects(), "redirect credential forwarding forbidden");
        require(connection.getConnectTimeout() == 7000 && connection.getReadTimeout() == 7000, "bounded network waits");
        require(connection.disconnected, "connection closed");
        require(new JSONObject(connection.body.toString("UTF-8")).getString("recordId").equals(id), "actual body sent");

        for (int status : new int[]{301, 302, 307, 308, 401, 403, 409, 500}) {
            MeasurementUploadProtocol failed = new MeasurementUploadProtocol(url -> captured[0] = new FakeConnection(url, status, ""));
            fails(() -> failed.upload("https://bp.example.com", TOKEN, id, READING, "manual", TimeZone.getDefault()), "HTTP failure cannot report success");
            require(captured[0].disconnected, "rejected connection closed");
        }
        for (String body : new String[]{"{\"saved\":false,\"recordId\":\""+id+"\"}", "{\"saved\":\"true\",\"recordId\":\""+id+"\"}",
                "{\"saved\":true,\"recordId\":\"another-record\"}", "{}", "not-json", new String(new char[4097]).replace('\0', 'x')}) {
            MeasurementUploadProtocol failed = new MeasurementUploadProtocol(url -> new FakeConnection(url, 200, body));
            fails(() -> failed.upload("https://bp.example.com", TOKEN, id, READING, "manual", TimeZone.getDefault()), "invalid acknowledgement rejected");
        }
        MeasurementUploadProtocol pairing = new MeasurementUploadProtocol(url -> captured[0] = new FakeConnection(url, 200,
                "{\"deviceId\":\"" + INSTALLATION + "\",\"memberName\":\"妈妈\"}"));
        require(pairing.testConnection("https://bp.example.com", TOKEN).equals("妈妈"), "pairing confirms family member");
        require(captured[0].getRequestMethod().equals("GET") && captured[0].body.size() == 0, "connection test uploads no readings");
    }

    private static void integration(String base, String token) throws Exception {
        MeasurementUploadProtocol protocol = new MeasurementUploadProtocol();
        require(protocol.testConnection(base, token).equals("联调测试"), "real HTTPS pairing");
        String id = MeasurementUploadProtocol.recordId(INSTALLATION, READING.id);
        protocol.upload(base, token, id, READING, "camera", TimeZone.getTimeZone("Asia/Shanghai"));
        protocol.upload(base, token, id, READING, "camera", TimeZone.getTimeZone("Asia/Shanghai"));
        require(true, "real HTTPS duplicate acknowledged");
    }

    interface Action { void run() throws Exception; }
    private static void rejects(Action action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException error) { rejected = true; }
        require(rejected, message);
    }
    private static void fails(Action action, String message) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (IOException | org.json.JSONException error) { failed = true; }
        require(failed, message);
    }
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeConnection extends HttpsURLConnection {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final int status;
        final String reply;
        boolean disconnected;
        FakeConnection(URL url, int status, String reply) { super(url); this.status = status; this.reply = reply; }
        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return "test-only"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
        @Override public OutputStream getOutputStream() { return body; }
        @Override public int getResponseCode() { return status; }
        @Override public String getContentType() { return "application/json; charset=utf-8"; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(reply.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }
}

package com.pressurediary.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/** Regression checks for file compatibility and browsing many chart records. */
public final class RegressionTests {
    private static int checks;
    private static final String HEADER = "测量时间,时段,收缩压(mmHg),舒张压(mmHg),脉搏(次/分),备注\r\n";

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        testFileCompatibility();
        testViewport();
        System.out.println("PASS: " + checks + " assertions (CSV / JSON compatibility, chart navigation)");
    }

    private static void testFileCompatibility() throws Exception {
        long time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                .parse("2026-10-10 08:15:22.123").getTime();
        Measurement a = new Measurement(1, 128, 76, 68, time, "起床后，休息5分钟\n备注含\"引号\"");
        Measurement b = new Measurement(2, 145, 92, 75, time + 11 * 3600000 - 123, "晚上");
        List<Measurement> original = Arrays.asList(a, b);
        ByteArrayOutputStream csv = new ByteArrayOutputStream();
        MeasurementFileTransfer.writeReport(csv, original);
        String text = csv.toString("UTF-8");
        require(text.startsWith("\uFEFF" + HEADER), "UTF-8 BOM and CSV columns preserved");
        require(text.contains("测量于 2026-10-10 08:15:22.123,上午"), "descriptive time and morning");
        require(text.contains("测量于 2026-10-10 19:15:22,晚上"), "evening and integer-second time");
        require(!text.contains("########"), "report contains readable date");
        MeasurementFileTransfer.ImportBatch roundTrip = read(csv.toByteArray(), Collections.emptyList());
        require(roundTrip.measurements.size() == 2 && roundTrip.invalidCount == 0, "new CSV round trip");
        equal(a, roundTrip.measurements.get(0));
        equal(b, roundTrip.measurements.get(1));
        require(read(csv.toByteArray(), original).duplicateCount == 2, "reimport skips all duplicates");
        require(read(csv.toByteArray(), Collections.singletonList(a)).measurements.size() == 1, "merges only new records");

        String old = HEADER + "2026-10-09 08:15,上午,118,72,65,老版本分钟格式\r\n" +
                "2026-10-09 15:30:45,下午,125,78,70,老版本秒格式\r\n" +
                "2026-10-09 19:30:45.123,晚上,130,82,72,老版本毫秒格式\r\n";
        MeasurementFileTransfer.ImportBatch legacy = read(old, Collections.emptyList());
        require(legacy.measurements.size() == 3 && legacy.invalidCount == 0, "all 1.0.0 date formats accepted");
        require(legacy.measurements.get(2).measuredAt % 1000 == 123, "legacy milliseconds retained");
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        MeasurementFileTransfer.writeBackup(backup, original);
        require(backup.toString("UTF-8").contains("\"formatVersion\": 1"), "JSON version unchanged");
        MeasurementFileTransfer.ImportBatch restored = read(backup.toByteArray(), Collections.emptyList());
        require(restored.measurements.size() == 2, "JSON restores every row");
        equal(a, restored.measurements.get(0));
        equal(b, restored.measurements.get(1));
        require(read(backup.toByteArray(), original).duplicateCount == 2, "JSON duplicates ignored");
        Measurement sameDataNewId = new Measurement(999, a.systolic, a.diastolic, a.pulse, a.measuredAt, a.note);
        require(MeasurementFileTransfer.key(a).equals(MeasurementFileTransfer.key(sameDataNewId)), "dedup independent of local id");

        String invalid = HEADER +
                "2026-02-30 08:15,上午,120,80,70,无效日期\r\n" +
                "2026-10-09 08:15garbage,上午,120,80,70,时间尾部无效\r\n" +
                "2026-10-09 08:15,上午,100,110,70,低压不合理\r\n" +
                "2026-10-09 08:15,上午,120,80,5,脉搏不合理\r\n" +
                "2026-10-09 08:15,上午,120,80,70,保留有效记录\r\n" +
                "2026-10-09 08:15,上午,120,80,70,保留有效记录\r\n";
        MeasurementFileTransfer.ImportBatch mixed = read(invalid, Collections.emptyList());
        require(mixed.invalidCount == 4 && mixed.duplicateCount == 1 && mixed.measurements.size() == 1,
                "invalid rows and duplicates do not displace valid rows");
        boolean badHeader = false;
        try { read("时间,高压,低压\n", Collections.emptyList()); }
        catch (MeasurementFileTransfer.FormatException error) { badHeader = true; }
        require(badHeader, "reject unrelated CSV");
        boolean badQuote = false;
        try { read(HEADER + "\"未闭合", Collections.emptyList()); }
        catch (MeasurementFileTransfer.FormatException error) { badQuote = true; }
        require(badQuote, "reject incomplete quoted CSV");
    }

    private static void testViewport() {
        ChartViewport window = new ChartViewport();
        window.reset(0, 12);
        require(window.first() == -1 && window.last() == -1, "empty chart");
        window.zoom(2, .5);
        require(!window.canZoomIn() && !window.canZoomOut(), "empty chart disables zoom");
        window.reset(1, 12);
        require(window.first() == 0 && window.last() == 0, "one record visible");
        window.reset(1000, 12);
        require(window.first() == 988 && window.last() == 999, "latest 12 of 1000 selected by default");
        window.pan(-12);
        require(window.first() == 976 && window.last() == 987, "swipe reaches older records");
        window.pan(-100000);
        require(window.first() == 0, "pan stops at oldest record");
        window.pan(100000);
        require(window.last() == 999, "pan stops at newest record");
        window.centerOn(500);
        double fixed = window.start() + window.span() * .25;
        window.zoom(2, .25);
        require(Math.abs(fixed - (window.start() + window.span() * .25)) < 1e-8, "pinch anchor stays at same record");
        window.zoom(10000, .5);
        require(window.span() == 3 && !window.canZoomIn(), "minimum readable window has three records");
        window.ensureVisible(0);
        require(window.first() == 0, "previous record navigation reaches oldest");
        window.ensureVisible(999);
        require(window.last() == 999, "next record navigation reaches newest");
        window.showAll();
        require(window.first() == 0 && window.last() == 999 && !window.canZoomOut(), "overview exposes all records");
        double start = window.start();
        window.pan(Double.NaN);
        window.pan(Double.POSITIVE_INFINITY);
        window.zoom(0, .5);
        require(window.start() == start, "invalid gesture input does not damage viewport");

        Random random = new Random(82);
        window.reset(180, 12);
        for (int i = 0; i < 200; i++) {
            if (i % 3 == 0) window.pan(random.nextDouble() * 80 - 40);
            else if (i % 3 == 1) window.zoom(.5 + random.nextDouble() * 2, random.nextDouble());
            else window.ensureVisible(random.nextInt(180));
            require(window.first() >= 0 && window.last() < 180 && window.first() <= window.last(),
                    "visible indexes stay valid after gesture " + i);
            require(window.start() >= -.50001 && window.end() <= 179.50001 && window.span() >= 3,
                    "gesture bounds stay inside dataset " + i);
        }
    }

    private static MeasurementFileTransfer.ImportBatch read(String text, List<Measurement> existing) throws Exception {
        return read(text.getBytes(StandardCharsets.UTF_8), existing);
    }
    private static MeasurementFileTransfer.ImportBatch read(byte[] bytes, List<Measurement> existing) throws Exception {
        return MeasurementFileTransfer.readImport(new ByteArrayInputStream(bytes), existing);
    }
    private static void equal(Measurement expected, Measurement actual) {
        require(expected.systolic == actual.systolic && expected.diastolic == actual.diastolic &&
                expected.pulse == actual.pulse && expected.measuredAt == actual.measuredAt &&
                expected.note.equals(actual.note), "all measurements and notes preserved exactly");
    }
    private static void require(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }
}

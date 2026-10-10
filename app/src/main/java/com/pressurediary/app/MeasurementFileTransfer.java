package com.pressurediary.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class MeasurementFileTransfer {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_IMPORT_CHARS = 10 * 1024 * 1024;
    private static final String[] CSV_HEADER = {
            "测量时间", "时段", "收缩压(mmHg)", "舒张压(mmHg)", "脉搏(次/分)", "备注"
    };

    private MeasurementFileTransfer() {}

    static void writeReport(OutputStream output, List<Measurement> measurements) throws IOException {
        output.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        writeCsvRow(writer, CSV_HEADER);
        SimpleDateFormat dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        SimpleDateFormat preciseDateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
        for (Measurement item : measurements) {
            writeCsvRow(writer, new String[]{
                    "测量于 " + (item.measuredAt % 1000L == 0 ? dateTime : preciseDateTime)
                            .format(new Date(item.measuredAt)), period(item.measuredAt),
                    String.valueOf(item.systolic), String.valueOf(item.diastolic),
                    String.valueOf(item.pulse), item.note
            });
        }
        writer.flush();
    }

    static void writeBackup(OutputStream output, List<Measurement> measurements) throws IOException {
        JSONObject backup = new JSONObject();
        JSONArray rows = new JSONArray();
        try {
            backup.put("format", "blood-pressure-diary");
            backup.put("formatVersion", FORMAT_VERSION);
            backup.put("exportedAt", System.currentTimeMillis());
            for (Measurement item : measurements) {
                JSONObject row = new JSONObject();
                row.put("systolic", item.systolic);
                row.put("diastolic", item.diastolic);
                row.put("pulse", item.pulse);
                row.put("measuredAt", item.measuredAt);
                row.put("note", item.note);
                rows.put(row);
            }
            backup.put("measurements", rows);
        } catch (JSONException error) {
            throw new IOException("无法生成备份文件", error);
        }
        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        try {
            writer.write(backup.toString(2));
        } catch (JSONException error) {
            throw new IOException("无法生成备份文件", error);
        }
        writer.flush();
    }

    static ImportBatch readImport(InputStream input, List<Measurement> existing)
            throws IOException, FormatException {
        String contents = readContents(input);
        String trimmed = contents.trim();
        if (trimmed.isEmpty()) throw new FormatException("文件内容为空");
        Set<String> knownRows = new HashSet<>();
        boolean json = trimmed.startsWith("{");
        for (Measurement item : existing) knownRows.add(key(item));
        return json ? readJson(trimmed, knownRows) : readCsv(contents, knownRows);
    }

    static String key(Measurement item) {
        return key(item, item.measuredAt);
    }

    private static String key(Measurement item, long measuredAt) {
        String note = item.note == null ? "" : item.note.trim();
        return item.systolic + "," + item.diastolic + "," + item.pulse + "," +
                measuredAt + "," + note.length() + ":" + note;
    }

    private static String readContents(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder contents = new StringBuilder();
        char[] buffer = new char[8192];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (contents.length() + count > MAX_IMPORT_CHARS) {
                throw new IOException("文件过大，请选择小于 10 MB 的备份或报告");
            }
            contents.append(buffer, 0, count);
        }
        if (contents.length() > 0 && contents.charAt(0) == '\uFEFF') contents.deleteCharAt(0);
        return contents.toString();
    }

    private static ImportBatch readJson(String contents, Set<String> knownRows) throws FormatException {
        try {
            JSONObject backup = new JSONObject(contents);
            if (!"blood-pressure-diary".equals(backup.optString("format"))) {
                throw new FormatException("这不是血压日记备份文件");
            }
            if (backup.optInt("formatVersion", -1) != FORMAT_VERSION) {
                throw new FormatException("备份文件版本暂不支持");
            }
            JSONArray rows = backup.getJSONArray("measurements");
            List<Measurement> accepted = new ArrayList<>();
            int invalid = 0;
            int duplicates = 0;
            for (int i = 0; i < rows.length(); i++) {
                try {
                    JSONObject row = rows.getJSONObject(i);
                    Measurement item = new Measurement(0,
                            row.getInt("systolic"), row.getInt("diastolic"), row.getInt("pulse"),
                            row.getLong("measuredAt"), row.optString("note", ""));
                    if (!isValid(item)) {
                        invalid++;
                    } else if (!knownRows.add(key(item))) {
                        duplicates++;
                    } else {
                        accepted.add(item);
                    }
                } catch (JSONException | NumberFormatException error) {
                    invalid++;
                }
            }
            return new ImportBatch(accepted, invalid, duplicates);
        } catch (JSONException error) {
            throw new FormatException("备份文件格式无法识别");
        }
    }

    private static ImportBatch readCsv(String contents, Set<String> knownRows) throws FormatException {
        List<List<String>> table = parseCsv(contents);
        if (table.isEmpty() || table.get(0).size() < CSV_HEADER.length) {
            throw new FormatException("报告缺少必要的列，请选择本应用导出的 CSV 文件");
        }
        for (int i = 0; i < CSV_HEADER.length; i++) {
            if (!CSV_HEADER[i].equals(table.get(0).get(i).trim())) {
                throw new FormatException("报告列名不匹配，请选择本应用导出的 CSV 文件");
            }
        }

        List<Measurement> accepted = new ArrayList<>();
        int invalid = 0;
        int duplicates = 0;
        for (int i = 1; i < table.size(); i++) {
            List<String> row = table.get(i);
            if (row.size() == 1 && row.get(0).trim().isEmpty()) continue;
            try {
                if (row.size() < CSV_HEADER.length) throw new IllegalArgumentException();
                long measuredAt = parseDateTime(row.get(0));
                Measurement item = new Measurement(0,
                        Integer.parseInt(row.get(2).trim()), Integer.parseInt(row.get(3).trim()),
                        Integer.parseInt(row.get(4).trim()), measuredAt, row.get(5));
                if (!isValid(item)) {
                    invalid++;
                } else if (!knownRows.add(key(item))) {
                    duplicates++;
                } else {
                    accepted.add(item);
                }
            } catch (IllegalArgumentException error) {
                invalid++;
            }
        }
        return new ImportBatch(accepted, invalid, duplicates);
    }

    private static List<List<String>> parseCsv(String contents) throws FormatException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < contents.length(); i++) {
            char ch = contents.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < contents.length() && contents.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.length() == 0) {
                quoted = true;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                if (ch == '\r' && i + 1 < contents.length() && contents.charAt(i + 1) == '\n') i++;
            } else {
                field.append(ch);
            }
        }
        if (quoted) throw new FormatException("CSV 文件中的引号没有正确闭合");
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static long parseDateTime(String value) {
        String text = value.trim();
        // A descriptive prefix keeps spreadsheet software from coercing dates into narrow numeric cells.
        // Continue accepting timestamps exported by 1.0.0.
        if (text.startsWith("测量于 ")) text = text.substring("测量于 ".length()).trim();
        String[] formats = {"yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"};
        for (String format : formats) {
            SimpleDateFormat parser = new SimpleDateFormat(format, Locale.ROOT);
            parser.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date parsed = parser.parse(text, position);
            if (parsed != null && position.getIndex() == text.length()) return parsed.getTime();
        }
        throw new IllegalArgumentException("无效的测量时间");
    }

    private static boolean isValid(Measurement item) {
        return item.measuredAt > 0 && item.systolic >= 50 && item.systolic <= 260 &&
                item.diastolic >= 30 && item.diastolic <= 160 && item.diastolic < item.systolic &&
                item.pulse >= 30 && item.pulse <= 220;
    }

    private static String period(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        if (hour < 5) return "凌晨";
        if (hour < 12) return "上午";
        if (hour < 18) return "下午";
        return "晚上";
    }

    private static void writeCsvRow(Writer writer, String[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            String value = values[i] == null ? "" : values[i];
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 ||
                    value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                writer.write('"');
                writer.write(value.replace("\"", "\"\""));
                writer.write('"');
            } else {
                writer.write(value);
            }
        }
        writer.write("\r\n");
    }

    static final class ImportBatch {
        final List<Measurement> measurements;
        final int invalidCount;
        final int duplicateCount;

        ImportBatch(List<Measurement> measurements, int invalidCount, int duplicateCount) {
            this.measurements = measurements;
            this.invalidCount = invalidCount;
            this.duplicateCount = duplicateCount;
        }
    }

    static final class FormatException extends Exception {
        FormatException(String message) {
            super(message);
        }
    }
}

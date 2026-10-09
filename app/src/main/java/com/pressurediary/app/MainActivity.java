package com.pressurediary.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.InputType;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_EXPORT_REPORT = 41;
    private static final int REQUEST_EXPORT_BACKUP = 42;
    private static final int REQUEST_IMPORT_DATA = 43;
    private static final int BG = 0xFFF4F7F5;
    private static final int SURFACE = 0xFFFFFFFF;
    private static final int INK = 0xFF1D2B26;
    private static final int MUTED = 0xFF68766F;
    private static final int ACCENT = 0xFF187C68;
    private static final int SYS = 0xFFD66B51;
    private static final int DIA = 0xFF4886B8;
    private static final int PULSE = 0xFFB1811E;
    private static final int LINE = 0xFFE6ECE8;
    private static final int STATUS_NORMAL = 0xFF167A50;
    private static final int STATUS_ELEVATED = 0xFF9A6100;
    private static final int STATUS_HIGH = 0xFFB8322A;
    private static final int STATUS_LOW = 0xFF3268A8;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA);
    private final SimpleDateFormat monthDayFormat = new SimpleDateFormat("M月d日", Locale.CHINA);
    private final SimpleDateFormat fullDateFormat = new SimpleDateFormat("yyyy年M月d日", Locale.CHINA);
    private MeasurementDatabase database;
    private List<Measurement> measurements = new ArrayList<>();
    private TrendChartView chart;
    private TextView days7Button;
    private TextView days30Button;
    private TextView dateButton;
    private TextView pressureButton;
    private TextView pulseButton;
    private LinearLayout chartLegend;
    private TextView chartReadoutLabel;
    private TextView chartReadoutDate;
    private TextView chartReadoutValue;
    private int selectedDays = 7;
    private Long selectedDateStart;
    private Long selectedDateEnd;
    private boolean showPulse;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        database = new MeasurementDatabase(this);
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        int systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) systemUiVisibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(systemUiVisibility);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (database != null) render();
    }

    @Override
    protected void onDestroy() {
        if (database != null) database.close();
        super.onDestroy();
    }

    private void render() {
        measurements = database.all();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(14), dp(20), dp(16));

        addHeader(content);
        content.addView(summaryPanel(), marginParams(MATCH, WRAP, 0, 22, 0, 0));
        addTrendSection(content);
        addHistorySection(content);

        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        root.addView(footer(), new LinearLayout.LayoutParams(MATCH, WRAP));
        setContentView(root);
    }

    private void addHeader(LinearLayout content) {
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.addView(label("血压记录", 13, ACCENT, true));
        titleBlock.addView(label("血压日记", 26, INK, true), marginParams(MATCH, WRAP, 0, 6, 0, 0));
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, WRAP, 1));

        TextView settings = label("⚙️", 24, INK, false);
        settings.setGravity(Gravity.CENTER);
        settings.setBackground(rounded(SURFACE, 8, LINE));
        settings.setContentDescription("数据管理设置");
        settings.setOnClickListener(v -> showDataManagementMenu());
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(header);

        TextView sub = label(dateFormat.format(new Date()), 13, MUTED, false);
        content.addView(sub, marginParams(MATCH, WRAP, 0, 4, 0, 0));
    }

    private View summaryPanel() {
        LinearLayout panel = panel();
        LinearLayout heading = horizontal();
        LinearLayout headingCopy = new LinearLayout(this);
        headingCopy.setOrientation(LinearLayout.VERTICAL);
        headingCopy.addView(label("最近一次测量", 15, INK, true));
        if (measurements.isEmpty()) {
            headingCopy.addView(label("还没有记录", 14, MUTED, false), marginParams(MATCH, WRAP, 0, 4, 0, 0));
        } else {
            headingCopy.addView(label(measurementDateTime(measurements.get(0).measuredAt), 15, MUTED, true),
                    marginParams(MATCH, WRAP, 0, 4, 0, 0));
        }
        heading.addView(headingCopy, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView count = label(todayCount() + " 次今日", 13, ACCENT, true);
        heading.addView(count);
        panel.addView(heading);

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        panel.addView(divider, marginParams(MATCH, dp(1), 0, 16, 0, 14));

        Measurement latest = measurements.isEmpty() ? null : measurements.get(0);
        LinearLayout values = horizontal();
        values.setGravity(Gravity.CENTER_VERTICAL);
        values.addView(summaryMetric("收缩压", latest == null ? "--" : String.valueOf(latest.systolic), "mmHg", SYS),
                new LinearLayout.LayoutParams(0, WRAP, 1));
        values.addView(verticalDivider());
        values.addView(summaryMetric("舒张压", latest == null ? "--" : String.valueOf(latest.diastolic), "mmHg", DIA),
                new LinearLayout.LayoutParams(0, WRAP, 1));
        values.addView(verticalDivider());
        values.addView(summaryMetric("脉搏", latest == null ? "--" : String.valueOf(latest.pulse), "次/分", PULSE),
                new LinearLayout.LayoutParams(0, WRAP, 1));
        panel.addView(values);

        LinearLayout statusRow = horizontal();
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.addView(label("本次血压参考", 14, INK, true), new LinearLayout.LayoutParams(0, WRAP, 1));
        if (latest != null) statusRow.addView(statusBadge(statusFor(latest.systolic, latest.diastolic)));
        TextView guidance = label("判读说明", 13, ACCENT, true);
        guidance.setGravity(Gravity.CENTER);
        guidance.setMinHeight(dp(48));
        guidance.setPadding(dp(10), 0, dp(4), 0);
        guidance.setContentDescription("查看血压判读说明");
        guidance.setOnClickListener(v -> showPressureGuidance());
        statusRow.addView(guidance);
        panel.addView(statusRow, marginParams(MATCH, WRAP, 0, 12, 0, 0));
        if (latest == null) {
            panel.addView(label("记录测量后显示参考提示", 13, MUTED, false),
                    marginParams(MATCH, WRAP, 0, 4, 0, 0));
        }
        return panel;
    }

    private View summaryMetric(String title, String value, String unit, int color) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(2), 0, dp(2), 0);
        column.addView(label(title, 14, MUTED, false));
        if (title.equals("收缩压") && !"--".equals(value)) color = statusForSystolic(Integer.parseInt(value)).color;
        if (title.equals("舒张压") && !"--".equals(value)) color = statusForDiastolic(Integer.parseInt(value)).color;
        TextView number = label(value, 30, color, true);
        column.addView(number, marginParams(WRAP, WRAP, 0, 4, 0, 0));
        column.addView(label(unit, 13, MUTED, false));
        return column;
    }

    private View verticalDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(48));
        params.setMargins(dp(3), 0, dp(3), 0);
        divider.setLayoutParams(params);
        return divider;
    }

    private void addTrendSection(LinearLayout content) {
        LinearLayout titleRow = horizontal();
        TextView title = label("趋势", 19, INK, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        days7Button = segment("7日", selectedDays == 7, () -> selectDays(7));
        days30Button = segment("30日", selectedDays == 30, () -> selectDays(30));
        String dateButtonText = selectedDateStart == null
                ? "按日期" : selectedDateLabel(selectedDateStart);
        dateButton = segment(dateButtonText, selectedDateStart != null, this::chooseTrendDate);
        LinearLayout range = horizontal();
        range.addView(days7Button);
        range.addView(days30Button, marginParams(WRAP, dp(48), 6, 0, 0, 0));
        range.addView(dateButton, marginParams(WRAP, dp(48), 6, 0, 0, 0));
        titleRow.addView(range);
        content.addView(titleRow, marginParams(MATCH, WRAP, 0, 24, 0, 10));

        LinearLayout chartPanel = panel();
        LinearLayout metricSwitch = horizontal();
        pressureButton = segment("血压", !showPulse, () -> selectMetric(false));
        pulseButton = segment("脉搏", showPulse, () -> selectMetric(true));
        metricSwitch.addView(pressureButton);
        metricSwitch.addView(pulseButton, marginParams(WRAP, dp(48), 6, 0, 0, 0));
        chartPanel.addView(metricSwitch);

        chartLegend = horizontal();
        refreshChartLegend();
        chartPanel.addView(chartLegend, marginParams(MATCH, WRAP, 0, 14, 0, 10));

        LinearLayout readout = new LinearLayout(this);
        readout.setOrientation(LinearLayout.VERTICAL);
        chartReadoutDate = label("", 22, INK, true);
        chartReadoutValue = label("", 19, INK, true);
        chartReadoutLabel = label("", 13, MUTED, false);
        readout.addView(chartReadoutDate);
        readout.addView(chartReadoutValue, marginParams(MATCH, WRAP, 0, 5, 0, 0));
        readout.addView(chartReadoutLabel, marginParams(MATCH, WRAP, 0, 3, 0, 0));
        chartPanel.addView(readout, marginParams(MATCH, WRAP, 0, 0, 0, 8));

        chart = new TrendChartView(this);
        chart.setOnMeasurementSelectedListener((item, index, count) ->
                refreshChartReadout(item, index, count));
        chart.setData(measurements, selectedDays, showPulse, selectedDateStart, selectedDateEnd);
        chartPanel.addView(chart, new LinearLayout.LayoutParams(MATCH, dp(210)));
        content.addView(chartPanel);
    }

    private View legendItem(String text, int color) {
        LinearLayout item = horizontal();
        View dot = new View(this);
        dot.setBackground(circle(color));
        item.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView name = label(text, 13, MUTED, false);
        item.addView(name, marginParams(WRAP, WRAP, 6, 0, 0, 0));
        return item;
    }

    private void addHistorySection(LinearLayout content) {
        List<Measurement> history = historyForSelectedDate();
        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        String headingText = selectedDateStart == null
                ? "测量记录" : selectedDateLabel(selectedDateStart) + " 的测量记录";
        heading.addView(label(headingText, 20, INK, true), new LinearLayout.LayoutParams(0, WRAP, 1));
        heading.addView(label(history.size() + " 条", 14, MUTED, false));
        content.addView(heading, marginParams(MATCH, WRAP, 0, 25, 0, 7));

        if (history.isEmpty()) {
            LinearLayout empty = panel();
            empty.setGravity(Gravity.CENTER);
            String emptyMessage = selectedDateStart == null
                    ? "添加第一次测量，开始查看自己的变化" : "这一天还没有测量记录";
            TextView emptyText = label(emptyMessage, 15, MUTED, false);
            emptyText.setGravity(Gravity.CENTER);
            empty.addView(emptyText);
            content.addView(empty);
            return;
        }

        int count = selectedDateStart == null ? Math.min(30, history.size()) : history.size();
        for (int i = 0; i < count; i++) {
            Measurement item = history.get(i);
            content.addView(recordRow(item), new LinearLayout.LayoutParams(MATCH, WRAP));
            if (i < count - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(LINE);
                content.addView(divider, marginParams(MATCH, dp(1), 0, 0, 0, 0));
            }
        }
        if (history.size() > count) {
            content.addView(label("仅显示最近 30 条记录", 13, MUTED, false), marginParams(MATCH, WRAP, 0, 9, 0, 0));
        }
    }

    private void showDataManagementMenu() {
        new AlertDialog.Builder(this)
                .setTitle("数据管理 · " + measurements.size() + " 条记录")
                .setItems(new String[]{"导出报告（CSV）", "备份全部数据（JSON）", "导入数据（CSV 或 JSON）"},
                        (dialog, which) -> {
                            if (which == 0) createReportFile();
                            else if (which == 1) createBackupFile();
                            else if (which == 2) chooseImportFile();
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void createReportFile() {
        launchCreateDocument(REQUEST_EXPORT_REPORT, "text/csv", "血压记录报告-" + fileDateSuffix() + ".csv");
    }

    private void createBackupFile() {
        launchCreateDocument(REQUEST_EXPORT_BACKUP, "application/json", "血压日记备份-" + fileDateSuffix() + ".json");
    }

    private void launchCreateDocument(int requestCode, String mimeType, String fileName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        startActivityForResult(intent, requestCode);
    }

    private void chooseImportFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "text/comma-separated-values", "application/json"});
        startActivityForResult(intent, REQUEST_IMPORT_DATA);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_REPORT || requestCode == REQUEST_EXPORT_BACKUP) {
            exportTo(uri, requestCode);
        } else if (requestCode == REQUEST_IMPORT_DATA) {
            previewImport(uri);
        }
    }

    private void exportTo(Uri uri, int requestCode) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IOException("无法写入所选文件");
            if (requestCode == REQUEST_EXPORT_REPORT) {
                MeasurementFileTransfer.writeReport(output, measurements);
                Toast.makeText(this, "报告已导出，共 " + measurements.size() + " 条记录", Toast.LENGTH_LONG).show();
            } else {
                MeasurementFileTransfer.writeBackup(output, measurements);
                Toast.makeText(this, "备份已导出，共 " + measurements.size() + " 条记录", Toast.LENGTH_LONG).show();
            }
        } catch (IOException error) {
            showTransferError("导出失败", error);
        }
    }

    private void previewImport(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取所选文件");
            MeasurementFileTransfer.ImportBatch batch = MeasurementFileTransfer.readImport(input, database.all());
            if (batch.measurements.isEmpty()) {
                if (batch.invalidCount == 0 && batch.duplicateCount == 0) {
                    showTransferMessage("没有可导入的记录", "文件中没有测量数据。原有记录未更改。");
                } else {
                    showTransferMessage("没有新记录", "重复记录 " + batch.duplicateCount + " 条，无法导入的记录 " +
                            batch.invalidCount + " 条。原有记录未更改。");
                }
                return;
            }
            String message = "可导入新记录 " + batch.measurements.size() + " 条\n" +
                    "重复记录将跳过 " + batch.duplicateCount + " 条\n" +
                    "格式或数值无效将忽略 " + batch.invalidCount + " 条\n\n" +
                    "确认后将合并到本机记录，并更新趋势与日期筛选。";
            new AlertDialog.Builder(this)
                    .setTitle("导入血压数据")
                    .setMessage(message)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("合并导入", (dialog, which) -> {
                        try {
                            int inserted = database.importRows(batch.measurements);
                            int skipped = batch.duplicateCount + batch.measurements.size() - inserted;
                            selectedDateStart = null;
                            selectedDateEnd = null;
                            selectedDays = 30;
                            render();
                            Toast.makeText(this, "已导入 " + inserted + " 条，跳过重复记录 " + skipped +
                                    " 条，无效记录 " + batch.invalidCount + " 条", Toast.LENGTH_LONG).show();
                        } catch (RuntimeException error) {
                            showTransferError("导入失败", error);
                        }
                    })
                    .show();
        } catch (IOException | MeasurementFileTransfer.FormatException error) {
            showTransferError("无法导入文件", error);
        }
    }

    private void showTransferMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showTransferError(String title, Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = "请检查文件后重试。";
        showTransferMessage(title, message);
    }

    private String fileDateSuffix() {
        return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date());
    }

    private List<Measurement> historyForSelectedDate() {
        if (selectedDateStart == null || selectedDateEnd == null) return measurements;
        List<Measurement> filtered = new ArrayList<>();
        for (Measurement item : measurements) {
            if (item.measuredAt >= selectedDateStart && item.measuredAt < selectedDateEnd) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private View recordRow(Measurement item) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        TextView marker = label("血压", 12, ACCENT, true);
        marker.setGravity(Gravity.CENTER);
        marker.setBackground(rounded(0xFFE2F1EC, 8, 0));
        row.addView(marker, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView time = label(measurementDateTime(item.measuredAt), 18, INK, true);
        copy.addView(time);
        LinearLayout pressureRow = horizontal();
        pressureRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView pressure = label("", 16, INK, true);
        pressure.setText(coloredPressure(item.systolic, item.diastolic, 16));
        pressureRow.addView(pressure);
        copy.addView(pressureRow, marginParams(MATCH, WRAP, 0, 3, 0, 0));
        copy.addView(statusBadge(statusFor(item.systolic, item.diastolic)),
                marginParams(WRAP, WRAP, 0, 3, 0, 0));
        String detail = "脉搏 " + item.pulse + " 次/分";
        if (!item.note.isEmpty()) detail += "  ·  " + item.note;
        TextView sub = label("", 13, MUTED, false);
        SpannableString styledDetail = new SpannableString(detail);
        int pulseStart = detail.indexOf(String.valueOf(item.pulse), detail.indexOf("脉搏"));
        if (pulseStart >= 0) {
            styledDetail.setSpan(new ForegroundColorSpan(PULSE), pulseStart,
                    pulseStart + String.valueOf(item.pulse).length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sub.setText(styledDetail);
        sub.setMaxLines(2);
        copy.addView(sub, marginParams(MATCH, WRAP, 0, 4, 0, 0));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, WRAP, 1);
        copyParams.setMargins(dp(12), 0, dp(8), 0);
        row.addView(copy, copyParams);

        TextView edit = label("编辑", 13, ACCENT, true);
        edit.setGravity(Gravity.CENTER);
        edit.setMinWidth(dp(48));
        edit.setMinHeight(dp(48));
        edit.setPadding(dp(4), dp(4), dp(4), dp(4));
        edit.setOnClickListener(v -> showMeasurementDialog(item));
        edit.setContentDescription("编辑" + measurementDateTime(item.measuredAt) + "的测量记录");
        row.addView(edit);

        TextView delete = label("删除", 13, MUTED, false);
        delete.setGravity(Gravity.CENTER);
        delete.setMinWidth(dp(48));
        delete.setMinHeight(dp(48));
        delete.setPadding(dp(4), dp(4), dp(4), dp(4));
        delete.setOnClickListener(v -> confirmDelete(item));
        delete.setContentDescription("删除" + measurementDateTime(item.measuredAt) + "的测量记录");
        row.addView(delete);

        copy.setOnClickListener(v -> showMeasurementDialog(item));
        return row;
    }

    private View footer() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(20), dp(9), dp(20), dp(9));
        footer.setBackgroundColor(BG);
        Button add = new Button(this);
        add.setText("＋  记录一次");
        add.setTextSize(17);
        add.setTextColor(SURFACE);
        add.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add.setAllCaps(false);
        add.setBackground(rounded(ACCENT, 8, 0));
        add.setMinHeight(dp(56));
        add.setOnClickListener(v -> showMeasurementDialog(null));
        footer.addView(add, new LinearLayout.LayoutParams(MATCH, dp(56)));
        TextView note = label("记录用于趋势观察，不替代专业诊断", 12, MUTED, false);
        note.setGravity(Gravity.CENTER);
        footer.addView(note, marginParams(MATCH, WRAP, 0, 7, 0, 0));
        return footer;
    }

    private void selectDays(int days) {
        selectedDays = days;
        selectedDateStart = null;
        selectedDateEnd = null;
        render();
    }

    private void chooseTrendDate() {
        Calendar day = Calendar.getInstance();
        if (selectedDateStart != null) day.setTimeInMillis(selectedDateStart);
        DatePickerDialog picker = new DatePickerDialog(this, (view, year, month, date) -> {
            setSelectedDate(year, month, date);
            render();
        }, day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        picker.show();
    }

    private void selectMetric(boolean pulse) {
        showPulse = pulse;
        updateChartControls();
    }

    private void updateChartControls() {
        if (days7Button == null || chart == null) return;
        styleSegment(days7Button, selectedDays == 7);
        styleSegment(days30Button, selectedDays == 30);
        styleSegment(dateButton, selectedDateStart != null);
        styleSegment(pressureButton, !showPulse);
        styleSegment(pulseButton, showPulse);
        refreshChartLegend();
        chart.setData(measurements, selectedDays, showPulse, selectedDateStart, selectedDateEnd);
    }

    private void refreshChartLegend() {
        if (chartLegend == null) return;
        chartLegend.removeAllViews();
        if (showPulse) {
            chartLegend.addView(legendItem("脉搏", PULSE));
        } else {
            chartLegend.addView(legendItem("收缩压", SYS));
            chartLegend.addView(legendItem("舒张压", DIA), marginParams(WRAP, WRAP, 14, 0, 0, 0));
        }
    }

    private void refreshChartReadout(Measurement selected, int index, int count) {
        if (chartReadoutLabel == null) return;
        if (selected == null) {
            chartReadoutDate.setText(selectedDateStart == null ? "区间内暂无记录" : "这一天暂无记录");
            chartReadoutValue.setText("--");
            chartReadoutValue.setTextColor(MUTED);
            chartReadoutLabel.setText("共 0 条测量");
            return;
        }
        chartReadoutDate.setText(measurementDateTime(selected.measuredAt));
        String rangeLabel = selectedDateStart == null
                ? "最近 " + selectedDays + " 日" : selectedDateLabel(selectedDateStart);
        chartReadoutLabel.setText("第 " + (index + 1) + " 条 · " + rangeLabel + " · 共 " + count + " 条");
        if (showPulse) {
            chartReadoutValue.setText(selected.pulse + " 次/分");
            chartReadoutValue.setTextColor(PULSE);
        } else {
            chartReadoutValue.setText(coloredPressure(selected.systolic, selected.diastolic, 19));
        }
    }

    private String measurementDateTime(long timestamp) {
        Calendar time = Calendar.getInstance();
        time.setTimeInMillis(timestamp);
        int hour = time.get(Calendar.HOUR_OF_DAY);
        String period;
        if (hour < 5) period = "凌晨";
        else if (hour < 12) period = "上午";
        else if (hour < 18) period = "下午";
        else period = "晚上";
        int hour12 = hour % 12;
        if (hour12 == 0) hour12 = 12;
        String date = time.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
                ? monthDayFormat.format(new Date(timestamp)) : fullDateFormat.format(new Date(timestamp));
        return date + "  " + period + " " +
                String.format(Locale.CHINA, "%d:%02d", hour12, time.get(Calendar.MINUTE));
    }

    private String selectedDateLabel(long timestamp) {
        Calendar selected = Calendar.getInstance();
        selected.setTimeInMillis(timestamp);
        return selected.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
                ? monthDayFormat.format(new Date(timestamp)) : fullDateFormat.format(new Date(timestamp));
    }

    private void setSelectedDate(int year, int month, int date) {
        Calendar start = Calendar.getInstance();
        start.set(year, month, date, 0, 0, 0);
        start.set(Calendar.MILLISECOND, 0);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_MONTH, 1);
        selectedDateStart = start.getTimeInMillis();
        selectedDateEnd = end.getTimeInMillis();
    }

    private void setSelectedDate(long timestamp) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(timestamp);
        setSelectedDate(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH));
    }

    private SpannableString coloredPressure(int systolic, int diastolic, int sizeSp) {
        String text = systolic + " / " + diastolic + " mmHg";
        SpannableString styled = new SpannableString(text);
        int slash = text.indexOf(" / ");
        int unit = text.indexOf(" mmHg");
        styled.setSpan(new ForegroundColorSpan(statusForSystolic(systolic).color), 0, slash, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new ForegroundColorSpan(statusForDiastolic(diastolic).color), slash + 3, unit, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new ForegroundColorSpan(MUTED), unit, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new RelativeSizeSpan(0.78f), unit, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }

    private PressureStatus statusFor(int systolic, int diastolic) {
        if (systolic >= 140 || diastolic >= 90) {
            return new PressureStatus("高值范围", STATUS_HIGH, 0xFFFCE9E7);
        }
        if (systolic < 90 || diastolic < 60) {
            return new PressureStatus("偏低参考", STATUS_LOW, 0xFFEAF1FA);
        }
        if (systolic >= 120 || diastolic >= 80) {
            return new PressureStatus("正常高值", STATUS_ELEVATED, 0xFFFFF3DB);
        }
        return new PressureStatus("正常", STATUS_NORMAL, 0xFFE6F3EC);
    }

    private PressureStatus statusForSystolic(int value) {
        if (value >= 140) return new PressureStatus("高值范围", STATUS_HIGH, 0xFFFCE9E7);
        if (value < 90) return new PressureStatus("偏低参考", STATUS_LOW, 0xFFEAF1FA);
        if (value >= 120) return new PressureStatus("正常高值", STATUS_ELEVATED, 0xFFFFF3DB);
        return new PressureStatus("正常", STATUS_NORMAL, 0xFFE6F3EC);
    }

    private PressureStatus statusForDiastolic(int value) {
        if (value >= 90) return new PressureStatus("高值范围", STATUS_HIGH, 0xFFFCE9E7);
        if (value < 60) return new PressureStatus("偏低参考", STATUS_LOW, 0xFFEAF1FA);
        if (value >= 80) return new PressureStatus("正常高值", STATUS_ELEVATED, 0xFFFFF3DB);
        return new PressureStatus("正常", STATUS_NORMAL, 0xFFE6F3EC);
    }

    private View statusBadge(PressureStatus status) {
        TextView badge = label(status.label, 13, status.color, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(8), dp(5), dp(8), dp(5));
        badge.setMinHeight(dp(28));
        badge.setBackground(rounded(status.background, 6, 0));
        badge.setContentDescription("血压参考状态：" + status.label);
        return badge;
    }

    private void showPressureGuidance() {
        String message = "成人诊室血压参考（mmHg）\n" +
                "正常：收缩压低于120且舒张压低于80\n" +
                "正常高值：收缩压120–139或舒张压80–89\n" +
                "高值范围：收缩压达到140或舒张压达到90\n" +
                "偏低参考：收缩压低于90或舒张压低于60\n\n" +
                "家庭测量收缩压达到135或舒张压达到85，应结合连续多日读数评估。单次读数不能确诊；中老年人的控制目标应由医生结合年龄、身体状况和用药确定。若明显不适或反复出现异常读数，请联系医护人员。";
        new AlertDialog.Builder(this)
                .setTitle("血压数值参考")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private static final class PressureStatus {
        final String label;
        final int color;
        final int background;

        PressureStatus(String label, int color, int background) {
            this.label = label;
            this.color = color;
            this.background = background;
        }
    }

    private void showMeasurementDialog(Measurement existing) {
        Calendar selected = Calendar.getInstance();
        if (existing != null) selected.setTimeInMillis(existing.measuredAt);
        else {
            selected.set(Calendar.SECOND, 0);
            selected.set(Calendar.MILLISECOND, 0);
        }

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), dp(8), dp(4), dp(8));

        TextView stepLabel = label("", 15, ACCENT, true);
        form.addView(stepLabel);
        TextView prompt = label("", 25, INK, true);
        form.addView(prompt, marginParams(MATCH, WRAP, 0, 12, 0, 0));
        TextView instruction = label("", 16, MUTED, false);
        form.addView(instruction, marginParams(MATCH, WRAP, 0, 7, 0, 14));

        String[] values = {
                existing == null ? "" : String.valueOf(existing.systolic),
                existing == null ? "" : String.valueOf(existing.diastolic),
                existing == null ? "" : String.valueOf(existing.pulse)
        };
        String[] names = {"高压（收缩压）", "低压（舒张压）", "脉搏"};
        String[] descriptions = {
                "请看血压计屏幕上方的数字",
                "请看血压计屏幕中间或下方的数字",
                "请看血压计上的脉搏或心率数字"
        };
        String[] units = {"mmHg", "mmHg", "次/分"};
        int[] currentStep = {0};

        LinearLayout inputBox = horizontal();
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setPadding(dp(18), 0, dp(18), 0);
        inputBox.setBackground(rounded(SURFACE, 8, LINE));
        EditText valueInput = numberField(values[0], names[0]);
        valueInput.setTextSize(42);
        valueInput.setGravity(Gravity.CENTER_VERTICAL);
        valueInput.setPadding(0, dp(4), dp(8), dp(4));
        valueInput.setBackgroundColor(0x00000000);
        valueInput.setHint("请输入");
        valueInput.setSelectAllOnFocus(false);
        inputBox.addView(valueInput, new LinearLayout.LayoutParams(0, dp(88), 1));
        TextView unit = label("mmHg", 18, MUTED, true);
        inputBox.addView(unit);
        form.addView(inputBox);

        TextView validation = label("", 14, STATUS_HIGH, false);
        form.addView(validation, marginParams(MATCH, WRAP, 0, 8, 0, 0));

        TextView moreToggle = label("测量时间和备注（选填）", 15, ACCENT, true);
        moreToggle.setGravity(Gravity.CENTER_VERTICAL);
        moreToggle.setMinHeight(dp(48));
        moreToggle.setContentDescription("展开测量时间和备注选项");
        form.addView(moreToggle, marginParams(MATCH, WRAP, 0, 10, 0, 0));
        LinearLayout optionalDetails = new LinearLayout(this);
        optionalDetails.setOrientation(LinearLayout.VERTICAL);
        optionalDetails.setVisibility(View.GONE);
        form.addView(optionalDetails);

        TextView timeLabel = label("测量时间", 14, MUTED, true);
        optionalDetails.addView(timeLabel, marginParams(MATCH, WRAP, 0, 0, 0, 6));
        TextView timeValue = label("", 16, INK, false);
        timeValue.setGravity(Gravity.CENTER_VERTICAL);
        timeValue.setPadding(dp(12), 0, dp(12), 0);
        timeValue.setBackground(rounded(SURFACE, 8, LINE));
        optionalDetails.addView(timeValue, new LinearLayout.LayoutParams(MATCH, dp(52)));
        Runnable updateTime = () -> timeValue.setText(measurementDateTime(selected.getTimeInMillis()));
        updateTime.run();
        timeValue.setOnClickListener(v -> {
            new DatePickerDialog(this, (picker, year, month, day) -> {
                selected.set(Calendar.YEAR, year);
                selected.set(Calendar.MONTH, month);
                    selected.set(Calendar.DAY_OF_MONTH, day);
                new TimePickerDialog(this, (timePicker, hour, minute) -> {
                    selected.set(Calendar.HOUR_OF_DAY, hour);
                    selected.set(Calendar.MINUTE, minute);
                    selected.set(Calendar.SECOND, 0);
                    selected.set(Calendar.MILLISECOND, 0);
                    updateTime.run();
                    valueInput.requestFocus();
                    InputMethodManager inputManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (inputManager != null) inputManager.showSoftInput(valueInput, InputMethodManager.SHOW_IMPLICIT);
                }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), true).show();
            }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).show();
        });

        TextView noteLabel = label("备注（选填）", 14, MUTED, true);
        optionalDetails.addView(noteLabel, marginParams(MATCH, WRAP, 0, 13, 0, 6));
        EditText note = new EditText(this);
        note.setTextSize(16);
        note.setTextColor(INK);
        note.setHintTextColor(0xFF98A49E);
        note.setHint("例如：起床后、运动后");
        note.setSingleLine(false);
        note.setMinLines(2);
        note.setMaxLines(3);
        note.setGravity(Gravity.TOP | Gravity.START);
        note.setPadding(dp(12), dp(10), dp(12), dp(8));
        note.setBackground(rounded(SURFACE, 8, LINE));
        if (existing != null) note.setText(existing.note);
        optionalDetails.addView(note, new LinearLayout.LayoutParams(MATCH, dp(76)));
        if (existing != null) {
            optionalDetails.setVisibility(View.VISIBLE);
            moreToggle.setText("收起选填信息");
        }
        moreToggle.setOnClickListener(v -> {
            boolean expanded = optionalDetails.getVisibility() != View.VISIBLE;
            optionalDetails.setVisibility(expanded ? View.VISIBLE : View.GONE);
            moreToggle.setText(expanded ? "收起选填信息" : "测量时间和备注（选填）");
            moreToggle.setContentDescription(expanded ? "收起测量时间和备注选项" : "展开测量时间和备注选项");
        });

        ScrollView formScroll = new ScrollView(this);
        formScroll.setFillViewport(false);
        formScroll.addView(form);
        LinearLayout dialogContent = new LinearLayout(this);
        dialogContent.setOrientation(LinearLayout.VERTICAL);
        dialogContent.setPadding(dp(20), dp(4), dp(20), dp(4));
        dialogContent.addView(formScroll, new LinearLayout.LayoutParams(MATCH, WRAP));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "记录测量" : "编辑记录")
                .setView(dialogContent)
                .setNegativeButton("取消", null)
                .setNeutralButton("上一步", null)
                .setPositiveButton("下一步", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            Button nextButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button backButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            nextButton.setTextColor(ACCENT);
            nextButton.setTextSize(16);
            backButton.setTextColor(MUTED);
            backButton.setTextSize(15);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(MUTED);
            Runnable[] showStep = new Runnable[1];
            showStep[0] = () -> {
                int step = currentStep[0];
                stepLabel.setText("第 " + (step + 1) + " 项，共 3 项");
                prompt.setText("请输入" + names[step]);
                instruction.setText(descriptions[step]);
                valueInput.setContentDescription("请输入" + names[step] + "，" + units[step]);
                valueInput.setImeOptions(step < 2 ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);
                unit.setText(units[step]);
                valueInput.setText(values[step]);
                valueInput.setSelection(valueInput.length());
                validation.setText("");
                backButton.setVisibility(step == 0 ? View.INVISIBLE : View.VISIBLE);
                nextButton.setText(step == 2 ? "保存" : "下一步");
            };
            nextButton.setOnClickListener(v -> {
                String input = valueInput.getText().toString().trim();
                if (input.isEmpty()) {
                    validation.setText("请先输入" + names[currentStep[0]] + "数值");
                    return;
                }
                int parsed;
                try {
                    parsed = Integer.parseInt(input);
                } catch (NumberFormatException error) {
                    validation.setText("请输入数字");
                    return;
                }
                int step = currentStep[0];
                if (step == 0 && (parsed < 50 || parsed > 260)) {
                    validation.setText("高压请填写 50–260 mmHg 之间的数值");
                    return;
                }
                if (step == 1 && (parsed < 30 || parsed > 160 ||
                        parsed >= Integer.parseInt(values[0]))) {
                    validation.setText("低压需为 30–160 mmHg，并低于高压");
                    return;
                }
                if (step == 2 && (parsed < 30 || parsed > 220)) {
                    validation.setText("脉搏请填写 30–220 次/分之间的数值");
                    return;
                }
                values[step] = input;
                if (step < 2) {
                    currentStep[0]++;
                    showStep[0].run();
                    return;
                }
                int sysValue = Integer.parseInt(values[0]);
                int diaValue = Integer.parseInt(values[1]);
                int pulseValue = Integer.parseInt(values[2]);
                if (existing == null) {
                    database.insert(sysValue, diaValue, pulseValue, selected.getTimeInMillis(), note.getText().toString());
                } else {
                    database.update(existing.id, sysValue, diaValue, pulseValue, selected.getTimeInMillis(), note.getText().toString());
                }
                if (selectedDateStart != null) setSelectedDate(selected.getTimeInMillis());
                dialog.dismiss();
                render();
                Toast.makeText(this, existing == null ? "记录已保存" : "记录已更新", Toast.LENGTH_SHORT).show();
            });
            backButton.setOnClickListener(v -> {
                if (currentStep[0] > 0) {
                    values[currentStep[0]] = valueInput.getText().toString().trim();
                    currentStep[0]--;
                    showStep[0].run();
                }
            });
            valueInput.setOnEditorActionListener((view, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
                    nextButton.performClick();
                    return true;
                }
                return false;
            });
            showStep[0].run();
            valueInput.post(() -> {
                valueInput.requestFocus();
                InputMethodManager inputManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (inputManager != null) inputManager.showSoftInput(valueInput, InputMethodManager.SHOW_IMPLICIT);
            });
        });
        dialog.show();
    }

    private EditText numberField(String value, String description) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(value);
        field.setContentDescription(description);
        field.setSelectAllOnFocus(true);
        return field;
    }

    private void confirmDelete(Measurement item) {
        new AlertDialog.Builder(this)
                .setTitle("删除这条记录？")
                .setMessage(measurementDateTime(item.measuredAt) + "  ·  " + item.systolic + " / " + item.diastolic + " mmHg")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    database.delete(item.id);
                    render();
                    Toast.makeText(this, "记录已删除", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private int todayCount() {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        long startAt = start.getTimeInMillis();
        start.add(Calendar.DAY_OF_MONTH, 1);
        long endAt = start.getTimeInMillis();
        int count = 0;
        for (Measurement item : measurements) if (item.measuredAt >= startAt && item.measuredAt < endAt) count++;
        return count;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(14));
        panel.setBackground(rounded(SURFACE, 8, 0));
        return panel;
    }

    private TextView segment(String text, boolean selected, Runnable action) {
        TextView button = label(text, 14, selected ? SURFACE : MUTED, selected);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinWidth(dp(56));
        button.setMinHeight(dp(48));
        button.setBackground(rounded(selected ? ACCENT : SURFACE, 8, selected ? 0 : LINE));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void styleSegment(TextView view, boolean selected) {
        view.setTextColor(selected ? SURFACE : MUTED);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setBackground(rounded(selected ? ACCENT : SURFACE, 8, selected ? 0 : LINE));
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) shape.setStroke(dp(1), strokeColor);
        return shape;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setShape(GradientDrawable.OVAL);
        return shape;
    }

    private TextView label(String text, int sizeSp, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams marginWeightParams(float weight, int leftMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, WRAP, weight);
        params.leftMargin = dp(leftMarginDp);
        return params;
    }

    private LinearLayout.LayoutParams marginParams(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;
}

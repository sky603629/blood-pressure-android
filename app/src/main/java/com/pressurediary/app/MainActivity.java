package com.pressurediary.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.InputType;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
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
    private static final int REQUEST_CAPTURE_PRESSURE = 44;
    private static final int BG = 0xFFF2F2F7;
    private static final int SURFACE = 0xFFFFFFFF;
    private static final int INK = 0xFF1C1C1E;
    private static final int MUTED = 0xFF636366;
    private static final int ACCENT = 0xFF0066CC;
    private static final int SYS = 0xFFBF5543;
    private static final int DIA = 0xFF326CA5;
    private static final int PULSE = 0xFF966820;
    private static final int LINE = 0xFFE5E5EA;
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
    private TextView allDaysButton;
    private TextView dateButton;
    private TextView pressureButton;
    private TextView pulseButton;
    private LinearLayout chartLegend;
    private TextView chartReadoutLabel;
    private TextView chartReadoutDate;
    private TextView chartReadoutValue;
    private TextView viewportLabel;
    private TextView previousButton;
    private TextView nextButton;
    private TextView zoomInButton;
    private TextView zoomOutButton;
    private ScrollView mainScroll;
    private LinearLayout historySection;
    private int historyLimit = 30;
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
        window.setNavigationBarColor(SURFACE);
        int systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) systemUiVisibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(systemUiVisibility);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (database != null) render();
        MeasurementUploader.onDataChanged(this);
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

        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(false);
        mainScroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(20));

        addHeader(content);
        content.addView(summaryPanel(), marginParams(MATCH, WRAP, 0, 22, 0, 0));
        addTrendSection(content);
        historySection = new LinearLayout(this);
        historySection.setOrientation(LinearLayout.VERTICAL);
        content.addView(historySection, new LinearLayout.LayoutParams(MATCH, WRAP));
        refreshHistorySection();

        mainScroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(mainScroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        root.addView(footer(), new LinearLayout.LayoutParams(MATCH, WRAP));
        setContentView(root);
    }

    private void addHeader(LinearLayout content) {
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.addView(label("血压日记", 30, INK, true));
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, WRAP, 1));

        ImageButton settings = new ImageButton(this);
        settings.setImageResource(R.drawable.ic_settings);
        settings.setImageTintList(ColorStateList.valueOf(ACCENT));
        settings.setPadding(dp(12), dp(12), dp(12), dp(12));
        settings.setBackground(tappable(SURFACE, 16, 0));
        settings.setContentDescription("数据管理设置");
        settings.setOnClickListener(v -> showDataManagementMenu());
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(header);

        TextView sub = label(dateFormat.format(new Date()), 15, MUTED, false);
        content.addView(sub, marginParams(MATCH, WRAP, 0, 14, 0, 0));
    }

    private View summaryPanel() {
        LinearLayout panel = panel();
        LinearLayout heading = horizontal();
        LinearLayout headingCopy = new LinearLayout(this);
        headingCopy.setOrientation(LinearLayout.VERTICAL);
        headingCopy.addView(label("最近一次测量", 17, INK, true));
        if (measurements.isEmpty()) {
            headingCopy.addView(label("还没有记录", 16, MUTED, false), marginParams(MATCH, WRAP, 0, 6, 0, 0));
        } else {
            headingCopy.addView(label(measurementDateTime(measurements.get(0).measuredAt), 19, INK, true),
                    marginParams(MATCH, WRAP, 0, 6, 0, 0));
        }
        heading.addView(headingCopy, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView count = label("今天 " + todayCount() + " 次", 14, ACCENT, true);
        count.setPadding(dp(8), dp(6), dp(8), dp(6));
        count.setBackground(rounded(0xFFEBF4FF, 8, 0));
        heading.addView(count);
        panel.addView(heading);

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        panel.addView(divider, marginParams(MATCH, dp(1), 0, 16, 0, 14));

        Measurement latest = measurements.isEmpty() ? null : measurements.get(0);
        panel.addView(measurementValues(latest, true));

        LinearLayout statusRow = horizontal();
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        if (latest != null) statusRow.addView(statusBadge(statusFor(latest.systolic, latest.diastolic)));
        View spacer = new View(this);
        statusRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        TextView guidance = label("判读说明  ›", 15, ACCENT, true);
        guidance.setGravity(Gravity.CENTER);
        guidance.setMinHeight(dp(48));
        guidance.setPadding(dp(10), 0, dp(4), 0);
        guidance.setContentDescription("查看血压判读说明");
        guidance.setBackground(tappable(SURFACE, 8, 0));
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
        column.addView(label(title, 16, MUTED, true));
        if (title.equals("高压") && !"--".equals(value)) color = statusForSystolic(Integer.parseInt(value)).color;
        if (title.equals("低压") && !"--".equals(value)) color = statusForDiastolic(Integer.parseInt(value)).color;
        TextView number = label(value, 38, color, true);
        column.addView(number, marginParams(WRAP, WRAP, 0, 4, 0, 0));
        column.addView(label(unit, 13, MUTED, false));
        return column;
    }

    private View verticalDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(60));
        params.setMargins(dp(3), 0, dp(3), 0);
        divider.setLayoutParams(params);
        return divider;
    }

    private void addTrendSection(LinearLayout content) {
        content.addView(label("血压趋势", 22, INK, true),
                marginParams(MATCH, WRAP, 2, 28, 0, 10));
        LinearLayout range = horizontal();
        range.setPadding(dp(3), dp(3), dp(3), dp(3));
        range.setBackground(rounded(0xFFE7E7ED, 12, 0));
        days7Button = segment("7日", selectedDateStart == null && selectedDays == 7, () -> selectDays(7));
        days30Button = segment("30日", selectedDateStart == null && selectedDays == 30, () -> selectDays(30));
        allDaysButton = segment("全部", selectedDateStart == null && selectedDays == 0, () -> selectDays(0));
        dateButton = segment("按日期", selectedDateStart != null, this::chooseTrendDate);
        range.addView(days7Button, new LinearLayout.LayoutParams(0, WRAP, 1));
        range.addView(days30Button, marginWeightParams(1, 2));
        range.addView(allDaysButton, marginWeightParams(1, 2));
        range.addView(dateButton, marginWeightParams(1, 2));
        content.addView(range, marginParams(MATCH, WRAP, 0, 0, 0, 12));

        LinearLayout chartPanel = panel();
        LinearLayout metricSwitch = horizontal();
        metricSwitch.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout metrics = horizontal();
        metrics.setPadding(dp(3), dp(3), dp(3), dp(3));
        metrics.setBackground(rounded(BG, 12, 0));
        pressureButton = segment("血压", !showPulse, () -> selectMetric(false));
        pulseButton = segment("脉搏", showPulse, () -> selectMetric(true));
        metrics.addView(pressureButton, new LinearLayout.LayoutParams(0, WRAP, 1));
        metrics.addView(pulseButton, marginWeightParams(1, 2));
        metricSwitch.addView(metrics, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView overview = actionButton("全览", () -> chart.showAll());
        overview.setContentDescription("查看当前日期范围内的全部测量");
        metricSwitch.addView(overview, marginParams(WRAP, WRAP, 10, 0, 0, 0));
        chartPanel.addView(metricSwitch);

        chartLegend = horizontal();
        refreshChartLegend();
        chartPanel.addView(chartLegend, marginParams(MATCH, WRAP, 0, 16, 0, 10));

        chartReadoutDate = label("", 20, INK, true);
        chartReadoutDate.setLineSpacing(dp(3), 1);
        chartReadoutDate.setMinLines(2);
        chartReadoutValue = label("", 19, INK, true);
        chartReadoutValue.setMinLines(2);
        chartReadoutLabel = label("", 14, MUTED, false);
        chartReadoutLabel.setMinLines(2);
        chartPanel.addView(chartReadoutDate);
        chartPanel.addView(chartReadoutValue, marginParams(MATCH, WRAP, 0, 8, 0, 0));
        chartPanel.addView(chartReadoutLabel, marginParams(MATCH, WRAP, 0, 6, 0, 0));

        chart = new TrendChartView(this);
        float fontScale = getResources().getConfiguration().fontScale;
        chartPanel.addView(chart, new LinearLayout.LayoutParams(MATCH,
                dp(270 + Math.round(Math.max(0, fontScale - 1) * 90))));

        viewportLabel = label("", 14, MUTED, false);
        viewportLabel.setGravity(Gravity.CENTER);
        chartPanel.addView(viewportLabel, marginParams(MATCH, WRAP, 0, 8, 0, 10));
        LinearLayout navigation = horizontal();
        previousButton = actionButton("上一条", () -> chart.selectRelative(-1));
        nextButton = actionButton("下一条", () -> chart.selectRelative(1));
        zoomOutButton = actionButton("缩小", () -> chart.zoomOut());
        zoomInButton = actionButton("放大", () -> chart.zoomIn());
        zoomOutButton.setContentDescription("缩小图表，查看更多测量");
        zoomInButton.setContentDescription("放大图表，看清单次测量");
        navigation.addView(previousButton, new LinearLayout.LayoutParams(0, WRAP, 1));
        navigation.addView(nextButton, marginWeightParams(1, 6));
        chartPanel.addView(navigation);
        if (needsWideText()) {
            LinearLayout zoomControls = horizontal();
            zoomControls.addView(zoomOutButton, new LinearLayout.LayoutParams(0, WRAP, 1));
            zoomControls.addView(zoomInButton, marginWeightParams(1, 6));
            chartPanel.addView(zoomControls, marginParams(MATCH, WRAP, 0, 6, 0, 0));
        } else {
            navigation.addView(zoomOutButton, marginWeightParams(1, 6));
            navigation.addView(zoomInButton, marginWeightParams(1, 6));
        }
        TextView gestures = label("左右滑动查看 · 点击圆点看数值\n双指缩放 · 长按拖动逐条查看", 14, MUTED, false);
        gestures.setLineSpacing(dp(4), 1);
        chartPanel.addView(gestures, marginParams(MATCH, WRAP, 0, 14, 0, 0));
        chartPanel.addView(label("按记录先后排列，每个点代表一次测量", 13, MUTED, false),
                marginParams(MATCH, WRAP, 0, 6, 0, 0));
        chart.setOnMeasurementSelectedListener(this::refreshChartReadout);
        chart.setOnViewportChangedListener((first, last, count, canZoomIn, canZoomOut) -> {
            viewportLabel.setText(count == 0 ? "暂无测量数据" :
                    "显示第 " + (first + 1) + "–" + (last + 1) + " 条 / 共 " + count + " 条");
            setControlEnabled(zoomInButton, canZoomIn);
            setControlEnabled(zoomOutButton, canZoomOut);
        });
        chart.setData(measurements, selectedDays, showPulse, selectedDateStart, selectedDateEnd);
        content.addView(chartPanel);
    }

    private View legendItem(String text, int color, boolean square) {
        LinearLayout item = horizontal();
        item.setGravity(Gravity.CENTER_VERTICAL);
        View marker = new View(this);
        marker.setBackground(square ? rounded(color, 1, 0) : circle(color));
        item.addView(marker, new LinearLayout.LayoutParams(dp(8), dp(8)));
        item.addView(label(text, 14, MUTED, false), marginParams(WRAP, WRAP, 6, 0, 0, 0));
        return item;
    }

    private void addHistorySection(LinearLayout content) {
        List<Measurement> history = historyForSelectedDate();
        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label("测量记录", 22, INK, true), new LinearLayout.LayoutParams(0, WRAP, 1));
        heading.addView(label("共 " + history.size() + " 条", 15, MUTED, false));
        content.addView(heading, marginParams(MATCH, WRAP, 2, 28, 0, 4));
        String scope = selectedDateStart == null ? "按时间从近到远" :
                fullDateFormat.format(new Date(selectedDateStart)) + " · 当天全部记录";
        content.addView(label(scope, 15, MUTED, false), marginParams(MATCH, WRAP, 2, 0, 0, 12));

        if (history.isEmpty()) {
            LinearLayout empty = panel();
            empty.setGravity(Gravity.CENTER);
            String message = selectedDateStart == null
                    ? "点击下方按钮，记录第一次测量" : "这一天还没有测量记录";
            TextView emptyText = label(message, 17, MUTED, false);
            emptyText.setGravity(Gravity.CENTER);
            emptyText.setPadding(0, dp(16), 0, dp(16));
            empty.addView(emptyText);
            content.addView(empty);
            return;
        }

        int count = selectedDateStart == null ? Math.min(historyLimit, history.size()) : history.size();
        for (int i = 0; i < count; i++) {
            content.addView(recordRow(history.get(i)), marginParams(MATCH, WRAP, 0, 0, 0, 12));
        }
        if (history.size() > count) {
            TextView more = actionButton("查看更多记录（还有 " + (history.size() - count) + " 条）", () -> {
                historyLimit += 30;
                refreshHistorySection();
            });
            content.addView(more, marginParams(MATCH, WRAP, 0, 0, 0, 6));
        }
    }

    private void refreshHistorySection() {
        if (historySection == null) return;
        historySection.removeAllViews();
        addHistorySection(historySection);
    }

    private void showDataManagementMenu() {
        new AlertDialog.Builder(this)
                .setTitle("数据管理 · " + measurements.size() + " 条记录")
                .setItems(new String[]{"导出报告（CSV）", "备份全部数据（JSON）", "导入数据（CSV 或 JSON）", "服务器设置"},
                        (dialog, which) -> {
                            if (which == 0) createReportFile();
                            else if (which == 1) createBackupFile();
                            else if (which == 2) chooseImportFile();
                            else if (which == 3) ServerSettingsDialog.show(this);
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
        if (requestCode == REQUEST_CAPTURE_PRESSURE) {
            if (resultCode == RESULT_OK && data != null) saveCapturedMeasurement(data);
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_REPORT || requestCode == REQUEST_EXPORT_BACKUP) {
            exportTo(uri, requestCode);
        } else if (requestCode == REQUEST_IMPORT_DATA) {
            previewImport(uri);
        }
    }

    private void saveCapturedMeasurement(Intent data) {
        int systolic = data.getIntExtra(OcrCaptureActivity.EXTRA_SYSTOLIC, 0);
        int diastolic = data.getIntExtra(OcrCaptureActivity.EXTRA_DIASTOLIC, 0);
        int pulse = data.getIntExtra(OcrCaptureActivity.EXTRA_PULSE, 0);
        long time = data.getLongExtra(OcrCaptureActivity.EXTRA_TIME, System.currentTimeMillis());
        if (!BloodPressureOcrParser.inRange(0, systolic) || !BloodPressureOcrParser.inRange(1, diastolic) ||
                !BloodPressureOcrParser.inRange(2, pulse) || diastolic >= systolic) return;
        String note = data.getStringExtra(OcrCaptureActivity.EXTRA_NOTE);
        database.insert(systolic, diastolic, pulse, time, note, "camera");
        MeasurementUploader.onDataChanged(this);
        if (selectedDateStart != null) setSelectedDate(time);
        render();
        Toast.makeText(this, "拍照记录已保存", Toast.LENGTH_SHORT).show();
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
                            if (inserted > 0) MeasurementUploader.onDataChanged(this);
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
        LinearLayout card = panel();
        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.TOP);
        LinearLayout timeBlock = new LinearLayout(this);
        timeBlock.setOrientation(LinearLayout.VERTICAL);
        timeBlock.addView(label(selectedDateLabel(item.measuredAt), 17, MUTED, true));
        timeBlock.addView(label(measurementTime(item.measuredAt), 22, INK, true),
                marginParams(MATCH, WRAP, 0, 3, 0, 0));
        heading.addView(timeBlock, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView more = actionButton("•••", () -> showRecordMenu(item));
        more.setTextSize(22);
        more.setContentDescription("更多操作：" + measurementDateTime(item.measuredAt));
        more.setBackground(tappable(BG, 12, 0));
        heading.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.addView(heading);

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        card.addView(divider, marginParams(MATCH, dp(1), 0, 14, 0, 14));
        card.addView(measurementValues(item, false));
        card.addView(statusBadge(statusFor(item.systolic, item.diastolic)),
                marginParams(WRAP, WRAP, 0, 14, 0, 0));
        if (!item.note.trim().isEmpty()) {
            TextView note = label("备注：" + item.note, 16, MUTED, false);
            note.setLineSpacing(dp(3), 1);
            card.addView(note, marginParams(MATCH, WRAP, 0, 10, 0, 0));
        }
        return card;
    }

    private boolean needsWideText() {
        return getResources().getConfiguration().fontScale > 1.25f ||
                getResources().getConfiguration().screenWidthDp < 340;
    }

    private View measurementValues(Measurement item, boolean summary) {
        String[] names = {"高压", "低压", "脉搏"};
        String[] units = {"mmHg", "mmHg", "次/分"};
        String[] numbers = item == null ? new String[]{"--", "--", "--"} :
                new String[]{String.valueOf(item.systolic), String.valueOf(item.diastolic), String.valueOf(item.pulse)};
        int[] colors = item == null ? new int[]{MUTED, MUTED, MUTED} :
                new int[]{statusForSystolic(item.systolic).color, statusForDiastolic(item.diastolic).color, PULSE};
        LinearLayout values = new LinearLayout(this);
        values.setOrientation(needsWideText() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            if (needsWideText()) {
                LinearLayout line = horizontal();
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.addView(label(names[i], 18, MUTED, true), new LinearLayout.LayoutParams(0, WRAP, 1));
                line.addView(label(numbers[i], 34, colors[i], true));
                line.addView(label(units[i], 14, MUTED, false), marginParams(WRAP, WRAP, 10, 0, 0, 0));
                values.addView(line, marginParams(MATCH, WRAP, 0, i == 0 ? 0 : 10, 0, 0));
            } else {
                if (summary && i > 0) values.addView(verticalDivider());
                View metric = summary ? summaryMetric(names[i], numbers[i], units[i], colors[i]) :
                        recordMetric(names[i], numbers[i], units[i], colors[i]);
                values.addView(metric, new LinearLayout.LayoutParams(0, WRAP, 1));
            }
        }
        return values;
    }

    private View recordMetric(String name, String value, String unit, int color) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(label(name, 16, MUTED, true));
        column.addView(label(value, 32, color, true),
                marginParams(MATCH, WRAP, 0, 4, 0, 0));
        column.addView(label(unit, 13, MUTED, false), marginParams(MATCH, WRAP, 0, 2, 0, 0));
        column.setContentDescription(name + " " + value + " " + unit);
        column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return column;
    }

    private void showRecordMenu(Measurement item) {
        new AlertDialog.Builder(this)
                .setTitle(measurementDateTime(item.measuredAt))
                .setItems(new String[]{"编辑这条记录", "删除这条记录"}, (dialog, which) -> {
                    if (which == 0) showMeasurementDialog(item);
                    else confirmDelete(item);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private View footer() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(16), dp(10), dp(16), dp(10));
        footer.setBackgroundColor(SURFACE);
        Button add = new Button(this);
        add.setText("手动录入");
        add.setTextSize(20);
        add.setTextColor(ACCENT);
        add.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add.setAllCaps(false);
        add.setPadding(dp(16), dp(12), dp(16), dp(12));
        add.setBackground(tappable(0xFFEBF4FF, 14, 0));
        add.setMinHeight(dp(58));
        add.setOnClickListener(v -> showMeasurementDialog(null));
        LinearLayout entries = horizontal();
        entries.addView(add, new LinearLayout.LayoutParams(0, WRAP, 1));
        Button camera = new Button(this);
        camera.setText("拍照录入");
        camera.setTextSize(20);
        camera.setTextColor(SURFACE);
        camera.setAllCaps(false);
        camera.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        camera.setMinHeight(dp(58));
        camera.setPadding(dp(12), dp(12), dp(12), dp(12));
        camera.setBackground(tappable(ACCENT, 14, 0));
        camera.setContentDescription("打开相机，拍照识别血压和脉搏");
        camera.setOnClickListener(v -> startActivityForResult(new Intent(this, OcrCaptureActivity.class), REQUEST_CAPTURE_PRESSURE));
        LinearLayout.LayoutParams cameraParams = new LinearLayout.LayoutParams(0, WRAP, 1);
        cameraParams.setMargins(dp(12), 0, 0, 0);
        entries.addView(camera, cameraParams);
        footer.addView(entries, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView note = label("记录用于趋势观察，不替代专业诊断", 13, MUTED, false);
        note.setGravity(Gravity.CENTER);
        footer.addView(note, marginParams(MATCH, WRAP, 0, 7, 0, 0));
        return footer;
    }

    private void selectDays(int days) {
        if (selectedDateStart == null && selectedDays == days) return;
        boolean leavingDateFilter = selectedDateStart != null;
        selectedDays = days;
        selectedDateStart = null;
        selectedDateEnd = null;
        // Keep the mounted chart and ScrollView; rebuilding them exposes a blank frame
        // before the old scroll offset can be restored.
        updateChartControls();
        if (leavingDateFilter) refreshHistorySection();
    }

    private void chooseTrendDate() {
        Calendar day = Calendar.getInstance();
        if (selectedDateStart != null) day.setTimeInMillis(selectedDateStart);
        DatePickerDialog picker = new DatePickerDialog(this, (view, year, month, date) -> {
            setSelectedDate(year, month, date);
            updateChartControls();
            refreshHistorySection();
        }, day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        picker.show();
    }

    private void selectMetric(boolean pulse) {
        if (showPulse == pulse) return;
        showPulse = pulse;
        refreshChartLegend();
        updateChartControls();
    }

    private void updateChartControls() {
        if (days7Button == null || chart == null) return;
        styleSegment(days7Button, selectedDateStart == null && selectedDays == 7);
        styleSegment(days30Button, selectedDateStart == null && selectedDays == 30);
        styleSegment(allDaysButton, selectedDateStart == null && selectedDays == 0);
        styleSegment(dateButton, selectedDateStart != null);
        styleSegment(pressureButton, !showPulse);
        styleSegment(pulseButton, showPulse);
        chart.setData(measurements, selectedDays, showPulse, selectedDateStart, selectedDateEnd);
    }

    private void refreshChartLegend() {
        if (chartLegend == null) return;
        chartLegend.removeAllViews();
        if (showPulse) {
            chartLegend.addView(legendItem("脉搏 · 实线", PULSE, false));
        } else {
            chartLegend.addView(legendItem("高压 · 实线", SYS, false), new LinearLayout.LayoutParams(0, WRAP, 1));
            chartLegend.addView(legendItem("低压 · 虚线", DIA, true), marginWeightParams(1, 8));
        }
    }

    private void refreshChartReadout(Measurement selected, int index, int count) {
        if (chartReadoutLabel == null) return;
        setControlEnabled(previousButton, selected != null && index > 0);
        setControlEnabled(nextButton, selected != null && index < count - 1);
        if (selected == null) {
            chartReadoutDate.setText(selectedDateStart == null ? "区间内暂无记录" : "这一天暂无记录");
            chartReadoutValue.setText("--");
            chartReadoutValue.setTextColor(MUTED);
            chartReadoutLabel.setText("共 0 条测量");
            return;
        }
        chartReadoutDate.setText(selectedDateLabel(selected.measuredAt) + "\n" + measurementTime(selected.measuredAt));
        String rangeLabel = selectedDateStart == null
                ? (selectedDays == 0 ? "全部测量" : "最近 " + selectedDays + " 日")
                : selectedDateLabel(selectedDateStart);
        chartReadoutLabel.setText(rangeLabel + " · 共 " + count + " 条\n已选第 " + (index + 1) + " 条");
        if (showPulse) {
            chartReadoutValue.setText(selected.pulse + " 次/分");
            chartReadoutValue.setTextColor(PULSE);
        } else {
            String text = "高压 " + selected.systolic + "  ·  低压 " + selected.diastolic +
                    " mmHg\n脉搏 " + selected.pulse + " 次/分";
            SpannableString styled = new SpannableString(text);
            colorNumber(styled, text, "高压", selected.systolic, statusForSystolic(selected.systolic).color);
            colorNumber(styled, text, "低压", selected.diastolic, statusForDiastolic(selected.diastolic).color);
            colorNumber(styled, text, "脉搏", selected.pulse, PULSE);
            chartReadoutValue.setTextColor(INK);
            chartReadoutValue.setText(styled);
        }
    }

    private void colorNumber(SpannableString styled, String text, String name, int value, int color) {
        String number = String.valueOf(value);
        int start = text.indexOf(number, text.indexOf(name));
        styled.setSpan(new ForegroundColorSpan(color), start, start + number.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private String measurementDateTime(long timestamp) {
        return selectedDateLabel(timestamp) + "  " + measurementTime(timestamp);
    }

    private String measurementTime(long timestamp) {
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
        return period + " " +
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
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setMinHeight(dp(28));
        badge.setBackground(rounded(status.background, 8, 0));
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
        inputBox.setBackground(rounded(0xFFF8FAFF, 14, ACCENT));
        EditText valueInput = numberField(values[0], names[0]);
        valueInput.setTextSize(42);
        valueInput.setSingleLine(true);
        valueInput.setMinHeight(dp(88));
        valueInput.setGravity(Gravity.CENTER_VERTICAL);
        valueInput.setPadding(0, dp(4), dp(8), dp(4));
        valueInput.setBackgroundColor(0x00000000);
        valueInput.setHint("例如 120");
        valueInput.setHintTextColor(0xFF8E8E93);
        valueInput.setSelectAllOnFocus(false);
        inputBox.addView(valueInput, new LinearLayout.LayoutParams(0, WRAP, 1));
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
        timeValue.setBackground(tappable(SURFACE, 12, LINE));
        timeValue.setMinHeight(dp(52));
        optionalDetails.addView(timeValue, new LinearLayout.LayoutParams(MATCH, WRAP));
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
        note.setHintTextColor(0xFF8E8E93);
        note.setHint("例如：起床后、运动后");
        note.setSingleLine(false);
        note.setMinLines(2);
        note.setMaxLines(3);
        note.setGravity(Gravity.TOP | Gravity.START);
        note.setPadding(dp(12), dp(10), dp(12), dp(8));
        note.setBackground(rounded(SURFACE, 12, LINE));
        if (existing != null) note.setText(existing.note);
        optionalDetails.addView(note, new LinearLayout.LayoutParams(MATCH, WRAP));
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
                window.setBackgroundDrawable(rounded(SURFACE, 22, 0));
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
                valueInput.setHint(new String[]{"例如 120", "例如 80", "例如 70"}[step]);
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
                    String savedNote = note.getText().toString().trim();
                    database.insert(sysValue, diaValue, pulseValue, selected.getTimeInMillis(), savedNote);
                } else {
                    database.update(existing.id, sysValue, diaValue, pulseValue, selected.getTimeInMillis(), note.getText().toString());
                }
                MeasurementUploader.onDataChanged(this);
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
                    MeasurementUploader.onDataChanged(this);
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
        panel.setPadding(dp(16), dp(18), dp(16), dp(18));
        panel.setBackground(rounded(SURFACE, 18, 0));
        return panel;
    }

    private TextView segment(String text, boolean selected, Runnable action) {
        TextView button = label(text, 16, selected ? ACCENT : MUTED, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), dp(8), dp(6), dp(8));
        button.setMinHeight(dp(48));
        button.setBackground(tappable(selected ? SURFACE : 0x00000000, 9, 0));
        button.setElevation(selected ? dp(1) : 0);
        button.setSelected(selected);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void styleSegment(TextView view, boolean selected) {
        if (view.isSelected() == selected) return;
        view.setSelected(selected);
        view.setTextColor(selected ? ACCENT : MUTED);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setBackground(tappable(selected ? SURFACE : 0x00000000, 9, 0));
        view.setElevation(selected ? dp(1) : 0);
    }

    private TextView actionButton(String text, Runnable action) {
        TextView button = label(text, 15, ACCENT, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), dp(8), dp(6), dp(8));
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setBackground(tappable(0xFFEBF4FF, 11, 0));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void setControlEnabled(TextView button, boolean enabled) {
        if (button == null || button.isEnabled() == enabled) return;
        button.setEnabled(enabled);
        button.setTextColor(enabled ? ACCENT : 0xFF8E8E93);
        button.setBackground(tappable(enabled ? 0xFFEBF4FF : BG, 11, 0));
    }

    private RippleDrawable tappable(int color, int radiusDp, int strokeColor) {
        return new RippleDrawable(ColorStateList.valueOf(0x22007AFF),
                rounded(color, radiusDp, strokeColor), rounded(SURFACE, radiusDp, 0));
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

package com.pressurediary.app;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Camera -> local OCR -> editable review. The caller saves only after explicit confirmation. */
public final class OcrCaptureActivity extends Activity {
    static final String EXTRA_SYSTOLIC = "ocr.systolic";
    static final String EXTRA_DIASTOLIC = "ocr.diastolic";
    static final String EXTRA_PULSE = "ocr.pulse";
    static final String EXTRA_TIME = "ocr.time";
    static final String EXTRA_NOTE = "ocr.note";
    private static final int TAKE_PHOTO = 1;
    private static final int BG = 0xFFF2F2F7, WHITE = 0xFFFFFFFF, INK = 0xFF1C1C1E;
    private static final int MUTED = 0xFF636366, BLUE = 0xFF0066CC, LINE = 0xFFE5E5EA;
    private static final int MATCH = -1, WRAP = -2;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String[] values = {"", "", ""};
    private final EditText[] fields = new EditText[3];
    private BloodPressureOcr ocr;
    private File photoFile;
    private File previousPhotoFile;
    private Uri photoUri;
    private Bitmap photo;
    private PhotoCropView cropView;
    private float[] screenCorners;
    private EditText noteField;
    private String note = "";
    private String message = "拍摄时请让血压计屏幕清晰、正对镜头";
    private long measuredAt = System.currentTimeMillis();
    private int generation;
    private boolean busy, cropMode, cameraPending;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(WHITE);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        ocr = new BloodPressureOcr(this);
        if (state != null) {
            String name = state.getString("photoName");
            if (name != null && name.matches("capture-[a-f0-9-]+\\.jpg")) {
                photoFile = new File(captureDirectory(), name);
                photoUri = FileProvider.getUriForFile(this, getPackageName() + ".camera-files", photoFile);
            }
            String previousName = state.getString("previousPhotoName");
            if (previousName != null && previousName.matches("capture-[a-f0-9-]+\\.jpg")) {
                previousPhotoFile = new File(captureDirectory(), previousName);
            }
            String[] restored = state.getStringArray("values");
            if (restored != null && restored.length == 3) System.arraycopy(restored, 0, values, 0, 3);
            measuredAt = state.getLong("measuredAt", measuredAt);
            note = state.getString("note", "");
            cameraPending = state.getBoolean("cameraPending");
            cropMode = state.getBoolean("cropMode");
            message = state.getString("message", message);
            screenCorners = state.getFloatArray("screenCorners");
            renderReview();
            if (!cameraPending && photoFile != null && photoFile.length() > 0) loadPhoto(state.getBoolean("busy"));
        } else {
            renderReview();
            openCamera();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        captureDraft();
        state.putString("photoName", photoFile == null ? null : photoFile.getName());
        state.putString("previousPhotoName", previousPhotoFile == null ? null : previousPhotoFile.getName());
        state.putStringArray("values", values);
        state.putLong("measuredAt", measuredAt);
        state.putString("note", note);
        state.putBoolean("cameraPending", cameraPending);
        state.putBoolean("cropMode", cropMode);
        state.putBoolean("busy", busy);
        if (cropMode && cropView != null) screenCorners = cropView.getCorners();
        state.putFloatArray("screenCorners", screenCorners);
        state.putString("message", message);
        super.onSaveInstanceState(state);
    }

    private File captureDirectory() {
        File directory = new File(getCacheDir(), "ocr-captures");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建照片缓存");
        return directory;
    }

    private void openCamera() {
        if (busy || cameraPending) return;
        captureDraft();
        previousPhotoFile = photoFile;
        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (intent.resolveActivity(getPackageManager()) == null) throw new IOException("手机没有可用的相机应用");
            File directory = captureDirectory();
            // Delete only this feature's old temporary photos, never user measurement data.
            File[] oldPhotos = directory.listFiles();
            if (oldPhotos != null) {
                for (File old : oldPhotos) {
                    if (old.getName().startsWith("capture-") && !old.equals(photoFile) &&
                            old.lastModified() < System.currentTimeMillis() - 86400000L)
                        old.delete();
                }
            }
            photoFile = new File(directory, "capture-" + UUID.randomUUID() + ".jpg");
            if (!photoFile.createNewFile()) throw new IOException("无法创建拍照文件");
            photoUri = FileProvider.getUriForFile(this, getPackageName() + ".camera-files", photoFile);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            intent.setClipData(ClipData.newRawUri("blood-pressure-photo", photoUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            cameraPending = true;
            startActivityForResult(intent, TAKE_PHOTO);
        } catch (Exception error) {
            cameraPending = false;
            if (photoFile != null && photoFile != previousPhotoFile) photoFile.delete();
            photoFile = previousPhotoFile;
            previousPhotoFile = null;
            message = "无法打开相机，请重试或返回手动录入。";
            renderReview();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != TAKE_PHOTO) return;
        cameraPending = false;
        if (photoUri != null) revokeUriPermission(photoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        // Full-size cameras commonly return a null Intent; the output file is authoritative.
        if (resultCode != RESULT_OK) {
            if (photoFile != null) photoFile.delete();
            photoFile = previousPhotoFile;
            previousPhotoFile = null;
            if (photoFile != null && photoFile.length() > 0) {
                photoUri = FileProvider.getUriForFile(this, getPackageName() + ".camera-files", photoFile);
                message = "拍照已取消，可以继续核对上一张照片。";
                if (photo == null) loadPhoto(false);
                else renderReview();
            } else finish();
            return;
        }
        measuredAt = System.currentTimeMillis();
        for (int i = 0; i < values.length; i++) values[i] = "";
        note = "";
        cropMode = false;
        screenCorners = null;
        photo = null;
        loadPhoto(true);
    }

    private void loadPhoto(boolean recognize) {
        final int request = ++generation;
        busy = true;
        message = "正在读取照片…";
        renderReview();
        final File file = photoFile;
        worker.execute(() -> {
            try {
                if (file == null || file.length() == 0) throw new IOException("相机没有返回照片");
                Bitmap decoded = decodePhoto(file);
                float[] detected = screenCorners == null ? LcdImageProcessor.locate(decoded) : screenCorners;
                runOnUiThread(() -> {
                    if (!active(request)) { decoded.recycle(); return; }
                    photo = decoded;
                    screenCorners = detected;
                    if (previousPhotoFile != null && !previousPhotoFile.equals(file)) previousPhotoFile.delete();
                    previousPhotoFile = null;
                    busy = false;
                    if (recognize) {
                        if (LcdScreenLocator.validQuad(screenCorners))
                            recognizePhoto(LcdImageProcessor.rectify(photo, screenCorners), true);
                        else recognizePhoto(photo, false);
                    }
                    else if (cropMode) showCrop();
                    else { message = "请对照照片核对三项数值"; renderReview(); }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!active(request)) return;
                    busy = false;
                    message = "照片读取失败，请重新拍照。";
                    renderReview();
                });
            }
        });
    }

    private static Bitmap decodePhoto(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) throw new IOException("照片无法解码");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2200) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException("照片无法解码");
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        Matrix matrix = new Matrix();
        switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.postRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.postRotate(270); matrix.postScale(-1, 1); break;
            default: break;
        }
        if (matrix.isIdentity()) return bitmap;
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    private void recognizePhoto(Bitmap input, boolean screenCrop) {
        final int request = ++generation;
        busy = true;
        cropMode = false;
        message = "正在识别高压、低压和脉搏…";
        renderReview();
        // A stalled detector must not prevent users from filling the readings themselves.
        mainHandler.postDelayed(() -> {
            if (!active(request) || !busy) return;
            ++generation;
            ocr.cancel();
            busy = false;
            message = "识别用时较长，请手动填写或重新拍照";
            renderReview();
        }, 30000);
        ocr.recognize(input, screenCrop, new BloodPressureOcr.Callback() {
            @Override public void onResult(BloodPressureOcrParser.Result result) {
                if (!active(request)) return;
                busy = false;
                Integer[] readings = {result.systolic, result.diastolic, result.pulse};
                for (int i = 0; i < readings.length; i++) values[i] = readings[i] == null ? "" : String.valueOf(readings[i]);
                message = result.count() == 0 ? "没有读清数字。请点“对齐屏幕四角”，框住整块屏幕后重试。" :
                        result.complete() && !result.uncertain ? "识别完成，请对照照片核对三项数值" :
                                "部分读数需要核对，请修改或补填后保存";
                renderReview();
            }
            @Override public void onError(Exception error) {
                if (!active(request)) return;
                busy = false;
                message = "识别未完成。请重新拍照，或手动填写三项数值。";
                renderReview();
            }
        });
    }

    private boolean active(int request) { return request == generation && !isFinishing() && !isDestroyed(); }

    private LinearLayout baseScreen() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(10), dp(16), dp(10));
        root.setFocusableInTouchMode(true);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹ 返回", 18, BLUE, true);
        back.setMinHeight(dp(48));
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(0, 0, dp(18), 0);
        back.setContentDescription("返回血压日记");
        back.setOnClickListener(v -> finish());
        header.addView(back);
        header.addView(text("拍照录入", 23, INK, true), new LinearLayout.LayoutParams(0, WRAP, 1));
        root.addView(header);
        TextView status = text(message, 18, INK, true);
        status.setLineSpacing(dp(3), 1);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, params(MATCH, WRAP, 10));
        return root;
    }

    private void renderReview() {
        LinearLayout root = baseScreen();
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        content.setPadding(0, dp(12), 0, dp(16));
        if (photo != null) {
            ImageView preview = new ImageView(this);
            preview.setImageBitmap(photo);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            preview.setBackground(background(WHITE, LINE));
            preview.setPadding(dp(8), dp(8), dp(8), dp(8));
            preview.setContentDescription("刚拍摄的血压计照片，可用于核对下方读数");
            content.addView(preview, params(MATCH, dp(210), 0));
        }
        if (busy) {
            ProgressBar progress = new ProgressBar(this);
            content.addView(progress, params(MATCH, dp(48), 12));
        }
        LinearLayout card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(background(WHITE, 0));
        String[] names = {"高压（收缩压）", "低压（舒张压）", "脉搏"};
        for (int i = 0; i < 3; i++) {
            card.addView(text(names[i], 18, INK, true), params(MATCH, WRAP, i == 0 ? 0 : 18));
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            fields[i] = new EditText(this);
            fields[i].setInputType(InputType.TYPE_CLASS_NUMBER);
            fields[i].setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
            fields[i].setSingleLine(true);
            fields[i].setTextSize(36);
            fields[i].setTextColor(INK);
            fields[i].setHintTextColor(MUTED);
            fields[i].setHint(busy ? "识别中" : "请填写");
            fields[i].setText(values[i]);
            fields[i].setSelectAllOnFocus(true);
            fields[i].setMinHeight(dp(64));
            fields[i].setContentDescription("核对或填写" + names[i]);
            fields[i].setEnabled(!busy);
            row.addView(fields[i], new LinearLayout.LayoutParams(0, WRAP, 1));
            row.addView(text(i == 2 ? "次/分" : "mmHg", 16, MUTED, false));
            card.addView(row);
        }
        content.addView(card, params(MATCH, WRAP, 14));
        TextView time = text("测量时间\n" + formattedTime(), 17, INK, true);
        time.setPadding(dp(16), dp(12), dp(16), dp(12));
        time.setMinHeight(dp(60));
        time.setBackground(background(WHITE, 0));
        time.setContentDescription("修改测量日期和时间，当前" + formattedTime());
        time.setOnClickListener(v -> { if (!busy) chooseTime(time); });
        content.addView(time, params(MATCH, WRAP, 12));
        TextView timingHelp = text("默认使用拍照时间，点上方可修改", 14, MUTED, false);
        content.addView(timingHelp, params(MATCH, WRAP, 6));
        noteField = new EditText(this);
        noteField.setTextSize(16);
        noteField.setTextColor(INK);
        noteField.setHintTextColor(MUTED);
        noteField.setHint("备注（选填）");
        noteField.setText(note);
        noteField.setEnabled(!busy);
        noteField.setMinHeight(dp(52));
        noteField.setPadding(dp(16), dp(8), dp(16), dp(8));
        noteField.setBackground(background(WHITE, 0));
        content.addView(noteField, params(MATCH, WRAP, 12));
        TextView local = text("照片仅在手机内识别，保存后清理临时照片", 13, MUTED, false);
        content.addView(local, params(MATCH, WRAP, 12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        Button retake = button("重新拍照", false, this::openCamera);
        retake.setEnabled(!busy);
        actions.addView(retake, new LinearLayout.LayoutParams(0, WRAP, 1));
        Button crop = button("对齐屏幕四角", false, () -> { captureDraft(); showCrop(); });
        crop.setEnabled(!busy && photo != null);
        LinearLayout.LayoutParams cropParams = new LinearLayout.LayoutParams(0, WRAP, 1);
        cropParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(crop, cropParams);
        root.addView(actions, params(MATCH, WRAP, 4));
        Button save = button(busy ? "正在识别…" : "核对无误，保存记录", true, this::saveReading);
        save.setEnabled(!busy && photo != null);
        root.addView(save, params(MATCH, WRAP, 10));
        setContentView(root);
        root.requestFocus();
    }

    private void showCrop() {
        if (photo == null || busy) return;
        cropMode = true;
        message = "拖动四个蓝点，对齐整块屏幕的四角";
        LinearLayout root = baseScreen();
        root.addView(text("请包含高压、低压、脉搏三行数字", 16, MUTED, false), params(MATCH, WRAP, 6));
        cropView = new PhotoCropView(this);
        cropView.setBitmap(photo);
        cropView.setCorners(screenCorners);
        root.addView(cropView, new LinearLayout.LayoutParams(MATCH, 0, 1));
        LinearLayout controls = new LinearLayout(this);
        controls.addView(button("恢复选框", false, () -> cropView.resetCrop()), new LinearLayout.LayoutParams(0, WRAP, 1));
        controls.addView(button("返回核对", false, () -> { cropMode = false; message = "请核对或补填三项数值"; renderReview(); }),
                new LinearLayout.LayoutParams(0, WRAP, 1));
        root.addView(controls, params(MATCH, WRAP, 10));
        root.addView(button("识别框内读数", true, () -> {
            screenCorners = cropView.getCorners();
            recognizePhoto(cropView.croppedBitmap(), true);
        }), params(MATCH, WRAP, 10));
        setContentView(root);
    }

    private void captureDraft() {
        if (cropMode) return;
        for (int i = 0; i < fields.length; i++) if (fields[i] != null) values[i] = fields[i].getText().toString().trim();
        if (noteField != null) note = noteField.getText().toString();
    }

    private void saveReading() {
        if (busy || photo == null || isFinishing()) return;
        captureDraft();
        int[] numbers = new int[3];
        String[] names = {"高压", "低压", "脉搏"};
        for (int i = 0; i < numbers.length; i++) {
            try { numbers[i] = Integer.parseInt(values[i]); }
            catch (NumberFormatException error) { fields[i].setError("请填写" + names[i]); fields[i].requestFocus(); return; }
            if (!BloodPressureOcrParser.inRange(i, numbers[i])) {
                fields[i].setError(i == 0 ? "请填写 50–260 mmHg" : i == 1 ? "请填写 30–160 mmHg" : "请填写 30–220 次/分");
                fields[i].requestFocus();
                return;
            }
        }
        if (numbers[1] >= numbers[0]) { fields[1].setError("低压应低于高压，请核对"); fields[1].requestFocus(); return; }
        Intent result = new Intent();
        result.putExtra(EXTRA_SYSTOLIC, numbers[0]);
        result.putExtra(EXTRA_DIASTOLIC, numbers[1]);
        result.putExtra(EXTRA_PULSE, numbers[2]);
        result.putExtra(EXTRA_TIME, measuredAt);
        result.putExtra(EXTRA_NOTE, note);
        setResult(RESULT_OK, result);
        finish();
    }

    private void chooseTime(TextView display) {
        captureDraft();
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(measuredAt);
        new DatePickerDialog(this, (view, year, month, day) -> {
            calendar.set(year, month, day);
            new TimePickerDialog(this, (time, hour, minute) -> {
                calendar.set(Calendar.HOUR_OF_DAY, hour);
                calendar.set(Calendar.MINUTE, minute);
                calendar.set(Calendar.SECOND, 0);
                calendar.set(Calendar.MILLISECOND, 0);
                measuredAt = calendar.getTimeInMillis();
                display.setText("测量时间\n" + formattedTime());
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show();
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private String formattedTime() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(measuredAt);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        String period = hour < 5 ? "凌晨" : hour < 12 ? "上午" : hour < 18 ? "下午" : "晚上";
        return new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(new Date(measuredAt)) + "  " + period + " " +
                new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(measuredAt));
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }
    private Button button(String title, boolean primary, Runnable action) {
        Button view = new Button(this);
        view.setText(title); view.setTextSize(18); view.setAllCaps(false);
        view.setTextColor(primary ? WHITE : BLUE);
        view.setMinHeight(dp(56)); view.setPadding(dp(8), dp(12), dp(8), dp(12));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x220066CC), background(primary ? BLUE : WHITE, 0), null));
        view.setOnClickListener(v -> action.run());
        return view;
    }
    private GradientDrawable background(int color, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(16));
        if (border != 0) drawable.setStroke(dp(1), border);
        return drawable;
    }
    private LinearLayout.LayoutParams params(int width, int height, int top) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(width, height);
        result.setMargins(0, dp(top), 0, 0); return result;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        ++generation;
        mainHandler.removeCallbacksAndMessages(null);
        if (ocr != null) ocr.close();
        worker.shutdownNow();
        if (isFinishing()) {
            if (photoFile != null) photoFile.delete();
            if (previousPhotoFile != null) previousPhotoFile.delete();
        }
        super.onDestroy();
    }
}

package com.pressurediary.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Bundled LCD-specific Tesseract model. Native calls and recycling share one worker. */
final class BloodPressureOcr implements AutoCloseable {
    interface Callback {
        void onResult(BloodPressureOcrParser.Result result);
        void onError(Exception error);
    }

    private static final String MODEL = "ssd_int";
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean closed;
    private TessBaseAPI engine;

    BloodPressureOcr(Context context) { this.context = context.getApplicationContext(); }

    void recognize(Bitmap bitmap, boolean screenCrop, Callback callback) {
        if (closed) { if (screenCrop) bitmap.recycle(); return; }
        final int request = generation.incrementAndGet();
        worker.execute(() -> {
            Bitmap screen = null;
            try {
                if (!active(request)) return;
                if (screenCrop) screen = bitmap;
                else {
                    float[] corners = LcdImageProcessor.locate(bitmap);
                    if (corners == null) {
                        deliver(request, callback, new BloodPressureOcrParser.Result(null,null,null,true,0));
                        return;
                    }
                    screen = LcdImageProcessor.rectify(bitmap, corners);
                }
                ensureEngine();
                List<List<LcdReadingConsensus.Candidate>> rows = new ArrayList<>();
                for (int field = 0; field < 3 && active(request); field++) {
                    List<LcdReadingConsensus.Candidate> candidates = new ArrayList<>();
                    Bitmap raw = LcdImageProcessor.row(screen, field);
                    try {
                        for (int variant = 0; variant < 3 && active(request); variant++) {
                            Bitmap prepared = LcdImageProcessor.prepare(raw, variant);
                            try {
                                engine.setImage(prepared);
                                // RAW_LINE handles separated LCD segments without ordinary text-line detection.
                                String text = engine.getUTF8Text();
                                candidates.add(LcdReadingConsensus.parse(text, engine.meanConfidence(), field));
                                engine.clear();
                            } finally { prepared.recycle(); }
                        }
                    } finally { raw.recycle(); }
                    rows.add(candidates);
                }
                if (active(request)) deliver(request, callback, LcdReadingConsensus.result(rows));
            } catch (Exception | LinkageError error) {
                main.post(() -> {
                    if (active(request)) callback.onError(new IOException("液晶数字识别未完成", error));
                });
            } finally {
                if (screen != null) screen.recycle();
                // A manual perspective crop is owned here even when a queued request is cancelled.
                if (screenCrop && screen != bitmap && !bitmap.isRecycled()) bitmap.recycle();
            }
        });
    }

    void cancel() { generation.incrementAndGet(); }

    private void deliver(int request, Callback callback, BloodPressureOcrParser.Result result) {
        main.post(() -> { if (active(request)) callback.onResult(result); });
    }

    private boolean active(int request) { return !closed && request == generation.get(); }

    private void ensureEngine() throws IOException, NoSuchAlgorithmException {
        if (engine != null) return;
        File root = new File(context.getNoBackupFilesDir(), "lcd-ocr-v1");
        File directory = new File(root, "tessdata");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建识别模型目录");
        File target = new File(directory, MODEL + ".traineddata");
        // Copy from the APK using a temporary file, so interrupted first-run setup can recover.
        if (!target.isFile() || target.length() != MODEL_BYTES) {
            File temporary = new File(directory, MODEL + ".partial");
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream input = context.getAssets().open("tessdata/" + MODEL + ".traineddata");
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer,0,count);
                    hash.update(buffer,0,count);
                }
                output.getFD().sync();
            }
            StringBuilder digest = new StringBuilder();
            for (byte value : hash.digest()) digest.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            if (temporary.length() != MODEL_BYTES || !MODEL_SHA256.equals(digest.toString())) {
                temporary.delete();
                throw new IOException("识别模型校验失败");
            }
            if (target.exists() && !target.delete()) throw new IOException("无法更新识别模型");
            if (!temporary.renameTo(target)) throw new IOException("无法保存识别模型");
        }
        TessBaseAPI candidate = new TessBaseAPI();
        try {
            if (!candidate.init(root.getAbsolutePath(), MODEL, TessBaseAPI.OEM_LSTM_ONLY))
                throw new IOException("无法初始化识别模型");
            candidate.setVariable("tessedit_char_whitelist", "0123456789");
            candidate.setVariable("user_defined_dpi", "300");
            candidate.setPageSegMode(TessBaseAPI.PageSegMode.PSM_RAW_LINE);
            engine = candidate;
        } finally { if (engine != candidate) candidate.recycle(); }
    }

    // Shreeshrii/tessdata_ssd, revision e493a10a4bf81632506cf24ba99d83854afff9f6.
    private static final long MODEL_BYTES = 1442809L;
    private static final String MODEL_SHA256 = "557759383e030694420ee9f6a88e824abae4acf17f7b94415ca07e1152339339";

    @Override public void close() {
        if (closed) return;
        closed = true;
        cancel();
        main.removeCallbacksAndMessages(null);
        worker.execute(() -> { if (engine != null) { engine.recycle(); engine = null; } });
        worker.shutdown();
    }
}

package com.pressurediary.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;

/** Screen geometry and digit-only image preparation; it does not guess numerical values. */
final class LcdImageProcessor {
    static final float[][] ROWS = {{.22f,.16f,.70f,.43f}, {.22f,.43f,.70f,.68f}, {.22f,.68f,.70f,.90f}};

    static float[] locate(Bitmap photo) {
        float scale = Math.min(1f, 640f / Math.max(photo.getWidth(), photo.getHeight()));
        Bitmap small = Bitmap.createScaledBitmap(photo, Math.max(1, Math.round(photo.getWidth() * scale)),
                Math.max(1, Math.round(photo.getHeight() * scale)), true);
        int[] pixels = new int[small.getWidth() * small.getHeight()];
        small.getPixels(pixels, 0, small.getWidth(), 0, 0, small.getWidth(), small.getHeight());
        float[] result = LcdScreenLocator.find(pixels, small.getWidth(), small.getHeight());
        if (small != photo) small.recycle();
        return result;
    }

    static Bitmap rectify(Bitmap photo, float[] corners) {
        if (!LcdScreenLocator.validQuad(corners)) throw new IllegalArgumentException("请对齐屏幕四角");
        float[] source = new float[8];
        for (int i = 0; i < 8; i++) source[i] = corners[i] * (i % 2 == 0 ? photo.getWidth() : photo.getHeight());
        float width = Math.max(distance(source, 0, 1), distance(source, 3, 2));
        float height = Math.max(distance(source, 0, 3), distance(source, 1, 2));
        float scale = Math.min(2f, 1400f / Math.max(width, height));
        int outWidth = Math.max(32, Math.round(width * scale)), outHeight = Math.max(32, Math.round(height * scale));
        float[] destination = {0,0, outWidth,0, outWidth,outHeight, 0,outHeight};
        Matrix warp = new Matrix();
        if (!warp.setPolyToPoly(source, 0, destination, 0, 4)) throw new IllegalArgumentException("屏幕选框无效，请调整四角");
        Bitmap result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(0xFFFFFFFF);
        canvas.drawBitmap(photo, warp, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return result;
    }

    static Bitmap row(Bitmap screen, int field) {
        float[] bounds = ROWS[field];
        int x = Math.round(screen.getWidth() * bounds[0]), y = Math.round(screen.getHeight() * bounds[1]);
        int right = Math.round(screen.getWidth() * bounds[2]), bottom = Math.round(screen.getHeight() * bounds[3]);
        return Bitmap.createBitmap(screen, x, y, Math.max(1, right - x), Math.max(1, bottom - y));
    }

    static Bitmap prepare(Bitmap input, int variant) {
        int width = input.getWidth(), height = input.getHeight();
        int[] pixels = new int[width * height], histogram = new int[256];
        input.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            pixels[i] = (((color >>> 16) & 255) * 77 + ((color >>> 8) & 255) * 150 + (color & 255) * 29) >>> 8;
            histogram[pixels[i]]++;
        }
        int threshold = otsu(histogram, pixels.length);
        // Two binary variants tolerate weak LCD strokes and reflections, with a gray variant as a cross-check.
        int cutoff = Math.min(210, threshold + (variant == 2 ? 20 : 0));
        int left = width, top = height, right = -1, bottom = -1;
        for (int i = 0; i < pixels.length; i++) {
            int gray = pixels[i];
            if (gray < threshold) {
                int x = i % width, y = i / width;
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
            int value = variant == 0 ? gray : gray < cutoff ? 0 : 255;
            pixels[i] = 0xFF000000 | value << 16 | value << 8 | value;
        }
        if (right <= left || bottom <= top) { left = 0; top = 0; right = width - 1; bottom = height - 1; }
        Bitmap gray = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        int digitWidth = right - left + 1, digitHeight = bottom - top + 1;
        // Very large line images can split a seven-segment "7" into several "1" strokes.
        // Normalize digit height before feeding the LSTM, regardless of camera resolution.
        float scale = Math.min(5f, Math.min(160f / digitHeight, 600f / digitWidth));
        int outWidth = Math.max(1, Math.round(digitWidth * scale)), outHeight = Math.max(1, Math.round(digitHeight * scale));
        Bitmap result = Bitmap.createBitmap(outWidth + 48, outHeight + 48, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(0xFFFFFFFF);
        canvas.drawBitmap(gray, new Rect(left, top, right + 1, bottom + 1),
                new Rect(24,24, outWidth + 24,outHeight + 24), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        gray.recycle();
        return result;
    }

    private static float distance(float[] points, int a, int b) {
        return (float) Math.hypot(points[a * 2] - points[b * 2], points[a * 2 + 1] - points[b * 2 + 1]);
    }

    private static int otsu(int[] histogram, int count) {
        long sum = 0, sumBelow = 0;
        for (int i = 0; i < 256; i++) sum += (long) i * histogram[i];
        int below = 0, best = 140;
        double bestVariance = -1;
        for (int i = 0; i < 255; i++) {
            below += histogram[i];
            sumBelow += (long) i * histogram[i];
            if (below == 0 || below == count) continue;
            double difference = (double) sumBelow / below - (double) (sum - sumBelow) / (count - below);
            double variance = (double) below * (count - below) * difference * difference;
            if (variance > bestVariance) { bestVariance = variance; best = i + 1; }
        }
        return Math.max(50, Math.min(190, best));
    }
}

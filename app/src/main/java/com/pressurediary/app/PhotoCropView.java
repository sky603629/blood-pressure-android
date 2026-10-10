package com.pressurediary.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Four independent corners correct perspective; all processing stays on the device. */
final class PhotoCropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF photoRect = new RectF();
    private final float[] corners = {.08f,.08f, .92f,.08f, .92f,.92f, .08f,.92f};
    private final float[] display = new float[8];
    private final Path frame = new Path();
    private final Path shade = new Path();
    private Bitmap bitmap;
    private int dragging = -1;
    private float lastX, lastY;

    PhotoCropView(Context context) {
        super(context);
        setContentDescription("拖动蓝色框的四角，分别对齐血压计整块屏幕的四角");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setBitmap(Bitmap bitmap) { this.bitmap = bitmap; invalidate(); }
    void setCorners(float[] detected) {
        if (LcdScreenLocator.validQuad(detected)) System.arraycopy(detected,0,corners,0,8);
        invalidate();
    }
    float[] getCorners() { return corners.clone(); }

    void resetCrop() { setCorners(new float[]{.08f,.08f, .92f,.08f, .92f,.92f, .08f,.92f}); }
    Bitmap croppedBitmap() { return bitmap == null ? null : LcdImageProcessor.rectify(bitmap, corners); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFF17191D);
        if (bitmap == null) return;
        float scale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        photoRect.set((getWidth() - width) / 2,(getHeight() - height) / 2,
                (getWidth() + width) / 2,(getHeight() + height) / 2);
        paint.setColor(0xFFFFFFFF); paint.setStyle(Paint.Style.FILL);
        canvas.drawBitmap(bitmap,null,photoRect,paint);
        frame.rewind();
        for (int i = 0; i < 4; i++) {
            display[i * 2] = photoRect.left + corners[i * 2] * photoRect.width();
            display[i * 2 + 1] = photoRect.top + corners[i * 2 + 1] * photoRect.height();
            if (i == 0) frame.moveTo(display[0], display[1]);
            else frame.lineTo(display[i * 2], display[i * 2 + 1]);
        }
        frame.close();
        shade.rewind(); shade.setFillType(Path.FillType.EVEN_ODD);
        shade.addRect(photoRect, Path.Direction.CW); shade.addPath(frame);
        paint.setColor(0xAA000000); canvas.drawPath(shade, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(0xFF62B6FF);
        canvas.drawPath(frame, paint);
        for (float band : new float[]{.43f,.68f}) {
            float leftX = display[0] + (display[6] - display[0]) * band;
            float leftY = display[1] + (display[7] - display[1]) * band;
            float rightX = display[2] + (display[4] - display[2]) * band;
            float rightY = display[3] + (display[5] - display[3]) * band;
            canvas.drawLine(leftX,leftY,rightX,rightY,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) canvas.drawCircle(display[i * 2],display[i * 2 + 1],dp(11),paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || photoRect.isEmpty()) return false;
        float x = clamp((event.getX() - photoRect.left) / photoRect.width());
        float y = clamp((event.getY() - photoRect.top) / photoRect.height());
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float nearest = dp(48); dragging = -1;
            for (int i = 0; i < 4; i++) {
                float distance = (float) Math.hypot(event.getX() - display[i * 2],event.getY() - display[i * 2 + 1]);
                if (distance < nearest) { nearest = distance; dragging = i; }
            }
            if (dragging < 0 && contains(x,y)) dragging = 4;
            if (dragging < 0) return false;
            lastX = x; lastY = y;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && dragging >= 0) {
            float[] proposed = corners.clone();
            if (dragging == 4) {
                float minX = 1, maxX = 0, minY = 1, maxY = 0;
                for (int i = 0; i < 4; i++) {
                    minX = Math.min(minX,corners[i * 2]); maxX = Math.max(maxX,corners[i * 2]);
                    minY = Math.min(minY,corners[i * 2 + 1]); maxY = Math.max(maxY,corners[i * 2 + 1]);
                }
                float dx = Math.max(-minX,Math.min(1 - maxX,x - lastX));
                float dy = Math.max(-minY,Math.min(1 - maxY,y - lastY));
                for (int i = 0; i < 4; i++) { proposed[i * 2] += dx; proposed[i * 2 + 1] += dy; }
            } else { proposed[dragging * 2] = x; proposed[dragging * 2 + 1] = y; }
            if (LcdScreenLocator.validQuad(proposed)) System.arraycopy(proposed,0,corners,0,8);
            lastX = x; lastY = y; invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            dragging = -1; getParent().requestDisallowInterceptTouchEvent(false);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return true;
        }
        return false;
    }

    private boolean contains(float x, float y) {
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            if ((corners[next * 2] - corners[i * 2]) * (y - corners[i * 2 + 1]) -
                    (corners[next * 2 + 1] - corners[i * 2 + 1]) * (x - corners[i * 2]) < 0) return false;
        }
        return true;
    }
    private static float clamp(float value) { return Math.max(0,Math.min(1,value)); }
    @Override public boolean performClick() { return super.performClick(); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}

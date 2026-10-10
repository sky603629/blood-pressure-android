package com.pressurediary.app;

/** Finds a bright LCD with three dark digit rows. No reading values are inferred here. */
final class LcdScreenLocator {
    static float[] find(int[] pixels, int width, int height) {
        if (width < 24 || height < 24 || pixels.length != width * height) return null;
        int[] gray = new int[pixels.length];
        int[] histogram = new int[256];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            gray[i] = (((color >>> 16) & 255) * 77 + ((color >>> 8) & 255) * 150 +
                    (color & 255) * 29) >>> 8;
            histogram[gray[i]]++;
        }
        int median = percentile(histogram, pixels.length, .50f);
        int bright = percentile(histogram, pixels.length, .94f);
        float bestScore = 0;
        float[] best = null;
        for (int threshold : new int[]{Math.max(145, (median + bright) / 2),
                Math.max(155, bright - 22), Math.max(165, bright - 8)}) {
            boolean[] visited = new boolean[pixels.length];
            int[] queue = new int[pixels.length];
            for (int start = 0; start < pixels.length; start++) {
                if (visited[start] || gray[start] < threshold) continue;
                int head = 0, tail = 1;
                queue[0] = start;
                visited[start] = true;
                int left = width, top = height, right = 0, bottom = 0;
                int minSum = Integer.MAX_VALUE, maxSum = Integer.MIN_VALUE;
                int minDiff = Integer.MAX_VALUE, maxDiff = Integer.MIN_VALUE;
                int[] corners = new int[8];
                long totalGray = 0;
                while (head < tail) {
                    int index = queue[head++], x = index % width, y = index / width;
                    left = Math.min(left, x); right = Math.max(right, x);
                    top = Math.min(top, y); bottom = Math.max(bottom, y);
                    totalGray += gray[index];
                    if (x + y < minSum) { minSum = x + y; corners[0] = x; corners[1] = y; }
                    if (x - y > maxDiff) { maxDiff = x - y; corners[2] = x; corners[3] = y; }
                    if (x + y > maxSum) { maxSum = x + y; corners[4] = x; corners[5] = y; }
                    if (x - y < minDiff) { minDiff = x - y; corners[6] = x; corners[7] = y; }
                    if (x > 0) tail = add(index - 1, threshold, gray, visited, queue, tail);
                    if (x + 1 < width) tail = add(index + 1, threshold, gray, visited, queue, tail);
                    if (y > 0) tail = add(index - width, threshold, gray, visited, queue, tail);
                    if (y + 1 < height) tail = add(index + width, threshold, gray, visited, queue, tail);
                }
                int boxWidth = right - left + 1, boxHeight = bottom - top + 1;
                float ratio = (float) boxWidth / boxHeight;
                float area = (float) tail / pixels.length;
                if (area < .025f || area > .78f || ratio < .42f || ratio > 1.45f ||
                        boxWidth < width * .16f || boxHeight < height * .15f ||
                        tail < boxWidth * boxHeight * .60f || left < 1 || right >= width - 1 ||
                        top < 1 || bottom >= height - 1) continue;
                // A bright wall/table is insufficient: all three reading bands must contain ink.
                int inkThreshold = Math.min(160, (int) (totalGray / tail) - 45);
                float minInk = 1;
                float[][] rows = {{.24f,.16f,.70f,.43f},{.24f,.43f,.70f,.68f},{.24f,.68f,.70f,.90f}};
                for (float[] row : rows) {
                    int x0 = left + Math.round(boxWidth * row[0]), x1 = left + Math.round(boxWidth * row[2]);
                    int y0 = top + Math.round(boxHeight * row[1]), y1 = top + Math.round(boxHeight * row[3]);
                    int ink = 0, count = 0;
                    for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                        count++;
                        if (gray[y * width + x] < inkThreshold) ink++;
                    }
                    float density = count == 0 ? 0 : (float) ink / count;
                    if (density > .48f) density = 0;
                    minInk = Math.min(minInk, density);
                }
                if (minInk < .025f) continue;
                float score = tail * Math.min(.14f, minInk);
                if (score > bestScore) {
                    float[] normalized = new float[8];
                    for (int i = 0; i < 8; i++) normalized[i] = (float) corners[i] / (i % 2 == 0 ? width : height);
                    if (validQuad(normalized)) { best = normalized; bestScore = score; }
                }
            }
        }
        return best;
    }

    private static int add(int index, int threshold, int[] gray, boolean[] visited, int[] queue, int tail) {
        if (!visited[index] && gray[index] >= threshold) {
            visited[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    static boolean validQuad(float[] corners) {
        if (corners == null || corners.length != 8) return false;
        for (float value : corners) if (Float.isNaN(value) || Float.isInfinite(value) || value < 0 || value > 1) return false;
        float area = 0;
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4, after = (i + 2) % 4;
            float x = corners[next * 2] - corners[i * 2], y = corners[next * 2 + 1] - corners[i * 2 + 1];
            float dx = corners[after * 2] - corners[next * 2], dy = corners[after * 2 + 1] - corners[next * 2 + 1];
            if (x * dy - y * dx <= .001f) return false;
            area += corners[i * 2] * corners[next * 2 + 1] - corners[next * 2] * corners[i * 2 + 1];
        }
        return area > .012f;
    }

    private static int percentile(int[] histogram, int count, float fraction) {
        int total = 0;
        for (int i = 0; i < histogram.length; i++) {
            total += histogram[i];
            if (total >= count * fraction) return i;
        }
        return 255;
    }
}

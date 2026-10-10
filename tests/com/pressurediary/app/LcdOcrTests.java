package com.pressurediary.app;

import java.util.Arrays;
import java.util.Collections;

/** Confidence/conflict and screen-geometry regressions, independent of the native engine. */
public final class LcdOcrTests {
    private static int checks;
    public static void main(String[] args) {
        check(LcdReadingConsensus.parse("109\n", 94, 0).value == 109, "line ending");
        check(LcdReadingConsensus.parse("7 1", 95, 1).value == 71, "separate digit spacing");
        for (String value : new String[]{"", "7I", "O86", "9-09", "100%", "11:37", "71/86", "0071", "711", "1119"})
            check(LcdReadingConsensus.parse(value,95,1) == null, "do not invent/correct " + value);
        check(LcdReadingConsensus.parse("29",95,1) == null, "lower range");
        check(LcdReadingConsensus.parse("261",95,0) == null, "upper range");
        check(LcdReadingConsensus.choose(Arrays.asList(candidate(71,96),candidate(71,95),null)).value == 71, "two supported readings");
        check(LcdReadingConsensus.choose(Arrays.asList(candidate(71,96),candidate(77,94),candidate(71,95))) == null, "conflict left blank");
        check(LcdReadingConsensus.choose(Arrays.asList(candidate(71,96),candidate(77,44),candidate(71,95))).value == 71, "weak conflicting candidate");
        check(LcdReadingConsensus.choose(Collections.singletonList(candidate(71,96))) == null, "single guess not auto-filled");
        check(LcdReadingConsensus.choose(Arrays.asList(candidate(71,10),candidate(71,20))) == null, "low confidence");
        BloodPressureOcrParser.Result result = LcdReadingConsensus.result(Arrays.asList(
                Arrays.asList(candidate(109,94),candidate(109,95)),
                Arrays.asList(candidate(71,96),candidate(71,96)),
                Arrays.asList(candidate(86,95),candidate(86,96))));
        check(result.complete() && !result.uncertain && result.systolic == 109 && result.diastolic == 71 && result.pulse == 86, "three separate rows");
        result = LcdReadingConsensus.result(Arrays.asList(
                Arrays.asList(candidate(109,94),candidate(109,95)), Collections.emptyList(),
                Arrays.asList(candidate(86,95),candidate(86,96))));
        check(result.diastolic == null && result.pulse == 86 && result.uncertain, "missing low pressure does not shift pulse");
        result = LcdReadingConsensus.result(Arrays.asList(
                Arrays.asList(candidate(90,94),candidate(90,95)), Arrays.asList(candidate(100,96),candidate(100,96)),
                Arrays.asList(candidate(86,95),candidate(86,96))));
        check(!result.complete() && result.uncertain, "pressure relationship");
        check(LcdScreenLocator.validQuad(new float[]{.1f,.1f,.9f,.1f,.9f,.9f,.1f,.9f}), "rectangle");
        check(LcdScreenLocator.validQuad(new float[]{.1f,.2f,.8f,.1f,.9f,.8f,.15f,.9f}), "perspective quadrilateral");
        check(!LcdScreenLocator.validQuad(new float[]{.1f,.1f,.9f,.9f,.9f,.1f,.1f,.9f}), "crossed corners");
        check(!LcdScreenLocator.validQuad(new float[]{.1f,.1f,.11f,.1f,.11f,.11f,.1f,.11f}), "too small");
        check(!LcdScreenLocator.validQuad(new float[]{Float.NaN,.1f,.9f,.1f,.9f,.9f,.1f,.9f}), "NaN");
        int width = 180, height = 300;
        int[] pixels = new int[width * height];
        Arrays.fill(pixels,0xFF777777);
        check(LcdScreenLocator.find(pixels,width,height) == null, "plain photo");
        fill(pixels,width,30,60,150,230,0xFFEEEEEE);
        check(LcdScreenLocator.find(pixels,width,height) == null, "bright blank region is not a monitor");
        fill(pixels,width,68,94,103,121,0xFF333333);
        fill(pixels,width,77,138,103,163,0xFF333333);
        fill(pixels,width,77,182,103,206,0xFF333333);
        float[] found = LcdScreenLocator.find(pixels,width,height);
        check(found != null && found[0] > .15f && found[2] < .85f, "locate three-row bright display");
        System.out.println("LCD OCR checks passed: " + checks);
    }
    private static LcdReadingConsensus.Candidate candidate(int value, int confidence) { return new LcdReadingConsensus.Candidate(value,confidence); }
    private static void fill(int[] pixels, int width, int x0,int y0,int x1,int y1,int color) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) pixels[y * width + x] = color;
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}

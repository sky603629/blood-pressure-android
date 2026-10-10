package com.pressurediary.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Host checks for reading association, not a substitute for running native OCR on actual photos. */
public final class OcrParserTests {
    private static int checks;

    public static void main(String[] args) {
        List<BloodPressureOcrParser.Word> sample = Arrays.asList(
                word("高压", 200, 473, 247, 498), word("109", 315, 455, 500, 583),
                word("低压", 200, 622, 247, 647), word("71", 350, 611, 510, 734),
                word("脉搏", 201, 779, 247, 804), word("86", 385, 767, 510, 863),
                word("9-09", 228, 377, 290, 402), word("100%", 554, 357, 620, 380),
                word("89", 826, 43, 850, 67), word("25/44", 420, 153, 520, 184),
                word("11:37", 70, 48, 150, 75));
        BloodPressureOcrParser.Result result = BloodPressureOcrParser.parse(sample);
        readings(result, 109, 71, 86, "YE692 labels and readings");
        require(result.labelledCount == 3 && !result.uncertain, "date and battery excluded");

        result = BloodPressureOcrParser.parse(Arrays.asList(
                word("１０９", 100, 100, 200, 200), word("７１", 100, 300, 200, 400),
                word("８６", 100, 500, 200, 600), word("99", 500, 10, 520, 30)));
        readings(result, 109, 71, 86, "three large aligned rows without labels");

        result = BloodPressureOcrParser.parse(Arrays.asList(
                word("收缩压：138", 0, 10, 120, 50), word("舒张压 88", 0, 100, 120, 140),
                word("心率：76", 0, 200, 120, 240)));
        readings(result, 138, 88, 76, "combined label and value");

        result = BloodPressureOcrParser.parse(Arrays.asList(
                word("SYS", 0, 120, 60, 145), word("120", 100, 100, 200, 190),
                word("DIA", 0, 310, 60, 335), word("80mmHg", 100, 290, 200, 380),
                word("PUL", 0, 510, 60, 535), word("72bpm", 100, 490, 200, 580)));
        readings(result, 120, 80, 72, "English labels and attached units");

        result = BloodPressureOcrParser.parse(Arrays.asList(
                word("高压", 0, 120, 50, 150), word("109", 100, 100, 200, 200),
                word("脉搏", 0, 520, 50, 550), word("86", 100, 500, 200, 600)));
        require(Integer.valueOf(109).equals(result.systolic) && result.diastolic == null &&
                Integer.valueOf(86).equals(result.pulse), "missing low pressure does not shift pulse");
        require(!result.complete(), "partial readings cannot be treated as complete");

        result = BloodPressureOcrParser.parse(Arrays.asList(
                word("109", 100, 100, 200, 200), word("86", 100, 500, 200, 600)));
        require(result.count() == 0, "two unlabeled rows are not guessed");
        require(BloodPressureOcrParser.parse(Collections.emptyList()).count() == 0, "empty OCR is handled");

        List<BloodPressureOcrParser.Word> split = Arrays.asList(
                word("高压", 0, 125, 50, 150), word("1", 100, 100, 120, 200),
                word("3", 130, 100, 180, 200), word("8", 190, 100, 240, 200),
                word("低压", 0, 325, 50, 350), word("88", 130, 300, 240, 400),
                word("脉搏", 0, 525, 50, 550), word("76", 130, 500, 240, 600));
        readings(BloodPressureOcrParser.parse(split), 138, 88, 76, "split digits joined without nested partial numbers");
        readings(BloodPressureOcrParser.parse(Arrays.asList(
                word("1 09", 100, 100, 200, 200), word("7 1", 100, 300, 200, 400),
                word("8 6", 100, 500, 200, 600))), 109, 71, 86, "spaces within segmented numbers");

        result = BloodPressureOcrParser.parse(Arrays.asList(
                word("I09", 100, 100, 200, 200), word("7I", 100, 300, 200, 400),
                word("B6", 100, 500, 200, 600)));
        require(result.count() == 0, "letters are not silently converted into digits");

        List<BloodPressureOcrParser.Word> ambiguous = new ArrayList<>(sample);
        ambiguous.add(word("119", 535, 455, 680, 583));
        result = BloodPressureOcrParser.parse(ambiguous);
        require(result.uncertain && result.systolic == null, "competing readings in a labeled row require correction");

        result = BloodPressureOcrParser.choose(new BloodPressureOcrParser.Result(109, 71, 86, false, 3),
                new BloodPressureOcrParser.Result(189, 71, 86, false, 0));
        require(result.uncertain && result.systolic == 109, "different model readings require review");
        result = BloodPressureOcrParser.choose(new BloodPressureOcrParser.Result(null, 71, 86, false, 2),
                new BloodPressureOcrParser.Result(109, 71, 86, false, 0));
        require(result.complete(), "complete independent pass preferred over missing reading");

        require(BloodPressureOcrParser.onlyNumber(Arrays.asList(word("71", 0, 0, 100, 100)), 1) == 71,
                "framed screen row contains one number");
        require(BloodPressureOcrParser.onlyNumber(Arrays.asList(word("71", 0, 0, 100, 100),
                word("86", 150, 0, 250, 100)), 1) == null, "multiple numbers in row are ambiguous");
        require(BloodPressureOcrParser.onlyNumber(Arrays.asList(word("100%", 0, 0, 100, 100)), 0) == null,
                "percentage is not a pressure reading");
        require(!BloodPressureOcrParser.inRange(0, 10) && !BloodPressureOcrParser.inRange(1, 260) &&
                !BloodPressureOcrParser.inRange(2, 999), "unusable values rejected");
        result = BloodPressureOcrParser.parse(Arrays.asList(word("高压 80", 0, 0, 100, 50),
                word("低压 90", 0, 100, 100, 150), word("脉搏 70", 0, 200, 100, 250)));
        require(!result.complete() && result.uncertain, "low pressure above high requires correction");
        System.out.println("PASS: " + checks + " OCR association assertions (actual photo recognition still requires a device)");
    }

    private static BloodPressureOcrParser.Word word(String text, float left, float top, float right, float bottom) {
        return new BloodPressureOcrParser.Word(text, left, top, right, bottom);
    }
    private static void readings(BloodPressureOcrParser.Result result, int high, int low, int pulse, String message) {
        require(Integer.valueOf(high).equals(result.systolic) && Integer.valueOf(low).equals(result.diastolic) &&
                Integer.valueOf(pulse).equals(result.pulse) && result.complete(), message);
    }
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

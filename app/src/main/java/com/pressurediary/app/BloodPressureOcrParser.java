package com.pressurediary.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps OCR words to readings; it never writes records or invents missing digits. */
final class BloodPressureOcrParser {
    private static final Pattern NUMBER = Pattern.compile("^([0-9 ]{2,5})(?:mmhg|bpm|次/分)?$");
    private static final Pattern LABELLED_NUMBER = Pattern.compile(
            "(?:高压|收缩压|低压|舒张压|脉搏|脉博|心率|sys|dia|pul(?:se)?)[：: ]*([0-9]{2,3})(?![0-9])");

    static final class Word {
        final String text;
        final float left, top, right, bottom;

        Word(String text, float left, float top, float right, float bottom) {
            this.text = text == null ? "" : text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        float height() { return Math.max(1, bottom - top); }
        float width() { return Math.max(1, right - left); }
        float centerX() { return (left + right) / 2; }
        float centerY() { return (top + bottom) / 2; }
    }

    static final class Result {
        final Integer systolic, diastolic, pulse;
        final boolean uncertain;
        final int labelledCount;

        Result(Integer systolic, Integer diastolic, Integer pulse, boolean uncertain, int labelledCount) {
            this.systolic = systolic;
            this.diastolic = diastolic;
            this.pulse = pulse;
            this.uncertain = uncertain;
            this.labelledCount = labelledCount;
        }

        int count() {
            return (systolic == null ? 0 : 1) + (diastolic == null ? 0 : 1) + (pulse == null ? 0 : 1);
        }

        boolean complete() {
            return count() == 3 && systolic > diastolic;
        }
    }

    static Result parse(List<Word> words) {
        Integer[] values = new Integer[3];
        boolean[] blocked = new boolean[3];
        boolean uncertain = false;
        List<Word> labels = new ArrayList<>();
        List<Word> numbers = new ArrayList<>();
        for (Word word : words) {
            String text = normalize(word.text);
            int field = labelField(text);
            if (field >= 0) {
                labels.add(word);
                Matcher matcher = LABELLED_NUMBER.matcher(text);
                if (matcher.find()) {
                    int value = Integer.parseInt(matcher.group(1));
                    if (inRange(field, value) && !blocked[field]) {
                        if (values[field] != null && values[field] != value) {
                            uncertain = true;
                            blocked[field] = true;
                            values[field] = null;
                        }
                        else values[field] = value;
                    }
                }
            }
            Integer value = numericValue(text);
            if (value != null) numbers.add(new Word(String.valueOf(value), word.left, word.top, word.right, word.bottom));
        }
        numbers = deduplicate(numbers);
        // Join separated OCR digits on a single baseline, e.g. "10" and "9".
        // Do not interpret letters such as I/O as digits, or date/battery strings as readings.
        numbers = joinDigits(words, numbers);
        for (Word label : labels) {
            int field = labelField(normalize(label.text));
            if (values[field] != null || blocked[field]) continue;
            Word best = null;
            float bestScore = Float.MAX_VALUE;
            Integer alternative = null;
            for (Word number : numbers) {
                int value = Integer.parseInt(number.text);
                float deltaY = Math.abs(number.centerY() - label.centerY());
                if (!inRange(field, value) || number.centerX() < label.centerX() ||
                        deltaY > Math.max(number.height() * .8f, label.height() * 2.4f)) continue;
                float score = deltaY / number.height() +
                        Math.max(0, number.left - label.right) / number.height() * .04f;
                if (score < bestScore) {
                    if (best != null && bestScore - score < .15f) alternative = Integer.parseInt(best.text);
                    else alternative = null;
                    best = number;
                    bestScore = score;
                } else if (score - bestScore < .15f) {
                    alternative = value;
                }
            }
            if (best != null && (alternative == null || alternative == Integer.parseInt(best.text))) {
                values[field] = Integer.valueOf(best.text);
            } else if (best != null) {
                uncertain = true;
                blocked[field] = true;
            }
        }
        int labelledCount = count(values);
        if (labelledCount < 3) {
            // YE692 has three large vertical rows. Require all three rows before
            // using their order, so a missing low-pressure row cannot shift pulse into it.
            List<Word> large = new ArrayList<>();
            float largest = 0;
            for (Word number : numbers) largest = Math.max(largest, number.height());
            for (Word number : numbers) if (number.height() >= largest * .50f) large.add(number);
            if (large.size() > 24) {
                Collections.sort(large, (a, b) -> Float.compare(b.height(), a.height()));
                large = new ArrayList<>(large.subList(0, 24));
            }
            Collections.sort(large, (a, b) -> Float.compare(a.centerY(), b.centerY()));
            Integer[] layout = null;
            float layoutScore = -1;
            boolean competingLayout = false;
            for (int a = 0; a < large.size(); a++) {
                for (int b = a + 1; b < large.size(); b++) {
                    for (int c = b + 1; c < large.size(); c++) {
                        Word high = large.get(a), low = large.get(b), heart = large.get(c);
                        float maxHeight = Math.max(high.height(), Math.max(low.height(), heart.height()));
                        float maxWidth = Math.max(high.width(), Math.max(low.width(), heart.width()));
                        if (low.centerY() - high.centerY() < maxHeight * .65f ||
                                heart.centerY() - low.centerY() < maxHeight * .65f ||
                                Math.abs(high.centerX() - low.centerX()) > maxWidth * .7f ||
                                Math.abs(low.centerX() - heart.centerX()) > maxWidth * .7f) continue;
                        Integer[] candidate = {Integer.valueOf(high.text), Integer.valueOf(low.text), Integer.valueOf(heart.text)};
                        if (!inRange(0, candidate[0]) || !inRange(1, candidate[1]) ||
                                !inRange(2, candidate[2]) || candidate[0] <= candidate[1]) continue;
                        boolean agrees = true;
                        for (int i = 0; i < 3; i++) if (values[i] != null && !values[i].equals(candidate[i])) agrees = false;
                        if (!agrees) continue;
                        float score = high.height() + low.height() + heart.height();
                        if (score > layoutScore) {
                            competingLayout = layout != null && score < layoutScore * 1.15f && !same(layout, candidate);
                            layout = candidate;
                            layoutScore = score;
                        } else if (score > layoutScore * .87f && !same(layout, candidate)) competingLayout = true;
                    }
                }
            }
            if (layout != null && !competingLayout) {
                for (int i = 0; i < 3; i++) if (values[i] == null && !blocked[i]) values[i] = layout[i];
            } else if (competingLayout) uncertain = true;
        }
        if (values[0] != null && values[1] != null && values[0] <= values[1]) uncertain = true;
        return new Result(values[0], values[1], values[2], uncertain, labelledCount);
    }

    static Result choose(Result first, Result second) {
        if (first == null) return second;
        if (second == null) return first;
        Result best = second.count() > first.count() ||
                (second.count() == first.count() && second.labelledCount > first.labelledCount) ? second : first;
        boolean conflict = differs(first.systolic, second.systolic) ||
                differs(first.diastolic, second.diastolic) || differs(first.pulse, second.pulse);
        return new Result(best.systolic, best.diastolic, best.pulse,
                best.uncertain || conflict, best.labelledCount);
    }

    static Integer onlyNumber(List<Word> words, int field) {
        Integer result = null;
        for (Word word : joinDigits(words, deduplicate(numericWords(words)))) {
            Integer candidate = numericValue(normalize(word.text));
            if (candidate == null || !inRange(field, candidate)) continue;
            if (result != null && !result.equals(candidate)) return null;
            result = candidate;
        }
        return result;
    }

    private static List<Word> numericWords(List<Word> words) {
        List<Word> result = new ArrayList<>();
        for (Word word : words) if (numericValue(normalize(word.text)) != null) result.add(word);
        return result;
    }

    private static List<Word> joinDigits(List<Word> words, List<Word> numbers) {
        List<Word> digits = new ArrayList<>();
        for (Word word : words) if (normalize(word.text).matches("[0-9]{1,2}")) digits.add(word);
        Collections.sort(digits, (a, b) -> Float.compare(a.left, b.left));
        List<Word> joined = new ArrayList<>();
        for (Word start : digits) {
            Word combined = start;
            for (Word next : digits) {
                if (next.left < combined.right || next == start) continue;
                if (Math.abs(next.centerY() - combined.centerY()) > Math.max(next.height(), combined.height()) * .22f ||
                        Math.min(next.height(), combined.height()) < Math.max(next.height(), combined.height()) * .65f ||
                        next.left - combined.right > Math.max(next.height(), combined.height()) * .45f) continue;
                String text = normalize(combined.text).replace(" ", "") + normalize(next.text);
                if (text.length() > 3) break;
                combined = new Word(text, combined.left, Math.min(combined.top, next.top),
                        next.right, Math.max(combined.bottom, next.bottom));
            }
            if (combined != start && numericValue(combined.text) != null) joined.add(combined);
        }
        List<Word> result = new ArrayList<>(numbers);
        Collections.sort(joined, (a, b) -> Float.compare(b.width(), a.width()));
        List<Word> maximal = new ArrayList<>();
        for (Word combined : joined) {
            boolean contained = false;
            for (Word larger : maximal) {
                if (combined.left >= larger.left && combined.right <= larger.right &&
                        Math.abs(combined.centerY() - larger.centerY()) < larger.height() * .25f) contained = true;
            }
            if (contained) continue;
            maximal.add(combined);
            Iterator<Word> iterator = result.iterator();
            while (iterator.hasNext()) {
                Word word = iterator.next();
                if (word.left >= combined.left && word.right <= combined.right &&
                        Math.abs(word.centerY() - combined.centerY()) < combined.height() * .25f) iterator.remove();
            }
            result.add(combined);
        }
        return deduplicate(result);
    }

    private static List<Word> deduplicate(List<Word> words) {
        List<Word> result = new ArrayList<>();
        for (Word word : words) {
            boolean duplicate = false;
            for (Word previous : result) {
                if (word.text.equals(previous.text) && Math.abs(word.centerX() - previous.centerX()) < word.width() * .3f &&
                        Math.abs(word.centerY() - previous.centerY()) < word.height() * .3f) duplicate = true;
            }
            if (!duplicate) result.add(word);
        }
        return result;
    }

    private static String normalize(String text) {
        StringBuilder result = new StringBuilder();
        for (char c : text.toLowerCase(Locale.ROOT).trim().toCharArray()) {
            if (c >= '０' && c <= '９') c = (char) ('0' + c - '０');
            if (Character.isWhitespace(c)) c = ' ';
            result.append(c);
        }
        return result.toString();
    }

    private static Integer numericValue(String text) {
        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.matches()) return null;
        String number = matcher.group(1).replace(" ", "");
        if (number.length() < 2 || number.length() > 3) return null;
        return Integer.valueOf(number);
    }

    private static int labelField(String text) {
        if (text.contains("高压") || text.contains("收缩压") || text.matches(".*\\bsys\\b.*")) return 0;
        if (text.contains("低压") || text.contains("舒张压") || text.matches(".*\\bdia\\b.*")) return 1;
        if (text.contains("脉搏") || text.contains("脉博") || text.contains("心率") || text.matches(".*\\bpul(?:se)?\\b.*")) return 2;
        return -1;
    }

    static boolean inRange(int field, int value) {
        return field == 0 ? value >= 50 && value <= 260 :
                field == 1 ? value >= 30 && value <= 160 : value >= 30 && value <= 220;
    }

    private static int count(Integer[] values) {
        int result = 0;
        for (Integer value : values) if (value != null) result++;
        return result;
    }

    private static boolean differs(Integer a, Integer b) { return a != null && b != null && !a.equals(b); }
    private static boolean same(Integer[] a, Integer[] b) {
        for (int i = 0; i < 3; i++) if (!a[i].equals(b[i])) return false;
        return true;
    }
}

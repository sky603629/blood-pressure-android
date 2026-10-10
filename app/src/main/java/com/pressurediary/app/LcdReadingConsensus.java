package com.pressurediary.app;

import java.util.ArrayList;
import java.util.List;

/** Accepts repeated digit-only readings, leaving conflicting or weak candidates for manual input. */
final class LcdReadingConsensus {
    static final class Candidate {
        final int value, confidence;
        Candidate(int value, int confidence) { this.value = value; this.confidence = confidence; }
    }

    static Candidate parse(String text, int confidence, int field) {
        if (text == null) return null;
        String number = text.trim().replaceAll("\\s+", "");
        if (!number.matches("[1-9][0-9]{1,2}")) return null;
        int value = Integer.parseInt(number);
        return BloodPressureOcrParser.inRange(field, value) ? new Candidate(value, confidence) : null;
    }

    static Candidate choose(List<Candidate> candidates) {
        Candidate best = null;
        int bestVotes = 0;
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.confidence < 45) continue;
            int votes = 0, confidence = 0;
            for (Candidate other : candidates) {
                if (other != null && other.value == candidate.value && other.confidence >= 45) {
                    votes++; confidence = Math.max(confidence, other.confidence);
                }
            }
            if (votes > bestVotes || (votes == bestVotes && (best == null || confidence > best.confidence))) {
                best = new Candidate(candidate.value, confidence); bestVotes = votes;
            }
        }
        if (best == null || bestVotes < 2) return null;
        for (Candidate candidate : candidates) {
            if (candidate != null && candidate.value != best.value && candidate.confidence >= 70) return null;
        }
        return best;
    }

    static BloodPressureOcrParser.Result result(List<List<Candidate>> rows) {
        List<Candidate> selected = new ArrayList<>();
        for (List<Candidate> row : rows) selected.add(choose(row));
        Integer[] values = new Integer[3];
        boolean uncertain = false;
        for (int i = 0; i < 3; i++) {
            Candidate candidate = selected.get(i);
            values[i] = candidate == null ? null : candidate.value;
            uncertain |= candidate == null || candidate.confidence < 70;
        }
        uncertain |= values[0] != null && values[1] != null && values[0] <= values[1];
        return new BloodPressureOcrParser.Result(values[0], values[1], values[2], uncertain, 0);
    }
}

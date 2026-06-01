package com.diplom.cloudstorage.service;

import java.util.ArrayList;
import java.util.List;

public final class VectorUtils {

    private VectorUtils() {
    }

    public static String serialize(List<Double> vector) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector.get(i));
        }
        return builder.toString();
    }

    public static List<Double> parse(String value) {
        List<Double> vector = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return vector;
        }
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                vector.add(Double.parseDouble(part));
            }
        }
        return vector;
    }

    public static double cosine(List<Double> first, List<Double> second) {
        if (first.isEmpty() || second.isEmpty() || first.size() != second.size()) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < first.size(); i++) {
            double a = first.get(i);
            double b = second.get(i);
            dot += a * b;
            normA += a * a;
            normB += b * b;
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}

package com.example.demo.dto;

public record CheckResult(
        boolean isBad,
        String message,
        String sentence_types,
        double similarity,
        String matchedWord,
        Object allItemsLog
) {}

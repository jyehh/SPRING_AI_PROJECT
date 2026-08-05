package com.example.demo.dto;

public record CheckResult(
        boolean isBad,
        String message,
        String sentence_types,
        double similarity,
        String matchedWord,
        Object allItemsLog
) {
    // 1. 안전한 문장일 때 (기본형)
    public static CheckResult safe() {
        return new CheckResult(false, "안전한 문장입니다.", "CLEAN", 0.0, null, null);
    }

    // 2. 비속어가 감지되었을 때
    public static CheckResult detected(boolean isBad,  String type, double score, String word, Object details) {
        String msg = isBad ? "비속어가 감지되었습니다." : "안전한 문장입니다.";
        return new CheckResult(isBad, msg, type, score, word, details);
    }

    // 3. 에러가 발생했을 때
    public static CheckResult error(String message) {
        return new CheckResult(true, "에러 발생: " + message, "ERROR", 0.0, null, null);
    }
    
    // 4. LLM 전용 (상세 메시지 포함)
    public static CheckResult llm(boolean isBad, String category, Object details) {
        String msg = isBad ? "LLM에 의해 비속어가 감지되었습니다." : "안전한 문장입니다.";
        return new CheckResult(isBad, msg, category, 0.0, null, details);
    }

    // 2. 비속어 적재
    public static CheckResult save() {
        return new CheckResult(true, "비속어가 등록되었습니다.", "IMMORAL_BAD", 0.0, null, null);
    }
}

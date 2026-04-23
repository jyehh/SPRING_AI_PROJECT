package com.example.demo.service;

import com.example.demo.dto.CheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BadWordValidService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    @Value("classpath:/prompts/bad-word-filter.st")
    private Resource systemPromptResource;

    /**
     * 다중 검증을 위해 Top 5개의 유사 데이터를 검색합니다.
     */
    public List<Document> selectVector(String userInput) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userInput)
                        .topK(5) // 5개로 변경
                        .build()
        );
    }

    /**
     * 5개의 검색 결과를 분석하여 가장 빈번한 타입을 찾고 평균 유사도를 계산합니다.
     */
    public CheckResult checkResult(List<Document> results) {
        if (results == null || results.isEmpty()) {
            return new CheckResult(false, "검색 결과가 없습니다.", "N/A", 0.0, null);
        }

        // 1. 데이터를 가공하여 리스트로 저장 (유사도 순서 유지)
        record ResultItem(double score, String text, String type) {}
        List<ResultItem> items = results.stream().map(doc -> {
            double score = doc.getScore();
            String text = doc.getText();
            Object typesObj = doc.getMetadata().getOrDefault("types", "N/A");
            String type = String.valueOf(typesObj).replaceAll("[\\[\\]\"]", "");
            return new ResultItem(score, text, type);
        }).toList();

        // 2. 타입별 빈도수 계산
        Map<String, Long> typeCounts = items.stream()
                .collect(Collectors.groupingBy(ResultItem::type, Collectors.counting()));

        // 3. 가장 많이 중복된 타입 및 평균 유사도 결정
        long maxCount = Collections.max(typeCounts.values());
        
        String finalType;
        double finalSimilarity;
        String finalMatchedWord;

        if (maxCount > 1) {
            // 중복된 타입이 있는 경우: 가장 빈도가 높은 타입 선정
            // (동률일 경우 유사도가 높은 순서대로 먼저 발견된 타입을 선택)
            finalType = items.stream()
                    .map(ResultItem::type)
                    .filter(type -> typeCounts.get(type) == maxCount)
                    .findFirst()
                    .orElse("N/A");

            // [수정] 해당 타입에 속하는 결과들 중 가장 높은 유사도 선택 (리스트가 이미 정렬되어 있으므로 첫 번째 값이 최대값)
            finalSimilarity = items.stream()
                    .filter(i -> i.type().equals(finalType))
                    .findFirst()
                    .map(ResultItem::score)
                    .orElse(0.0);

            // 해당 타입 중 가장 유사도가 높은 단어 선택
            finalMatchedWord = items.stream()
                    .filter(i -> i.type().equals(finalType))
                    .findFirst()
                    .map(ResultItem::text)
                    .orElse(null);
        } else {
            // 중복이 하나도 없는 경우: 가장 유사도가 높은 첫 번째 데이터 사용
            ResultItem top = items.get(0);
            finalType = top.type();
            finalSimilarity = top.score();
            finalMatchedWord = top.text();
        }

        // 4. 최종 비속어 여부 판별 (기준치 0.80 및 IMMORAL_NONE 제외 로직 유지)
        boolean isBad = finalSimilarity > 0.80 && !finalType.contains("IMMORAL_NONE");

        // 5. 전체 데이터 상세 로그 생성
        String allItemsLog = items.stream()
                .map(i -> String.format("[%s (%.4f, %s)]", i.text(), i.score(), i.type()))
                .collect(Collectors.joining(", "));

        log.info("다중 검증 결과 - 선정타입: {}(빈도:{}), 최대유사도: {}, 대표단어: {}, 판별: {}\n전체 데이터: {}",
                 finalType, maxCount, String.format("%.4f", finalSimilarity), finalMatchedWord, isBad ? "비속어" : "정상", allItemsLog);

        return new CheckResult(
                isBad,
                isBad ? "비속어가 감지되었습니다. (다중 검증)" : "안전한 문장입니다.",
                finalType,
                finalSimilarity,
                finalMatchedWord
        );
    }

    public CheckResult askLLM(String userInput) {
        String response = chatClient.prompt()
                .system(systemPromptResource)
                .user(userInput)
                .call()
                .content();

        log.info("LLM 판별 요청: [{}] -> 결과: {}", userInput, response);

        if (response == null) return new CheckResult(false, "LLM 응답 없음", "N/A", 0.0, null);
        
        boolean isBad = response.toLowerCase().contains("true");
        return new CheckResult(
                isBad,
                isBad ? "LLM에 의해 비속어가 감지되었습니다." : "안전한 문장입니다.",
                isBad ? response.replaceFirst(".*\\((.*)\\).*", "$1") : null,
                0.0,
                null
        );
    }
}

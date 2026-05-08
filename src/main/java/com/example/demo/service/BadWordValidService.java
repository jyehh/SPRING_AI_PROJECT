package com.example.demo.service;

import com.example.demo.config.BadWordFilterProperties;
import com.example.demo.dto.CheckResult;
import com.example.demo.dto.LlmCheckResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BadWordValidService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final BadWordFilterProperties filterProperties;

    /**
     * VectorStore에서 가장 유사한 상위 데이터를 검색합니다.
     * 유사도가 하한선 미만인 데이터는 노이즈로 간주하여 제외합니다.
     */
    public List<Document> selectVector(String userInput) {
        return vectorStore.similaritySearch(userInput);
//        return vectorStore.similaritySearch(
//                SearchRequest.builder()
//                        .query(userInput)
//                        .topK(filterProperties.getRag().getTopK())
//                        .similarityThreshold(filterProperties.getRag().getMinSimilarity())
//                        .build()
//        );
    }

    /**
     * 가장 유사도가 높은 상위 1개 데이터를 기준으로 비속어 여부를 판별하되,
     * 응답값에는 검색된 전체 로그를 포함합니다.
     */
    public CheckResult checkResult(List<Document> results) {
        if (results == null || results.isEmpty()) {
            return CheckResult.safe();
        }

        // 1. 검색 결과를 ResultItem 리스트로 변환 (응답 로그용)
        record ResultItem(double score, String text, String type) {}
        List<ResultItem> items = results.stream().map(doc -> {
            double score = doc.getScore();
            String text = doc.getText();
            Object typesObj = doc.getMetadata().getOrDefault("types", "N/A");
            String type = formatType(typesObj);
            return new ResultItem(score, text, type);
        }).toList();

        // 2. 판정은 가장 유사도가 높은 첫 번째 데이터(Top 1)만 사용
        ResultItem top1 = items.get(0);

        // 최종 비속어 여부 판별: 
        // - 유사도가 임계치 이상
        // - 타입이 '정상(IMMORAL_NONE)'이 아님
        boolean isBad = top1.score() >= filterProperties.getRag().getSimilarityThreshold() && !top1.type().contains("IMMORAL_NONE");

        log.info("RAG 판별 완료 (Top 1 기준) - 유사도: {}, 타입: {}, 판별: {}", 
                String.format("%.4f", top1.score()), top1.type(), isBad ? "비속어" : "정상");

        return CheckResult.detected(isBad,top1.type(), top1.score(), top1.text(), items);
    }


    /**
     * 메타데이터 객체를 문자열 형식으로 정제합니다.
     */
    private String formatType(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.joining(","));
        }
        return String.valueOf(obj).replaceAll("[\\[\\]\"]", "");
    }

    /**
     * LLM에게 직접 비속어 여부를 물어봅니다.
     */
    public CheckResult askLLM(String userInput, Object contextLog) {
        log.info("LLM 판별 요청 중: [{}]", userInput);
        
        try {
            // ChatClientConfig에서 설정된 defaultSystem 프롬프트와 format이 자동으로 적용됩니다.
            LlmCheckResponse response = chatClient.prompt()
                    .user(userInput)
                    .call()
                    .entity(LlmCheckResponse.class);

            if (response == null) {
                log.warn("LLM 응답이 null입니다.");
                return CheckResult.error("LLM 응답 없음");
            }

            log.info("LLM 판별 결과: {} ({}) - 사유: {}", response.isBad() ? "비속어" : "정상", response.category(), response.reason());

            return CheckResult.llm(response.isBad(), response.category(), contextLog);
        } catch (Exception e) {
            log.error("LLM 판별 중 예외 발생: {}", e.getMessage(), e);
            return CheckResult.error("LLM 서비스 일시적 오류");
        }
    }
}

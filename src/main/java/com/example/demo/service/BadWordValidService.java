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

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BadWordValidService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    @Value("classpath:/prompts/bad-word-filter.st")
    private Resource systemPromptResource;

    public List<Document> selectVector(String userInput) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userInput)
                        .topK(1)
                        .build()
        );
    }

    public CheckResult checkResult(List<Document> results){
        Document topResult = results.get(0);
        // VectorStore에서 score는 보통 1 - 코사인 거리 (유사도)를 반환합니다.
        double similarity = topResult.getScore();
        String matchedWord = topResult.getText();

        // 메타데이터에서 'types' 정보를 추출합니다.
        // List 형태로 저장된 경우를 대비해 문자열로 변환 후 대괄호 등을 제거합니다.
        Object typesObj = topResult.getMetadata().getOrDefault("types", "N/A");
        String sentenceTypes = String.valueOf(typesObj).replaceAll("[\\[\\]\"]", "");

        // 3. 기준치(Threshold) 설정 및 최종 판별
        // 유사도가 0.70보다 높고, 타입이 IMMORAL_NONE이 아닌 경우에만 비속어로 판단합니다.
        boolean isBad = similarity > 0.75 && !sentenceTypes.contains("IMMORAL_NONE");

        log.info("판별 결과 - 가장 유사한 문장: {}, 유사도: {}, 타입: {}, 판별: {}",
                 matchedWord, similarity, sentenceTypes, isBad ? "비속어" : "정상");

        return new CheckResult(
                isBad,
                isBad ? "비속어가 감지되었습니다." : "안전한 문장입니다.",
                sentenceTypes,
                similarity,
                matchedWord
        );
    }

    public CheckResult askLLM(String userInput) {
        String response = chatClient.prompt()
                .system(systemPromptResource)
                .user(userInput)
                .call()
                .content();

        log.info("LLM 판별 요청: [{}] -> 결과: {}", userInput, response);

        assert response != null;
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

package com.example.demo.service;

import com.example.demo.dto.CheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BadWordSelectService {

    private final BadWordValidService badWordValidService;
    /**
     * 비속어 여부 판별
     * @param userInput 사용자가 입력한 문장
     * @return 비속어 여부 및 유사도 정보
     */
    public CheckResult checkBadWordV2(String userInput) {
        log.info("사용자 입력 문장 : {}", userInput);
        // 1. VectorStore를 사용한 유사도 검색 (Spring AI 라이브러리 활용)
        // 가장 유사한 5개의 문장을 찾습니다. (Top-K = 5)
        List<Document> results = badWordValidService.selectVector(userInput);

        // 2. 만약 조회데이터가 없으면
        if (results.isEmpty()) {
            return new CheckResult(false, "안전한 문장입니다.", "N/A", 0.0, null,null);
        }

     return badWordValidService.checkResult(results);
    }
}
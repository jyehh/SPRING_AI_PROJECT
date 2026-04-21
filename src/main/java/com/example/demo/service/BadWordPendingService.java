package com.example.demo.service;

import com.example.demo.dto.CheckResult;
import com.example.demo.repository.PendingBadWordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BadWordPendingService {

    private final BadWordValidService badWordValidService;
    private final PendingBadWordRepository pendingBadWordRepository;

    /**
     * 사용자가 입력한 문장의 비속어 여부를 판별하는 핵심 메소드
     *
     * @param userInput 사용자가 입력한 문장
     * @return 비속어 여부, 유사도, 매칭된 단어 등을 포함한 CheckResult 객체
     */
    public CheckResult checkBadWord(String userInput) {
        // 1. [유사도 검색] VectorStore를 사용하여 입력된 문장과 가장 유사한 데이터를 검색합니다.
        List<Document> results = badWordValidService.selectVector(userInput);

        log.info("2차 임베딩검증");
        // 2. [검색 결과 확인] 만약 DB에 비교할 데이터가 전혀 없다면 LLM에게 직접 물어봅니다.
        if (results.isEmpty()) {
            log.info("3차 LLM검증");
            return badWordValidService.askLLM(userInput);
        }

        CheckResult checkResult = badWordValidService.checkResult(results);
        log.info("2차 임베딩 결과 검증");
        if (checkResult.isBad()) {
            // 6. [결과 객체 반환] 최종 판별 결과와 관련 정보를 담은 DTO(CheckResult)를 생성하여 반환합니다.
            return checkResult;
        } else {
            log.info("4차 LLM검증");
            //LLM 조회 전 대상 문장 적재
            savePendingWord(userInput, checkResult);
            // 유사도가 낮으면 LLM 판별을 한 번 더 수행합니다.
            CheckResult llmResult = badWordValidService.askLLM(userInput);
            //LLM조회 후 결과 저장
            updatePendingWord(String.valueOf(llmResult.isBad()),userInput);

            return llmResult;
        }
    }

    public CheckResult savePendingWord(String userInput, CheckResult checkResult) {
        try {
            pendingBadWordRepository.upsertPendingWord(
                    userInput,
                    checkResult.matchedWord(),
                    checkResult.similarity(),
                    "SYSTEM",
                    checkResult.sentence_types()
            );
        } catch (Exception e) {
            log.error("Failed to save pending bad word: {}", e.getMessage());
        }
        return checkResult;
    }

    public void updatePendingWord(String response,String pendingBadWord){
        pendingBadWordRepository.updatePendingWord(response,pendingBadWord);
    }
}

package com.example.demo.service;

import com.example.demo.domain.BadWord;
import com.example.demo.repository.BadWordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BadWordSaveService {

    private final VectorStore vectorStore;

    @Transactional
    public void saveBadWord(String sentence) {
        log.info("비속어 등록 요청: {}", sentence);

        // 1. 문장 내용을 기반으로 ID 생성 (중복 방지 핵심)
        String deterministicId = UUID.nameUUIDFromBytes(sentence.getBytes(StandardCharsets.UTF_8)).toString();
        log.info("생성된 UUID: {}", deterministicId);

        // 2. VectorStore 저장 (동일 ID인 경우 자동으로 덮어쓰기 처리됨)
        // Documnet는 Spring AI의 기본 규격
        Document document = new Document(deterministicId, sentence, Map.of("types", List.of("IMMORAL_BAD")));
        // spring ai 가 지원하는 벡터 데이터베이스 구현체
        // vectorStore를 사용하면 이후에 db가 변경되더라도 코드를 수정할일이 없음 (이식성)
        // 임베딩하고 vector db에 저장까지
        vectorStore.add(List.of(document));

        log.info("비속어 등록 프로세스 완료");
    }
}
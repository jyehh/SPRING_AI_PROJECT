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

        // 1. 문장 내용을 기반으로 결정적 ID 생성 (중복 방지 핵심)
        String deterministicId = UUID.nameUUIDFromBytes(sentence.getBytes(StandardCharsets.UTF_8)).toString();
        log.info("생성된 UUID: {}", deterministicId);

        // 2. VectorStore 저장 (동일 ID인 경우 자동으로 덮어쓰기/무시 처리됨)
        Document document = new Document(deterministicId, sentence, Map.of("types", List.of("IMMORAL_BAD")));
        vectorStore.add(List.of(document));

        log.info("비속어 등록 프로세스 완료");
    }
}
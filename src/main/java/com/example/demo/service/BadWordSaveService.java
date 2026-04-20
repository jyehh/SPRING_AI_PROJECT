package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BadWordSaveService {

    private final VectorStore vectorStore;

    @Transactional
    public void saveBadWord(String sentence) {
        log.info("비속어 등록 요청: {}", sentence);

        Document document = new Document(sentence, Map.of("types", List.of("IMMORAL_BAD")));
        vectorStore.add(List.of(document));

        log.info("비속어 등록 및 임베딩 저장 완료");
    }

}
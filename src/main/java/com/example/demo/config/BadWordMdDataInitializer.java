package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 마크다운 파일(.md) 형식의 비속어 단어 리스트를 읽어 VectorStore에 저장하는 초기화 클래스입니다.
 */
@Slf4j
//@Component
@RequiredArgsConstructor
public class BadWordMdDataInitializer {

    private final VectorStore vectorStore;

    /**
     * 애플리케이션 시작 시 실행되어 MD 파일을 읽고 VectorStore에 데이터를 삽입합니다.
     */
    @PostConstruct
    public void init() {
        log.info("==== [MD 데이터 초기화] 시작 ====");

        try {
            // 1. MD 파일 읽기 (classpath:word/ 폴더 내의 모든 .md 파일 로드)
            List<String> allWords = readAllMdFiles();
            int totalSize = allWords.size();

            if (totalSize == 0) {
                log.warn("처리할 MD 데이터가 없습니다.");
                return;
            }

            log.info("총 {}건의 MD 단어를 로드했습니다. VectorStore 입력을 시작합니다.", totalSize);

            // 2. 배치(Batch) 처리 설정: 효율적인 삽입을 위해 100건 단위로 처리
            int batchSize = 100;

            for (int i = 0; i < totalSize; i += batchSize) {
                int endIndex = Math.min(i + batchSize, totalSize);
                List<String> subList = allWords.subList(i, endIndex);

                // 3. Document 객체 리스트 생성
                List<Document> documents = subList.stream().map(word -> {
                    return new Document(word, Map.of(
                            "file_type", "md",
                            "types", List.of("IRRITABLE") // 기본 타입 부여
                    ));
                }).toList();

                // 4. VectorStore에 저장
                vectorStore.add(documents);

                // 실시간 진행률 로그 출력
                double progress = ((double) endIndex / totalSize) * 100;
                log.info("[MD 진행 상황] {} / {} 건 완료 ({}) / 시간 : [{}]", endIndex, totalSize, String.format("%.2f%%", progress), LocalDateTime.now());
            }

            log.info("==== [MD 데이터 초기화] 모든 작업이 완료되었습니다! ====");

        } catch (Exception e) {
            log.error("==== [MD 데이터 초기화] 중단됨: {} ====", e.getMessage(), e);
        }
    }

    /**
     * resources/word 폴더 내의 모든 .md 파일을 읽어 주석이나 빈 줄을 제외한 순수 단어 리스트를 반환합니다.
     */
    private List<String> readAllMdFiles() throws Exception {
        List<String> results = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:word/*.md");

        for (Resource resource : resources) {
            log.info("MD 파일 로드 중: {}", resource.getFilename());
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    // 빈 줄이 아니고, 마크다운 헤더(#)나 구분선(---)이 아닌 경우에만 데이터로 간주
                    if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("---")) {
                        results.add(line);
                    }
                }
            }
        }
        return results;
    }
}

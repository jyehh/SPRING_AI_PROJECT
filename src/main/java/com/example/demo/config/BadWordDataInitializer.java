package com.example.demo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 텍스트 파일(.txt) 형태의 비속어 데이터를 읽어 VectorStore에 저장하는 초기화 클래스입니다.
 */
@Slf4j
//@Component
@RequiredArgsConstructor
public class BadWordDataInitializer {

    private final VectorStore vectorStore;

    // 문장과 파일 정보를 담는 내부 클래스
    private record BadSentenceData(String sentence, String extension, String filename, int splitCount) {}

    /**
     * 애플리케이션 시작 시 실행되어 텍스트 파일을 읽고 VectorStore에 데이터를 삽입합니다.
     */
    @PostConstruct
    public void init() {
        log.info("==== [데이터 초기화] 시작 ====");

        try {
            // 1. 다중 파일 읽기 (bad_sentences_*.txt 패턴의 모든 파일을 리스트로 로드)
            List<BadSentenceData> allData = readAllSentences();
            int totalSize = allData.size();

            if (totalSize == 0) {
                log.warn("처리할 데이터가 없습니다. 파일을 확인해주세요.");
                return;
            }

            log.info("총 {}건의 데이터를 로드했습니다. VectorStore 입력을 시작합니다.", totalSize);

            // 2. 배치(Batch) 처리 설정: 대량의 데이터를 효율적으로 처리하기 위해 100건씩 나누어 진행
            int batchSize = 100; 

            for (int i = 0; i < totalSize; i += batchSize) {
                int endIndex = Math.min(i + batchSize, totalSize);
                List<BadSentenceData> subList = allData.subList(i, endIndex);
                
                // 3. Document 객체 리스트 생성
                List<Document> documents = subList.stream().map(data -> {
                    return new Document(data.sentence(), Map.of(
                            "file_type", data.extension(),
                            "filename", data.filename(),
                            "split_count", data.splitCount(),
                            "types", List.of("IMMORAL_BAD") // 기본 타입 부여
                    ));
                }).toList();

                // 4. VectorStore에 저장 (임베딩은 VectorStore 내부에서 처리됨)
                vectorStore.add(documents);

                // 진행률 계산 및 실시간 로그 출력
                double progress = ((double) endIndex / totalSize) * 100;
                log.info("[진행 상황] {} / {} 건 완료 ({}) / 시간 : [{}]", endIndex, totalSize, String.format("%.2f%%", progress), LocalDateTime.now());
            }

            log.info("==== [데이터 초기화] 모든 작업이 성공적으로 완료되었습니다! ====");

        } catch (Exception e) {
            log.error("==== [데이터 초기화] 중단됨: {} ====", e.getMessage(), e);
        }
    }

    /**
     * resources 폴더 내의 bad_sentences_*.txt 패턴을 가진 모든 파일을 읽어 리스트로 반환합니다.
     */
    private List<BadSentenceData> readAllSentences() throws Exception {
        List<BadSentenceData> allSentences = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        
        // 특정 패턴을 가진 리소스 파일들을 모두 가져옴
        Resource[] resources = resolver.getResources("classpath:bad_sentences_*.txt");

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            String extension = "";
            if (filename != null && filename.contains(".")) {
                extension = filename.substring(filename.lastIndexOf(".") + 1);
            }

            log.info("파일 로드 중: {} (확장자: {})", filename, extension);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;

                    // '|' 구분자를 기반으로 문장 내 단어 개수 파악
                    int splitCount = line.split("\\|").length;

                    // 특수 기호 제거 및 포맷 정제
                    String cleanedLine = line.replace("{", "")
                            .replace("}", "")
                            .replace("|", ". ")
                            .replace("  ", " ")
                            .trim();
                    
                    if (!cleanedLine.isEmpty()) {
                        allSentences.add(new BadSentenceData(cleanedLine, extension, filename, splitCount));
                    }
                }
            }
        }
        return allSentences;
    }
}

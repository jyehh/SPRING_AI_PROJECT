package com.example.demo.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JSON 형식(.json)의 비속어 데이터를 읽어 VectorStore에 상세 메타데이터와 함께 저장하는 초기화 클래스입니다.
 * 문장 기반의 고정 ID를 생성하여 중복 저장을 방지(Upsert)합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BadWordJsonDataInitializer {

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    // JSON 내의 문장과 관련 메타데이터를 담는 내부 클래스
    private record BadJsonData(String sentence, Map<String, Object> metadata) {}

    /**
     * 애플리케이션 시작 시 실행되어 JSON 파일을 파싱하고 VectorStore에 데이터를 삽입합니다.
     */
    @PostConstruct
    public void init() {
        log.info("==== [JSON 데이터 초기화] 시작 ====");

        try {
            // 1. JSON 파일 읽기 (classpath:json/ 폴더 내의 특정 패턴 파일을 로드하여 파싱)
            List<BadJsonData> allData = readAllJsonFiles();
            int totalSize = allData.size();

            if (totalSize == 0) {
                log.warn("처리할 JSON 데이터가 없습니다.");
                return;
            }

            log.info("총 {}건의 JSON 문장을 로드했습니다. VectorStore 입력을 시작합니다.", totalSize);

            // 2. 배치(Batch) 처리 설정: 효율적인 삽입을 위해 100건 단위로 처리
            int batchSize = 100;

            for (int i = 0; i < totalSize; i += batchSize) {
                int endIndex = Math.min(i + batchSize, totalSize);
                List<BadJsonData> subList = allData.subList(i, endIndex);
                
                // 3. Document 객체 리스트 생성 (중복 방지를 위한 고정 ID 생성 포함)
                List<Document> documents = subList.stream().map(data -> {
                    String content = data.sentence().trim();
                    // 문장 내용을 기반으로 고정된 UUID 생성 (중복 방지 핵심)
                    String deterministicId = UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8)).toString();
                    
                    // Spring AI Document는 메타데이터에 null 값을 허용하지 않으므로, null인 필드는 제외합니다.
                    Map<String, Object> filteredMetadata = data.metadata().entrySet().stream()
                            .filter(entry -> entry.getValue() != null)
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

                    return new Document(deterministicId, content, filteredMetadata);
                }).toList();

                // 4. VectorStore에 저장 (ID가 같으면 자동으로 ON CONFLICT / Upsert 처리됨)
                vectorStore.add(documents);

                // 실시간 진행률 로그 출력
                double progress = ((double) endIndex / totalSize) * 100;
                log.info("[JSON 진행 상황] {} / {} 건 완료 ({}) / 시간 : [{}]", endIndex, totalSize, String.format("%.2f%%", progress), LocalDateTime.now());
            }

            log.info("==== [JSON 데이터 초기화] 모든 작업이 완료되었습니다! ====");

        } catch (Exception e) {
            log.error("==== [JSON 데이터 초기화] 중단됨: {} ====", e.getMessage(), e);
        }
    }

    /**
     * resources/json 폴더 내의 모든 JSON 파일을 읽어 복잡한 구조의 데이터를 평탄화(Flatten)하여 반환합니다.
     */
    private List<BadJsonData> readAllJsonFiles() throws Exception {
        List<BadJsonData> results = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:json/bad_sentences_*.json");

        // ObjectMapper 설정: null 필드는 제외하도록 구성하여 Map 변환 시 null 값이 들어가지 않게 함
        // (이미 생성된 Bean에 영향을 주지 않기 위해 copy() 사용 고려 가능하나, 
        // 여기서는 Map 변환 후 스트림에서 필터링하는 방식이 더 안전함)

        for (Resource resource : resources) {
            log.info("JSON 파일 로드 중: {}", resource.getFilename());
            
            // 빈 파일인 경우 스킵
            if (resource.contentLength() == 0) {
                log.warn("파일이 비어 있습니다: {}", resource.getFilename());
                continue;
            }

            // ObjectMapper를 사용하여 JSON 트리 구조 분석
            JsonNode rootArray = objectMapper.readTree(resource.getInputStream());
            
            if (rootArray.isArray()) {
                for (JsonNode item : rootArray) {
                    JsonNode sentences = item.get("sentences");
                    if (sentences != null && sentences.isArray()) {
                        for (JsonNode sentenceNode : sentences) {
                            // JSON 객체 내에서 'origin_text' 필드 추출
                            JsonNode originTextNode = sentenceNode.get("origin_text");
                            if (originTextNode != null && !originTextNode.isNull()) {
                                String originText = originTextNode.asText();
                                if (!originText.trim().isEmpty()) {
                                    // 해당 문장과 관련된 모든 정보를 Map으로 변환하여 메타데이터로 활용
                                    Map<String, Object> metadata = objectMapper.convertValue(sentenceNode, new TypeReference<Map<String, Object>>() {});
                                    results.add(new BadJsonData(originText.trim(), metadata));
                                }
                            }
                        }
                    }
                }
            }
        }
        return results;
    }
}

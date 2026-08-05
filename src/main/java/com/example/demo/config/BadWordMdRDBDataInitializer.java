package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 마크다운 파일(.md) 형식의 비속어 단어 리스트를 읽어 RDB(bad_word 테이블)에 저장하는 초기화 클래스입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BadWordMdRDBDataInitializer {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 애플리케이션 시작 시 실행되어 MD 파일을 읽고 DB(bad_word 테이블)에 데이터를 삽입합니다.
     */

    @Async
    public void init() {
        log.info("==== [MD RDB 데이터 초기화] 시작 ====");

        try {
            // 1. MD 파일 읽기 (classpath:word/ 폴더 내의 모든 .md 파일 로드)
            List<String> allWords = readAllMdFiles();
            int totalSize = allWords.size();

            if (totalSize == 0) {
                log.warn("처리할 MD 데이터가 없습니다.");
                return;
            }

            log.info("총 {}건의 MD 단어를 로드했습니다. DB 입력을 시작합니다.", totalSize);

            // 2. 배치(Batch) 처리 설정: 효율적인 삽입을 위해 100건 단위로 처리
            int batchSize = 100;
            // bad_word 테이블 구조에 맞게 INSERT 쿼리 작성 (중복 단어 무시)
            String sql = "INSERT INTO bad_word (word, insert_id, insert_dtm, update_id, update_dtm) " +
                         "VALUES (?, ?, ?, ?, ?) " +
                         "ON CONFLICT (word) DO NOTHING";

            for (int i = 0; i < totalSize; i += batchSize) {
                int endIndex = Math.min(i + batchSize, totalSize);
                List<String> subList = allWords.subList(i, endIndex);

                // 3. JDBC Batch Update를 사용하여 데이터 대량 저장
                jdbcTemplate.batchUpdate(sql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        String word = subList.get(i);
                        LocalDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
                                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                .toLocalDateTime();
                        
                        ps.setString(1, word);              // 비속어 단어
                        ps.setString(2, "SYSTEM");          // 등록자 ID
                        ps.setObject(3, now);               // 등록 일시
                        ps.setString(4, "SYSTEM");          // 수정자 ID
                        ps.setObject(5, now);               // 수정 일시
                    }

                    @Override
                    public int getBatchSize() {
                        return subList.size();
                    }
                });

                // 실시간 진행률 로그 출력
                double progress = ((double) endIndex / totalSize) * 100;
                log.info("[MD RDB 진행 상황] {} / {} 건 완료 ({})", endIndex, totalSize, String.format("%.2f%%", progress));
            }

            log.info("==== [MD RDB 데이터 초기화] 모든 작업이 완료되었습니다! ====");

        } catch (Exception e) {
            log.error("==== [MD RDB 데이터 초기화] 중단됨: {} ====", e.getMessage(), e);
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
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    // 마크다운 불렛 기호(-, *)와 공백만 제거하여 단어 원형(예: 10넘) 보존
                    String cleaned = line.replaceAll("^[-*\\s]+", "").trim();
                    
                    // 빈 줄이 아니고, 마크다운 헤더(#)나 구분선(---)이 아닌 경우에만 데이터로 간주
                    if (!cleaned.isEmpty() && !line.startsWith("#") && !line.startsWith("---")) {
                        results.add(cleaned);
                    }
                }
            }
        }
        return results;
    }
}

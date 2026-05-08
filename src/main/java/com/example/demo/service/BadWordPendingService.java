package com.example.demo.service;

import com.example.demo.config.BadWordFilterProperties;
import com.example.demo.dto.CheckResult;
import com.example.demo.repository.BadWordRepository;
import com.example.demo.repository.PendingBadWordRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

@Slf4j
@Service
@RequiredArgsConstructor
public class BadWordPendingService {

    private final BadWordValidService badWordValidService;
    private final PendingBadWordRepository pendingBadWordRepository;
    private final BadWordRepository badWordRepository;
    private final BadWordSaveService badWordSaveService;
    private final BadWordFilterProperties filterProperties;
    
    // Jaro-Winkler 유사도 객체
    private static final JaroWinklerSimilarity jw = new JaroWinklerSimilarity();
    
    // 런타임에 컴파일된 정규식 패턴
    private Pattern badWordPattern;

    // 비속어 데이터를 효율적으로 관리하기 위한 레코드
    private record BadWordCache(String original, String jamo, int jamoLength) {}

    // 1:1 매칭용 셋 (조회 성능 O(1))
    private Set<String> badWordsSet = new HashSet<>();
    // Fuzzy 매칭용 리스트 (미리 계산된 데이터로 순회 성능 최적화)
    private List<BadWordCache> badWordCacheList = new ArrayList<>();

    /**
     * 서비스 기동 시 모든 필터 데이터를 초기화합니다.
     */
    @PostConstruct
    public void init() {
        log.info("비속어 필터 서비스 초기화 시작...");
        
        // 1. 정규식 패턴 컴파일
        initRegexPattern();

        // 2. DB 비속어 목록 및 캐시 로드
        loadBadWords();

        log.info("비속어 필터 서비스 초기화 완료.");
    }

    private void initRegexPattern() {
        String patternString = filterProperties.getRegex().getRegexPattern();
        this.badWordPattern = Pattern.compile(patternString, Pattern.CASE_INSENSITIVE);
        log.info("정규식 패턴 컴파일 완료 (길이: {})", patternString.length());
    }

    private void loadBadWords() {
        //단어목록
        Set<String> words = badWordRepository.findAllWords();
        //단어캐시 리스트
        List<BadWordCache> tempCache = new ArrayList<>();

        for (String word : words) {
            // 자모분리
            String decomposed = decompose(word);
            // 자모분리 한 단어를 캐시 리스트에 저장
            tempCache.add(new BadWordCache(word, decomposed, decomposed.length()));
        }

        //Collections.unmodifiable : readOnly
        this.badWordsSet = Collections.unmodifiableSet(words);
        this.badWordCacheList = Collections.unmodifiableList(tempCache);
        log.info("DB 비속어 캐시 로드 완료: {}건", badWordCacheList.size());
    }

    /**
     * 문자열을 전처리하고 정규화합니다. (NFC 정규화 + 특수문자 제거 + 반복문자 축소)
     */
    private String cleanAndNormalize(String input) {
        if (input == null || input.trim().isEmpty()) return "";
        
        // 1. NFC 정규화 (완성형 한글로 통일)
        String nfc = Normalizer.normalize(input, Normalizer.Form.NFC);
        
        // 2. 특수문자, 공백, 이모지 제거 (한글, 영문, 숫자만 유지)
        String cleaned = nfc.replaceAll("[^가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]", "");
        
        // 3. 반복되는 문자열 축소 (예: "바보보보" -> "바보")
        return cleaned.replaceAll("(.)\\1+", "$1");
    }

    public CheckResult checkBadWordV4(String input) {
        log.info("입력문장: {}", input);

        // [Step 1] 문장 전체 정규화 및 정규식 검사
        String normalizedSentence = cleanAndNormalize(input);
        log.info("정규화 완료: {}", normalizedSentence);

        var matcher = badWordPattern.matcher(normalizedSentence);
        if (matcher.find()) {
            //group : 실제로 어떤 단어를 찾았는지 확인
            String detected = matcher.group();
            log.warn("정규식 매칭 감지: detected=[{}]", detected);
            return CheckResult.detected(true,"Regex filter", 1.0, detected, "");
        }

        // [Step 2] 단어 단위 1:1 매칭 검사
        String[] words = input.split("\\s+");
        for (String word : words) {
            String nw = cleanAndNormalize(word);
            if (nw.isEmpty()) continue;

            if (badWordsSet.contains(nw)) {
                log.warn("DB 1:1 매칭 감지: word=[{}]", nw);
                return CheckResult.detected(true,"1:1 데이터 매핑", 1.0, word, "");
            }
        }

        // [Step 3] DB 기반 Fuzzy 매칭 검사
        if (isBadWord(normalizedSentence)) {
            log.warn("Fuzzy 매칭 감지: normalized=[{}]", normalizedSentence);
            return CheckResult.detected(true,"Fuzzy filter", 1.0, normalizedSentence, "");
        }

        // AI 기반(Vector/LLM) 검사 수행
        log.info("로컬 필터 통과. AI 기반(RAG/LLM) 정밀 검사 수행");
        return isChecked(input);
    }

    /**
     * AI 기반(RAG + LLM) 비속어 판별 메소드
     */
    public CheckResult isChecked(String userInput) {
        log.info("AI 검증 시작 :: 입력값=[{}]", userInput);
        
        // 1. RAG(VectorStore) 유사도 검색
        List<Document> results = badWordValidService.selectVector(userInput);
        
        // 2. 검색 결과가 없는 경우 LLM 직접 검증
        if (results.isEmpty()) {
            log.info("RAG 결과 없음 -> LLM 직접 검증 수행");
            return badWordValidService.askLLM(userInput, "조회된 데이터가 없습니다.");
        }

        // 3. RAG 결과 분석
        log.info("RAG 결과 분석 중...");
        CheckResult checkResult = badWordValidService.checkResult(results);

        // 4. RAG 결과가 비속어인 경우 즉시 반환
        if (checkResult.isBad()) {
            log.info("RAG 판별 완료: 비속어 감지");
            return checkResult;
        }

        // 5. RAG 결과가 정상이지만, 확실히 하기 위해 LLM 2차 검증 수행
        log.info("RAG 판별 결과: 정상 -> LLM 정밀 재검증 수행");
        
        // 대기 데이터 적재 (사후 모니터링용)
        savePendingWord(userInput, checkResult);
        
        // LLM 호출
        CheckResult llmResult = badWordValidService.askLLM(userInput, checkResult.allItemsLog());
        
        // 대기 데이터 업데이트
        updatePendingWord(String.valueOf(llmResult.isBad()), userInput);

        // LLM 검증 결과 비속어인 경우 Vector DB에 학습 데이터로 자동 저장
        if (llmResult.isBad()) {
            log.info("LLM 재검증 결과: 비속어 확정 -> 자동 학습 데이터(Vector DB) 적재");
            badWordSaveService.saveBadWord(userInput);
        }

        return llmResult;
    }

    /**
     *  자모분리
     **/
    public static String decompose(String input) {
        // Normalizer.Form.NFD는 한글을 초/중/종성으로 분리하는 Java 표준 API입니다.
        return Normalizer.normalize(input, Normalizer.Form.NFD);
    }

    /**
     *  자모분리 실제 적용
     **/
    public boolean isBadWord(String cleanInput) {

        // 사용자 입력값 자모분리
        String jamoInput = decompose(cleanInput);
        int inputLen = jamoInput.length();

        // 저장된 욕설 리스트 (캐시된 레코드 순회)
        for (BadWordCache cache : badWordCacheList) {
            String badWord = cache.original();
            String jamoBad = cache.jamo();
            int badLen = cache.jamoLength();

            // 비속어가 1글자인 경우
            if (badWord.length() == 1) {

                // 자모분리된 입력값과 '정확히 일치'할 때만 차단
                if (cleanInput.equals(badWord)){
                    log.info("한글자 비속어와 정확히 일치한 경우 :{} / {}",cleanInput,badWord);
                    return true;
                }
                // 유사도가 아주 높은 경우
                if (jw.apply(jamoInput, jamoBad) >= 0.95) {
                    log.info("한글자 비속어와 유사도가 아주 높은 경우 :{} / {}",cleanInput,badWord);
                    return true;
                }
            } else {
                // 비속어가 2글자 이상인 경우
                // 입력값에 '포함'되어 있는지 체크
                if (cleanInput.contains(badWord)){
                    log.info("2글자 이상 비속어에 포함된 경우 :{} / {}",cleanInput,badWord);
                    return true;
                }

                // 자모 분리 +  Fuzzy 검사
                // 자모분리 된 입력값 보다 자모분리 된 비속어 길이 짧으면 pass
                if (inputLen < badLen) continue;

                // 슬라이딩 윈도우 기법
                for (int i = 0; i <= inputLen - badLen; i++) {
                    String jamoInputSlide = jamoInput.substring(i, i + badLen);
                    // fuzzy filter N-gram
                    if (jw.apply(jamoInputSlide, jamoBad) >= filterProperties.getFuzzy().getThreshold()) {
                        log.info("2글자 이상 비속어와 유사도가 높은 경우 :{} / {}",cleanInput,badWord);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * LLM 조회 전 RAG를 통과한 문장 적재
     **/
    public void savePendingWord(String userInput, CheckResult checkResult) {
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
    }

    /**
     *  LLM 조회 후 결과 업데이트
     **/
    public void updatePendingWord(String response,String pendingBadWord){
        pendingBadWordRepository.updatePendingWord(response,pendingBadWord);
    }

}

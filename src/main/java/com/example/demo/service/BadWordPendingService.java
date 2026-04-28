package com.example.demo.service;

import com.example.demo.domain.BadWord;
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
    
    // Jaro-Winkler 유사도 객체
    private static final JaroWinklerSimilarity jw = new JaroWinklerSimilarity();

    // 차단 기준 임계치
    private static final double THRESHOLD = 0.88;

    // 거대 정규식 상수화 (성능을 위해 미리 컴파일)
    private static final Pattern BAD_WORD_PATTERN = Pattern.compile(
            "[시씨씪슈쓔쉬쉽쒸쓉](?:[0-9]*|[0-9]+ *)[바발벌빠빡빨뻘파팔펄]|[섊좆좇졷좄좃좉졽썅춍봊]|[ㅈ조][0-9]*까|ㅅㅣㅂㅏㄹ?|ㅂ[0-9]*ㅅ|[ㅄᄲᇪᄺᄡᄣᄦᇠ]|[ㅅㅆᄴ][0-9]*[ㄲㅅㅆᄴㅂ]|[존좉좇][0-9 ]*나|[자보][0-9]+지|보빨|[봊봋봇봈볻봁봍] *[빨이]|[후훚훐훛훋훗훘훟훝훑][장앙]|[엠앰]창|애[미비]|애자|[가-탏탑-힣]색기|(?:[샊샛세쉐쉑쉨쉒객갞갟객갞갟갯갰갴겍겎겏겤곅곆곇곗곘곜걕걖걗걧걨걬] *[끼키퀴])|새 *[키퀴]|[병븅][0-9]*[신딱딲]|미친[가-닥-힣]|[믿밑]힌|[염옘][0-9]*병|[샊샛샜샠섹섺셋셌셐셱솃솄솈섁섂섓섔섘]기|[섹섺섻섹쎆쎇쎽쎾쎿섁섂섃썍썎썏][스쓰]|[지야][0-9]*랄|니[애에]mi|갈[0-9]*보[^가-힣]|[뻐뻑뻒뻙뻨][0-9]*[뀨큐킹낑]|꼬[0-9]*추|곧[0-9]*휴|[가-힣]슬아치|자[0-9]*박꼼|빨통|[사싸](?:이코|가지|[0-9]*까시)|육[0-9]*시[랄럴]|육[0-9]*실[알얼할헐]|즐[^가-힣]|찌[0-9]*(?:질이|랭이)|찐[0-9]*따|찐[0-9]*찌버거|창[녀놈]|[가-힣]{2,}충[^가-힣]|[가-힣]{2,}츙|부녀자|화냥년|환[양향]년|호[0-9]*[구모]|조[선센][징]|조센|[쪼쪽쪾](?:[발빨]이|[바빠]리)|盧|무현|찌끄[레래]기|(?:하악){2,}|하[앍앜]|[낭당랑앙항남담랑앙함][ ]?[가-힣]+[띠찌]|느[금급]마|文전|이성계|(?<=[^\n])[家哥]|속냐|[tT]l[qQ]kf|Wls|[ㅂ]신|[ㅅ]발|[ㅈ]밥",
            Pattern.CASE_INSENSITIVE
            );

    // 비속어 데이터를 효율적으로 관리하기 위한 레코드
    private record BadWordCache(String original, String jamo, int jamoLength) {}

    // 1:1 매칭용 셋 (조회 성능 O(1))
    private Set<String> badWordsSet = new HashSet<>();
    // Fuzzy 매칭용 리스트 (미리 계산된 데이터로 순회 성능 최적화)
    private List<BadWordCache> badWordCacheList = new ArrayList<>();

    @PostConstruct
    public void loadBadWords() {
        Set<String> words = badWordRepository.findAllWords();
        List<BadWordCache> tempCache = new ArrayList<>();
        
        for (String word : words) {
            String decomposed = decompose(word);
            tempCache.add(new BadWordCache(word, decomposed, decomposed.length()));
        }
        
        this.badWordsSet = Collections.unmodifiableSet(words);
        this.badWordCacheList = Collections.unmodifiableList(tempCache);
        log.info("비속어 목록 로드 완료: {}건", badWordCacheList.size());
    }

    public CheckResult checkBadWordV4(String input) {
        // [Step 1] 문장 전체 정규식 검사 (숫자/공백 포함 욕설 탐지)
        log.info("입력문장: {}", input);

        // NFC 정규화
        String nfcString = Normalizer.normalize(input, Normalizer.Form.NFC);
        log.info("NFC 정규화 완료: {}", nfcString);
        
        // 특수문자, 공백, 이모지 제거
        String cleanedInput = nfcString.replaceAll("[^가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]", "");
        log.info("특수문자/공백 제거 완료: {}", cleanedInput);
        
        // 반복되는 문자열 축소
        String normalized = cleanedInput.replaceAll("(.)\\1+", "$1");
        log.info("반복 문자 축소 완료: {}", normalized);

        // 정규화 패턴 매칭
        var matcher = BAD_WORD_PATTERN.matcher(normalized);
        if (matcher.find()) {
            String detected = matcher.group();
            log.warn("정규식 매칭 감지: detected=[{}]", detected);
            return new CheckResult(true, "비속어가 감지되었습니다.", "Regex filter", 1.0, detected, "");
        }

        // 단어 단위 1:1 매칭 검사
        String[] words = input.split("\\s+");
        for (String word : words) {
            String cleaned = word.replaceAll("[^가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z]", "");
            if (cleaned.isEmpty()) continue;

            String normalizedWord = cleaned.replaceAll("(.)\\1+", "$1");
            if (badWordsSet.contains(normalizedWord)) {
                log.warn("DB 1:1 매칭 감지: word=[{}]", normalizedWord);
                return new CheckResult(true, "비속어가 감지되었습니다.", "1:1 데이터 매핑", 1.0, word, "");
            }
        }

        // Fuzzy 매칭 검사
        if (isBadWord(normalized)) {
            log.warn("Fuzzy 매칭 감지: normalized=[{}]", normalized);
            return new CheckResult(true, "비속어가 감지되었습니다.", "Fuzzy filter", 1.0, normalized, "");
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
                    if (jw.apply(jamoInputSlide, jamoBad) >= THRESHOLD) {
                        log.info("2글자 이상 비속어와 유사도가 높은 경우 :{} / {}",cleanInput,badWord);
                        return true;
                    }
                }
            }
        }
        return false;
    }



    /**
     *  LLM 조회 전 RAG를 통과한 문장 적재
     **/
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


    /**
     *  LLM 조회 후 결과 업데이트
     **/
    public void updatePendingWord(String response,String pendingBadWord){
        pendingBadWordRepository.updatePendingWord(response,pendingBadWord);
    }

}

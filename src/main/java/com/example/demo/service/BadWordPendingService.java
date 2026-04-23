package com.example.demo.service;

import com.example.demo.domain.BadWord;
import com.example.demo.dto.CheckResult;
import com.example.demo.repository.BadWordRepository;
import com.example.demo.repository.PendingBadWordRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

@Slf4j
@Service
@RequiredArgsConstructor
public class BadWordPendingService {

    private final BadWordValidService badWordValidService;
    private final PendingBadWordRepository pendingBadWordRepository;
    private final BadWordRepository badWordRepository;
    
    // Jaro-Winkler 유사도 객체
    private static final JaroWinklerSimilarity jw = new JaroWinklerSimilarity();

    // 차단 기준 임계치
    private static final double THRESHOLD = 0.88;

    // 거대 정규식 상수화 (성능을 위해 미리 컴파일)
    private static final Pattern BAD_WORD_PATTERN = Pattern.compile(
            "[시씨씪슈쓔쉬쉽쒸쓉](?:[0-9]*|[0-9]+ *)[바발벌빠빡빨뻘파팔펄]|[섊좆좇졷좄좃좉졽썅춍봊]|[ㅈ조][0-9]*까|ㅅㅣㅂㅏㄹ?|ㅂ[0-9]*ㅅ|[ㅄᄲᇪᄺᄡᄣᄦᇠ]|[ㅅㅆᄴ][0-9]*[ㄲㅅㅆᄴㅂ]|[존좉좇][0-9 ]*나|[자보][0-9]+지|보빨|[봊봋봇봈볻봁봍] *[빨이]|[후훚훐훛훋훗훘훟훝훑][장앙]|[엠앰]창|애[미비]|애자|[가-탏탑-힣]색기|(?:[샊샛세쉐쉑쉨쉒객갞갟객갞갟갯갰갴겍겎겏겤곅곆곇곗곘곜걕걖걗걧걨걬] *[끼키퀴])|새 *[키퀴]|[병븅][0-9]*[신딱딲]|미친[가-닥-힣]|[믿밑]힌|[염옘][0-9]*병|[샊샛샜샠섹섺셋셌셐셱솃솄솈섁섂섓섔섘]기|[섹섺섻섹쎆쎇쎽쎾쎿섁섂섃썍썎썏][스쓰]|[지야][0-9]*랄|니[애에]미|갈[0-9]*보[^가-힣]|[뻐뻑뻒뻙뻨][0-9]*[뀨큐킹낑]|꼬[0-9]*추|곧[0-9]*휴|[가-힣]슬아치|자[0-9]*박꼼|빨통|[사싸](?:이코|가지|[0-9]*까시)|육[0-9]*시[랄럴]|육[0-9]*실[알얼할헐]|즐[^가-힣]|찌[0-9]*(?:질이|랭이)|찐[0-9]*따|찐[0-9]*찌버거|창[녀놈]|[가-힣]{2,}충[^가-힣]|[가-힣]{2,}츙|부녀자|화냥년|환[양향]년|호[0-9]*[구모]|조[선센][징]|조센|[쪼쪽쪾](?:[발빨]이|[바빠]리)|盧|무현|찌끄[레래]기|(?:하악){2,}|하[앍앜]|[낭당랑앙항남담랑앙함][ ]?[가-힣]+[띠찌]|느[금급]마|文전|이성계|(?<=[^\n])[家哥]|속냐|[tT]l[qQ]kf|Wls|[ㅂ]신|[ㅅ]발|[ㅈ]밥",
            Pattern.CASE_INSENSITIVE
    );

    // 비속어 목록을 담아둘 메모리 캐시
    private HashSet<String> badWordsList;

    @PostConstruct
    public void loadBadWords() {
        this.badWordsList = badWordRepository.findAllWords();
        log.info("비속어 목록 로드 완료: {}건", badWordsList.size());
    }

    public CheckResult isChecked(String input) {
        HashSet<String> badWordList = badWordsList;
        // [Step 1] 문장 전체 정규식 검사 (숫자/공백 포함 욕설 탐지)
        // 정규식 자체가 띄어쓰기와 숫자를 대응하도록 되어 있으므로 원본 문장에 먼저 적용합니다.
        log.info("입력문장:{}",input);

        //NFC 정규화를 제일 먼저 진행
        String nfcString = Normalizer.normalize(input, Normalizer.Form.NFC);
        // 숫자, 특수문자, 공백,이모지,외국어 제거
        String cleaneInput = nfcString.replaceAll("[^가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z]", "");
        // 반복되는 문자열 제거
        String normalized = cleaneInput.replaceAll("(.)\\1+", "$1");


        // NFC까지 완료된 데이터로 정규화 패턴 매칭
        var matcher = BAD_WORD_PATTERN.matcher(normalized);
        if (matcher.find()) {
            String detected = matcher.group();
            log.warn("정규식 매칭 감지: detected=[{}]", detected);
            return new CheckResult(true, "비속어가 감지되었습니다.", "Regex filter", 1.0, detected, "");
        }


        // 원본 문자열을 기준으로 비교
        String[] words = input.split("\\s+");
        for (String word : words) {
            // 특수문자 제거 후 정규화 (자음/모음 ㄱ-ㅎㅏ-ㅣ 포함)
            String cleaned = word.replaceAll("[^가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z]", "");
            if (cleaned.isEmpty()) continue;

            //반복된 글자를 하나로 줄여주는 정규화
            String normalizedWord = cleaned.replaceAll("(.)\\1+", "$1");
            if (normalized.isEmpty()) continue;

            for (String badWord : badWordList) {
                if(normalizedWord.equals(badWord)){
                    return new CheckResult(true, "비속어가 감지되었습니다.", "1:1 데이터 매핑", 1.0, word, "");
                }

            }


        }

        // DB 기반 Fuzzy 매칭 할건데 정규화 다 된 데이터로 전달
        if (isBadWord(normalized, badWordsList)) {
            log.warn("Fuzzy 매칭 감지: normalized=[{}]", normalized);
            return new CheckResult(true, "비속어가 감지되었습니다.", "Fuzzy filter", 1.0, normalized, "");
        }

        // [Step 3] AI 기반(Vector/LLM) 검사 수행
        log.info("기본 필터링 통과. AI 기반 검사(checkBadWord)를 수행합니다.");
        return checkBadWord(input);
    }
    /**
     * 사용자가 입력한 문장의 비속어 여부를 판별하는 핵심 메소드
     *
     * @param userInput 사용자가 입력한 문장
     * @return 비속어 여부, 유사도, 매칭된 단어 등을 포함한 CheckResult 객체
     */
    public CheckResult checkBadWord(String userInput) {
        // 1. [유사도 검색] VectorStore를 사용하여 입력된 문장과 가장 유사한 데이터를 검색합니다.
        log.info("임베딩검증 입력값  : {}",userInput);
        List<Document> results = badWordValidService.selectVector(userInput);

        log.info("임베딩 검증 결과 없음");
        // 2. [검색 결과 확인] 만약 DB에 비교할 데이터가 전혀 없다면 LLM에게 직접 물어봅니다.
        if (results.isEmpty()) {
            log.info("LLM검증 시작");
            return badWordValidService.askLLM(userInput,"조회된 데이터가 없습니다.");
        }else {
            log.info("2차 임베딩 결과 검증");
            CheckResult checkResult = badWordValidService.checkResult(results);
            // 임베딩 결과 비속어라고 판단된 경우
            if (checkResult.isBad()) {
                // 6. [결과 객체 반환] 최종 판별 결과와 관련 정보를 담은 DTO(CheckResult)를 생성하여 반환합니다.
                return checkResult;
            } else {
                //임베딩 결과가 비속어가 아님으로 판단된 경우
                log.info("4차 LLM검증");
                //LLM 조회 전 대상 문장 적재
                savePendingWord(userInput, checkResult);
                // 유사도가 낮으면 LLM 판별을 수행
                CheckResult llmResult = badWordValidService.askLLM(userInput, checkResult.allItemsLog());
                //LLM조회 후 결과 저장
                updatePendingWord(String.valueOf(llmResult.isBad()), userInput);

                return llmResult;
            }
        }
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
    public static boolean isBadWord(String cleanInput, HashSet<String> badWordList) {

        // 사용자 입력값 자모분리
        String jamoInput = decompose(cleanInput);
        int inputLen = jamoInput.length();

        // 저장된 욕설 리스트
        for (String badWord : badWordList) {
            // 욕설 자모분리
            String jamoBad = decompose(badWord);
            int badLen = jamoBad.length();

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
}

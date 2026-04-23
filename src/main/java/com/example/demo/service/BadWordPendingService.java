package com.example.demo.service;

import com.example.demo.domain.BadWord;
import com.example.demo.dto.CheckResult;
import com.example.demo.repository.BadWordRepository;
import com.example.demo.repository.PendingBadWordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

@Slf4j
@Service
@RequiredArgsConstructor
public class BadWordPendingService {

    private final VectorStore vectorStore;
    private final BadWordValidService badWordValidService;
    private final PendingBadWordRepository pendingBadWordRepository;
    private final BadWordRepository badWordRepository;
    // Jaro-Winkler 유사도 객체
    private static final JaroWinklerSimilarity jw = new JaroWinklerSimilarity();

    // 차단 기준 임계치 (자모 분리를 하면 유사도가 높게 나오므로 0.88 정도로 타이트하게 잡습니다)
    private static final double THRESHOLD = 0.88;

    public CheckResult isChecked(String input) {
        // 1. 문장 분리 (공백 기준)
        String[] words = input.split("\\s+");
        log.info("입력 문장 분석 :: {}개의 단어 감지", words.length);

        // 2. 비속어 정규식 패턴 (속도 최적화를 위해 미리 컴파일)
        String regex = "[시씨씪슈쓔쉬쉽쒸쓉](?:[0-9]*|[0-9]+ *)[바발벌빠빡빨뻘파팔펄]|[섊좆좇졷좄좃좉졽썅춍봊]|[ㅈ조][0-9]*까|ㅅㅣㅂㅏㄹ?|ㅂ[0-9]*ㅅ|[ㅄᄲᇪᄺᄡᄣᄦᇠ]|[ㅅㅆᄴ][0-9]*[ㄲㅅㅆᄴㅂ]|[존좉좇][0-9 ]*나|[자보][0-9]+지|보빨|[봊봋봇봈볻봁봍] *[빨이]|[후훚훐훛훋훗훘훟훝훑][장앙]|[엠앰]창|애[미비]|애자|[가-탏탑-힣]색기|(?:[샊샛세쉐쉑쉨쉒객갞갟객갞갟갯갰갴겍겎겏겤곅곆곇곗곘곜걕걖걗걧걨걬] *[끼키퀴])|새 *[키퀴]|[병븅][0-9]*[신딱딲]|미친[가-닥-힣]|[믿밑]힌|[염옘][0-9]*병|[샊샛샜샠섹섺셋셌셐셱솃솄솈섁섂섓섔섘]기|[섹섺섻쎅쎆쎇쎽쎾쎿섁섂섃썍썎썏][스쓰]|[지야][0-9]*랄|니[애에]미|갈[0-9]*보[^가-힣]|[뻐뻑뻒뻙뻨][0-9]*[뀨큐킹낑]|꼬[0-9]*추|곧[0-9]*휴|[가-힣]슬아치|자[0-9]*박꼼|빨통|[사싸](?:이코|가지|[0-9]*까시)|육[0-9]*시[랄럴]|육[0-9]*실[알얼할헐]|즐[^가-힣]|찌[0-9]*(?:질이|랭이)|찐[0-9]*따|찐[0-9]*찌버거|창[녀놈]|[가-힣]{2,}충[^가-힣]|[가-힣]{2,}츙|부녀자|화냥년|환[양향]년|호[0-9]*[구모]|조[선센][징]|조센|[쪼쪽쪾](?:[발빨]이|[바빠]리)|盧|무현|찌끄[레래]기|(?:하악){2,}|하[앍앜]|[낭당랑앙항남담랑앙함][ ]?[가-힣]+[띠찌]|느[금급]마|文전|이성계|(?<=[^\n])[家哥]|속냐|[tT]l[qQ]kf|Wls|[ㅂ]신|[ㅅ]발|[ㅈ]밥";
        Pattern pattern = Pattern.compile(regex);

        List<String> badWords = badWordRepository.findAllWords();

        boolean isBad = false;
        String detectedWord = "NONE";
        String sentenceTypes = "IMMORAL_NONE";
        double similarity = 0.0;
        StringBuilder normalizedSentence = new StringBuilder();


        for (String word : words) {
            // [Step 1] 단어 정제 (특수문자/숫자 제거)
            String cleaned = word.replaceAll("[^가-힣a-zA-Z]", "");
            log.info("clean: {}",cleaned);
            if (cleaned.isEmpty()) continue;

            // [Step 2] 단어 정규화 (반복 문자 축소)
            String normalized = cleaned.replaceAll("(.)\\1+", "$1");
            normalizedSentence.append(normalized).append(" ");
            log.info("normalized: {}",normalized);

            // [Step 3: 정규식 매칭 체크] -> 매칭 시 즉시 break
            if (pattern.matcher(normalized).find()) {
                isBad = true;
                detectedWord = word;
                log.warn("정규식 매칭 감지: word=[{}], normalized=[{}]", word, normalized);
                break;
            }

            if(isBadWord(normalized,badWords)){
                detectedWord = word;
                break;
            }
            return checkBadWord(input);

        }

        return new CheckResult(
                isBad,
                "비속어가 감지되었습니다.",
                sentenceTypes,
                similarity,
                detectedWord
        );
    }
    /**
     * 사용자가 입력한 문장의 비속어 여부를 판별하는 핵심 메소드
     *
     * @param userInput 사용자가 입력한 문장
     * @return 비속어 여부, 유사도, 매칭된 단어 등을 포함한 CheckResult 객체
     */
    public CheckResult checkBadWord(String userInput) {
        // 1. [유사도 검색] VectorStore를 사용하여 입력된 문장과 가장 유사한 데이터를 검색합니다.
        log.info("2차 임베딩검증 입력값  : {}",userInput);
        List<Document> results = badWordValidService.selectVector(userInput);

        log.info("2차 임베딩검증");
        // 2. [검색 결과 확인] 만약 DB에 비교할 데이터가 전혀 없다면 LLM에게 직접 물어봅니다.
        if (results.isEmpty()) {
            log.info("3차 LLM검증");
            return badWordValidService.askLLM(userInput);
        }

        CheckResult checkResult = badWordValidService.checkResult(results);
        log.info("2차 임베딩 결과 검증");
        if (checkResult.isBad()) {
            // 6. [결과 객체 반환] 최종 판별 결과와 관련 정보를 담은 DTO(CheckResult)를 생성하여 반환합니다.
            return checkResult;
        } else {
            log.info("4차 LLM검증");
            //LLM 조회 전 대상 문장 적재
            savePendingWord(userInput, checkResult);
            // 유사도가 낮으면 LLM 판별을 한 번 더 수행합니다.
            CheckResult llmResult = badWordValidService.askLLM(userInput);
            //LLM조회 후 결과 저장
            updatePendingWord(String.valueOf(llmResult.isBad()),userInput);

            return llmResult;
        }
    }

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

    public void updatePendingWord(String response,String pendingBadWord){
        pendingBadWordRepository.updatePendingWord(response,pendingBadWord);
    }

   //자모분리
    public static String decompose(String input) {
        // Normalizer.Form.NFD는 한글을 초/중/종성으로 분리하는 Java 표준 API입니다.
        return Normalizer.normalize(input, Normalizer.Form.NFD);
    }

    public static boolean isBadWord(String input, List<String> badWordList) {

        // 입력값 자모 분리 (Fuzzy 검사용)
        String jamoInput = decompose(input);

        // [Step 3] DB에서 가져온 리스트 순회
        for (String badWord : badWordList) {

            // ==========================================
            // [Step 4] Contains 검사 (완전 일치 및 붙여쓰기 포획)
            // ==========================================
            // 1. 비속어가 1글자면 무조건 완전 일치만 체크
            if (badWord.length() == 1) {
                if (input.equals(badWord)) return true;
            }else if(input.contains(badWord)) {
                System.out.println("[Step 4 차단] Contains 감지: '" + input + "'");
                return true;
            }

            // ==========================================
            // [Step 5] 자모 분리 + N-Gram Fuzzy 검사 (오타, 변종 포획)
            // ==========================================
            String jamoBad = decompose(badWord);
            int badLen = jamoBad.length();     // 자모 분리된 비속어의 길이
            int inputLen = jamoInput.length(); // 자모 분리된 입력값의 길이

            if (inputLen < badLen) continue;

            // 자모 단위로 슬라이딩 윈도우 진행
            for (int i = 0; i <= inputLen - badLen; i++) {
                String window = jamoInput.substring(i, i + badLen);
                double score = jw.apply(window, jamoBad);

                if (score >= THRESHOLD) {
                    System.out.printf("[Step 5 차단] 변종 감지! 기준:'%s' | 유사도: %.2f\n", badWord, score);
                    return true;
                }
            }
        }
        return false;
    }
}

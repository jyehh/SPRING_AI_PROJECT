package com.example.demo.service;

import com.example.demo.domain.BadWord;
import com.example.demo.repository.BadWordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckWordService {

    private final VectorStore vectorStore;
    private final BadWordRepository badWordRepository;

    public Map<String, String> isChecked(String input) {
        // 1. 문장 분리 (공백 기준)
        String[] words = input.split("\\s+");
        log.info("입력 문장 분석 :: {}개의 단어 감지", words.length);

        // 2. 비속어 정규식 패턴 (속도 최적화를 위해 미리 컴파일)
        String regex = "[시씨씪슈쓔쉬쉽쒸쓉](?:[0-9]*|[0-9]+ *)[바발벌빠빡빨뻘파팔펄]|[섊좆좇졷좄좃좉졽썅춍봊]|[ㅈ조][0-9]*까|ㅅㅣㅂㅏㄹ?|ㅂ[0-9]*ㅅ|[ㅄᄲᇪᄺᄡᄣᄦᇠ]|[ㅅㅆᄴ][0-9]*[ㄲㅅㅆᄴㅂ]|[존좉좇][0-9 ]*나|[자보][0-9]+지|보빨|[봊봋봇봈볻봁봍] *[빨이]|[후훚훐훛훋훗훘훟훝훑][장앙]|[엠앰]창|애[미비]|애자|[가-탏탑-힣]색기|(?:[샊샛세쉐쉑쉨쉒객갞갟객갞갟갯갰갴겍겎겏겤곅곆곇곗곘곜걕걖걗걧걨걬] *[끼키퀴])|새 *[키퀴]|[병븅][0-9]*[신딱딲]|미친[가-닥-힣]|[믿밑]힌|[염옘][0-9]*병|[샊샛샜샠섹섺셋셌셐셱솃솄솈섁섂섓섔섘]기|[섹섺섻쎅쎆쎇쎽쎾쎿섁섂섃썍썎썏][스쓰]|[지야][0-9]*랄|니[애에]미|갈[0-9]*보[^가-힣]|[뻐뻑뻒뻙뻨][0-9]*[뀨큐킹낑]|꼬[0-9]*추|곧[0-9]*휴|[가-힣]슬아치|자[0-9]*박꼼|빨통|[사싸](?:이코|가지|[0-9]*까시)|육[0-9]*시[랄럴]|육[0-9]*실[알얼할헐]|즐[^가-힣]|찌[0-9]*(?:질이|랭이)|찐[0-9]*따|찐[0-9]*찌버거|창[녀놈]|[가-힣]{2,}충[^가-힣]|[가-힣]{2,}츙|부녀자|화냥년|환[양향]년|호[0-9]*[구모]|조[선센][징]|조센|[쪼쪽쪾](?:[발빨]이|[바빠]리)|盧|무현|찌끄[레래]기|(?:하악){2,}|하[앍앜]|[낭당랑앙항남담랑앙함][ ]?[가-힣]+[띠찌]|느[금급]마|文전|이성계|(?<=[^\n])[家哥]|속냐|[tT]l[qQ]kf|Wls|[ㅂ]신|[ㅅ]발|[ㅈ]밥";
        Pattern pattern = Pattern.compile(regex);

        boolean isBad = false;
        String detectedWord = "N/A";
        StringBuilder normalizedSentence = new StringBuilder();

        for (String word : words) {
            // [Step 1] 단어 정제 (특수문자/숫자 제거)
            String cleaned = word.replaceAll("[^가-힣a-zA-Z]", "");
            if (cleaned.isEmpty()) continue;

            // [Step 2] 단어 정규화 (반복 문자 축소)
            String normalized = cleaned.replaceAll("(.)\\1+", "$1");
            normalizedSentence.append(normalized).append(" ");

            // [Step 3: 방어선 1] 정규식 매칭 체크 -> DB(JPA) 확인
            if (pattern.matcher(normalized).find()) {
                log.info("정규식 매칭 감지: [{}], DB 추가 확인 진행", normalized);
                Optional<BadWord> badWord = badWordRepository.findByWord(normalized);
                if (badWord.isPresent()) {
                    isBad = true;
                    detectedWord = word;
                    log.warn("정규식 및 DB(JPA) 매칭 완료: word=[{}], normalized=[{}]", word, normalized);
                    break;
                }
            }

            // [Step 4: 방어선 2] 벡터 유사도 검색 (DB 조회)
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(normalized)
                            .topK(1)
                            .filterExpression("file == 'word'")
                            .build()
            );

            if (!results.isEmpty()) {
                Document topResult = results.get(0);
                double similarity = (topResult.getScore() != null) ? topResult.getScore() : 0.0;
                Object typesObj = topResult.getMetadata().getOrDefault("types", "N/A");
                String sentenceTypes = String.valueOf(typesObj).replaceAll("[\\[\\]\"]", "");

                // 단어 단위 검색이므로 유사도 기준을 엄격하게(0.85) 설정
                if (similarity > 0.85 && !sentenceTypes.contains("IMMORAL_NONE")) {
                    isBad = true;
                    detectedWord = word;
                    log.warn("벡터 DB 매칭 감지: word=[{}], similarity={}", word, similarity);
                    break;
                }
            }
        }

        Map<String, String> result = new HashMap<>();
        result.put("origin", input);
        result.put("isBad", String.valueOf(isBad));
        result.put("detectedWord", detectedWord);
        result.put("normalizedSentence", normalizedSentence.toString().trim());

        return result;
    }

}

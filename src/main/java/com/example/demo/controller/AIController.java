package com.example.demo.controller;

import com.example.demo.dto.CheckResult;
import com.example.demo.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@RestController
@RequestMapping("/ai")
@Slf4j
public class AIController {

  @Autowired
  private BadWordSaveService badWordSaveService;
  @Autowired
  private BadWordSelectService badWordService;
  @Autowired
  private BadWordSendService badWordSendService;
  @Autowired
  private BadWordPendingService badWordPendingService;

  @GetMapping("/healthcheck")
  public ResponseEntity<String> healthcheck() {
    return ResponseEntity.ok("200 OK");
  }


    // 1. 입력값 embedding 저장
    @PostMapping("/check")
    public ResponseEntity<CheckResult> checkWord(@RequestBody Map<String, String> request) {
      String sentence = request.get("sentence");

      if (sentence == null || sentence.trim().isEmpty()) {
        return ResponseEntity.badRequest().build();
      }

      try {
        badWordSaveService.saveBadWord(sentence);
        return ResponseEntity.ok(new CheckResult(false, "성공적으로 등록되었습니다.", "N/A", 0.0, null,null));
      } catch (Exception e) {
        return ResponseEntity.internalServerError().body(new CheckResult(true, "에러 발생: " + e.getMessage(), "ERROR", 0.0, null,null));
      }
    }

  // 2.입력문장 RAG 조회
    @PostMapping("/check/v2")
    public ResponseEntity<CheckResult> checkWordv2(@RequestBody Map<String, String> request){
      String sentence = request.get("sentence");

      if (sentence == null || sentence.trim().isEmpty()) {
        return ResponseEntity.badRequest().build();
      }

      CheckResult result = badWordService.checkBadWordV2(sentence);
      return ResponseEntity.ok(result);
    }

    // 3. 입력값 RAG 조회 후 pass 하면 LLM 호출
    @PostMapping("/check/v3")
    public ResponseEntity<CheckResult> checkWordv3(@RequestBody Map<String, String> request){
      String sentence = request.get("sentence");

      if (sentence == null || sentence.trim().isEmpty()) {
        return ResponseEntity.badRequest().build();
      }

      CheckResult result = badWordSendService.checkBadWordV3(sentence);

      return ResponseEntity.ok(result);
    }

  // 전처리작업 이후 RAG 조회 -> LLM 호출
    @PostMapping("/check/v4")
    public ResponseEntity<CheckResult> checkWordv4(@RequestBody Map<String, String> request){
      String sentence = request.get("sentence");

      if (sentence == null || sentence.trim().isEmpty()) {
        return ResponseEntity.badRequest().build();
      }

      CheckResult result = badWordPendingService.checkBadWordV4(sentence);

      return ResponseEntity.ok(result);
    }

}

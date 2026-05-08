package com.example.demo.controller;

import com.example.demo.config.BadWordJsonDataInitializer;
import com.example.demo.dto.CheckRequest;
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
  @Autowired
  private BadWordJsonDataInitializer badWordJsonDataInitializer;

  @GetMapping("/healthcheck")
  public ResponseEntity<String> healthcheck() {
    return ResponseEntity.ok("200 OK");
  }

  /**
   * JSON 데이터를 비동기적으로 초기화하는 API
   */
  @PostMapping("/init-json")
  public ResponseEntity<String> initJsonData() {
    badWordJsonDataInitializer.initializeData();
    return ResponseEntity.ok("JSON 데이터 초기화가 비동기적으로 시작되었습니다.");
  }


    // 1. 입력값 embedding 저장
    @PostMapping("/check/v1")
    public ResponseEntity<CheckResult> checkWord(@RequestBody CheckRequest request) {
      validateRequest(request);

      try {
        badWordSaveService.saveBadWord(request.sentence());
        return ResponseEntity.ok(CheckResult.save());
      } catch (Exception e) {
        return ResponseEntity.internalServerError().body(CheckResult.error(e.getMessage()));
      }
    }

  // 2.입력문장 RAG 조회
    @PostMapping("/check/v2")
    public ResponseEntity<CheckResult> checkWordv2(@RequestBody CheckRequest request){
      validateRequest(request);

      CheckResult result = badWordService.checkBadWordV2(request.sentence());
      return ResponseEntity.ok(result);
    }

    // 3. 입력값 RAG 조회 후 pass 하면 LLM 호출
    @PostMapping("/check/v3")
    public ResponseEntity<CheckResult> checkWordv3(@RequestBody CheckRequest request){
      validateRequest(request);

      CheckResult result = badWordSendService.checkBadWordV3(request.sentence());

      return ResponseEntity.ok(result);
    }

  // 전처리작업 이후 RAG 조회 -> LLM 호출
    @PostMapping("/check/v4")
    public ResponseEntity<CheckResult> checkWordv4(@RequestBody CheckRequest request){
      validateRequest(request);

      CheckResult result = badWordPendingService.checkBadWordV4(request.sentence());

      return ResponseEntity.ok(result);
    }

    private void validateRequest(CheckRequest request) {
        if (request == null || request.sentence() == null || request.sentence().trim().isEmpty()) {
            throw new IllegalArgumentException("입력 문장은 필수입니다.");
        }
    }

}

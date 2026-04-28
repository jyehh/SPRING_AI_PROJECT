package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record LlmCheckResponse(
        @JsonPropertyDescription("입력 문장에 비속어, 욕설 또는 유해한 표현이 포함되어 있는지 여부")
        @JsonProperty("is_bad")
        boolean isBad,

        @JsonPropertyDescription("비속어의 카테고리 (예: 직설적 욕설, 성적 희롱, 차별/비하, NONE)")
        @JsonProperty("category")
        String category,

        @JsonPropertyDescription("분류 결과에 대한 간단한 사유")
        @JsonProperty("reason")
        String reason
) {}

package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;

public record CheckRequest(
        @NotBlank(message = "sentence is required")
        String sentence
) {}

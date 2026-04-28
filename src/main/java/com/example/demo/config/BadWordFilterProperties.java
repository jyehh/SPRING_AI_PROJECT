package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "filter")
@Data
public class BadWordFilterProperties {

    private Fuzzy fuzzy = new Fuzzy();
    private Rag rag = new Rag();
    private Cache cache = new Cache();

    @Data
    public static class Fuzzy {
        private double threshold = 0.88;
    }

    @Data
    public static class Rag {
        private double minSimilarity = 0.50;
        private double similarityThreshold = 0.80;
        private int topK = 5;
    }

    @Data
    public static class Cache {
        private int maxSize = 1000;
    }
}

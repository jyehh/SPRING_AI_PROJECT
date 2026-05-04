package com.example.demo.config;

import com.example.demo.dto.LlmCheckResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
public class ChatClientConfig {

    @Value("classpath:/prompts/bad-word-filter.st")
    private Resource systemPromptResource;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder){
//        var converter = new BeanOutputConverter<>(LlmCheckResponse.class);

        return builder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultSystem(s -> s.text(systemPromptResource))
//                        .param("format", converter.getFormat()))
                .build();
    }
}

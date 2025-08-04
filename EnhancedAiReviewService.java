package com.vibecoding.aicodereview.service;

import com.vibecoding.aicodereview.model.ReviewComment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class EnhancedAiReviewService {
    
    private final AiReviewService originalAiReviewService;
    private final AzureOpenAiService azureOpenAiService;
    private final GitHubCopilotService gitHubCopilotService;
    
    public EnhancedAiReviewService(
            AiReviewService originalAiReviewService,
            @Autowired(required = false) AzureOpenAiService azureOpenAiService,
            @Autowired(required = false) GitHubCopilotService gitHubCopilotService) {
        this.originalAiReviewService = originalAiReviewService;
        this.azureOpenAiService = azureOpenAiService;
        this.gitHubCopilotService = gitHubCopilotService;
    }
    
    public Mono<AiReviewResult> analyzeCode(String code, String fileName, String language, String reviewFocus) {
        // Priority: GitHub Copilot > Azure OpenAI > OpenAI > Mock Service
        
        if (gitHubCopilotService != null && gitHubCopilotService.isConfigured()) {
            log.info("Using GitHub Copilot for code analysis");
            return gitHubCopilotService.analyzeCode(code, fileName, language, reviewFocus)
                .map(this::convertCopilotResult)
                .onErrorResume(throwable -> {
                    log.warn("GitHub Copilot failed, falling back to Azure OpenAI", throwable);
                    return fallbackToAzureOpenAI(code, fileName, language, reviewFocus);
                });
        } else if (azureOpenAiService != null && azureOpenAiService.isConfigured()) {
            log.info("Using Azure OpenAI for code analysis");
            return azureOpenAiService.analyzeCode(code, fileName, language, reviewFocus)
                .map(this::convertAzureResult)
                .onErrorResume(throwable -> {
                    log.warn("Azure OpenAI failed, falling back to OpenAI", throwable);
                    return originalAiReviewService.analyzeCode(code, fileName, language, reviewFocus);
                });
        } else {
            log.info("Using OpenAI for code analysis");
            return originalAiReviewService.analyzeCode(code, fileName, language, reviewFocus);
        }
    }
    
    private Mono<AiReviewResult> fallbackToAzureOpenAI(String code, String fileName, String language, String reviewFocus) {
        if (azureOpenAiService != null && azureOpenAiService.isConfigured()) {
            return azureOpenAiService.analyzeCode(code, fileName, language, reviewFocus)
                .map(this::convertAzureResult)
                .onErrorResume(throwable -> {
                    log.warn("Azure OpenAI fallback failed, using OpenAI", throwable);
                    return originalAiReviewService.analyzeCode(code, fileName, language, reviewFocus);
                });
        } else {
            return originalAiReviewService.analyzeCode(code, fileName, language, reviewFocus);
        }
    }
    
    private AiReviewService.AiReviewResult convertCopilotResult(GitHubCopilotService.CopilotReviewResult copilotResult) {
        AiReviewService.AiReviewResult result = new AiReviewService.AiReviewResult();
        result.setQualityScore(copilotResult.getQualityScore());
        result.setSummary(copilotResult.getSummary());
        result.setComments(copilotResult.getComments());
        return result;
    }
    
    private AiReviewService.AiReviewResult convertAzureResult(AzureOpenAiService.AiReviewResult azureResult) {
        AiReviewService.AiReviewResult result = new AiReviewService.AiReviewResult();
        result.setQualityScore(azureResult.getQualityScore());
        result.setSummary(azureResult.getSummary());
        result.setComments(azureResult.getComments());
        return result;
    }
    
    public String getActiveService() {
        if (gitHubCopilotService != null && gitHubCopilotService.isConfigured()) {
            return "GitHub Copilot";
        } else if (azureOpenAiService != null && azureOpenAiService.isConfigured()) {
            return "Azure OpenAI";
        } else {
            return "OpenAI";
        }
    }
    
    public boolean isAnyServiceConfigured() {
        return (gitHubCopilotService != null && gitHubCopilotService.isConfigured()) ||
               (azureOpenAiService != null && azureOpenAiService.isConfigured()) ||
               originalAiReviewService != null;
    }
    
    public List<String> getAvailableServices() {
        List<String> services = new ArrayList<>();
        
        if (gitHubCopilotService != null && gitHubCopilotService.isConfigured()) {
            services.add("GitHub Copilot");
        }
        if (azureOpenAiService != null && azureOpenAiService.isConfigured()) {
            services.add("Azure OpenAI");
        }
        if (originalAiReviewService != null) {
            services.add("OpenAI");
        }
        
        if (services.isEmpty()) {
            services.add("Mock Service");
        }
        
        return services;
    }
    
    // Alias for the result class to maintain compatibility
    public static class AiReviewResult extends AiReviewService.AiReviewResult {
    }
} 
package com.vibecoding.aicodereview.controller;

import com.vibecoding.aicodereview.dto.ReviewRequest;
import com.vibecoding.aicodereview.dto.ReviewResult;
import com.vibecoding.aicodereview.service.GitHubCopilotService;
import com.vibecoding.aicodereview.service.GitHubService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/github/copilot")
@Slf4j
@ConditionalOnProperty(name = "github.copilot.enabled", havingValue = "true")
public class GitHubCopilotController {
    
    private final GitHubCopilotService copilotService;
    private final GitHubService gitHubService;
    
    public GitHubCopilotController(
            @Autowired(required = false) GitHubCopilotService copilotService,
            @Autowired(required = false) GitHubService gitHubService) {
        this.copilotService = copilotService;
        this.gitHubService = gitHubService;
    }
    
    /**
     * Get GitHub Copilot service status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        boolean configured = copilotService != null && copilotService.isConfigured();
        
        return ResponseEntity.ok(Map.of(
            "enabled", true,
            "configured", configured,
            "copilotApiAvailable", false, // Will be true when GitHub releases Copilot API
            "status", configured ? "ready" : "not configured",
            "features", List.of(
                "code-analysis",
                "security-scanning", 
                "performance-optimization",
                "best-practices",
                "pr-reviews",
                "interactive-chat"
            )
        ));
    }
    
    /**
     * Analyze code with GitHub Copilot
     */
    @PostMapping("/analyze")
    public Mono<ResponseEntity<GitHubCopilotService.CopilotReviewResult>> analyzeCode(
            @RequestBody Map<String, Object> request) {
        
        String code = (String) request.get("code");
        String fileName = (String) request.getOrDefault("fileName", "code.txt");
        String language = (String) request.getOrDefault("language", "auto");
        String reviewFocus = (String) request.getOrDefault("reviewFocus", "general");
        
        if (code == null || code.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        
        return copilotService.analyzeCode(code, fileName, language, reviewFocus)
            .map(ResponseEntity::ok)
            .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    /**
     * Review a specific pull request with GitHub Copilot
     */
    @PostMapping("/review-pr/{repository}/{prNumber}")
    public Mono<ResponseEntity<GitHubCopilotService.CopilotReviewResult>> reviewPullRequest(
            @PathVariable String repository,
            @PathVariable int prNumber,
            @RequestParam(defaultValue = "comprehensive") String reviewType) {
        
        log.info("Starting Copilot review for PR {}/{}", repository, prNumber);
        
        return copilotService.analyzePullRequest(repository, prNumber)
            .map(ResponseEntity::ok)
            .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    /**
     * Post Copilot review results as PR comments
     */
    @PostMapping("/post-review/{repository}/{prNumber}")
    public Mono<ResponseEntity<Map<String, Object>>> postReviewToPR(
            @PathVariable String repository,
            @PathVariable int prNumber,
            @RequestBody Map<String, Object> request) {
        
        Boolean postComments = (Boolean) request.getOrDefault("postComments", true);
        String reviewSummary = (String) request.get("reviewSummary");
        
        return copilotService.analyzePullRequest(repository, prNumber)
            .flatMap(result -> {
                if (postComments) {
                    String summary = reviewSummary != null ? reviewSummary : result.getSummary();
                    return gitHubService.postPullRequestComment(repository, prNumber, 
                        "🤖 **GitHub Copilot Analysis**\n\n" + summary)
                        .then(Mono.just(ResponseEntity.ok(Map.of(
                            "success", true,
                            "message", "Review posted successfully",
                            "qualityScore", result.getQualityScore(),
                            "commentsCount", result.getComments().size()
                        ))));
                } else {
                    return Mono.just(ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Review completed (not posted)",
                        "result", result
                    )));
                }
            })
            .onErrorReturn(ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Failed to complete review"
            )));
    }
    
    /**
     * Interactive chat with GitHub Copilot about code
     */
    @PostMapping("/chat")
    public Mono<ResponseEntity<Map<String, Object>>> chatWithCopilot(
            @RequestBody Map<String, Object> chatRequest) {
        
        String message = (String) chatRequest.get("message");
        String code = (String) chatRequest.get("code");
        String context = (String) chatRequest.getOrDefault("context", "general");
        
        if (message == null || message.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest()
                .body(Map.of("error", "Message is required")));
        }
        
        return copilotService.chatWithCopilot(message, code, context)
            .map(response -> ResponseEntity.ok(Map.of(
                "response", response,
                "timestamp", System.currentTimeMillis()
            )))
            .onErrorReturn(ResponseEntity.ok(Map.of(
                "response", "I'm not able to process that request right now. Please try again later.",
                "error", true
            )));
    }
    
    /**
     * Get detailed metrics for a repository
     */
    @GetMapping("/metrics/{repository}")
    public Mono<ResponseEntity<Map<String, Object>>> getRepositoryMetrics(
            @PathVariable String repository,
            @RequestParam(defaultValue = "30") int days) {
        
        return copilotService.getRepositoryMetrics(repository, days)
            .map(ResponseEntity::ok)
            .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    /**
     * Suggest improvements for a code file
     */
    @PostMapping("/suggest-improvements")
    public Mono<ResponseEntity<Map<String, Object>>> suggestImprovements(
            @RequestBody Map<String, Object> request) {
        
        String code = (String) request.get("code");
        String fileName = (String) request.getOrDefault("fileName", "code.txt");
        String focusArea = (String) request.getOrDefault("focusArea", "all");
        
        return copilotService.suggestImprovements(code, fileName, focusArea)
            .map(suggestions -> ResponseEntity.ok(Map.of(
                "suggestions", suggestions,
                "fileName", fileName,
                "focusArea", focusArea
            )))
            .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    /**
     * Batch review multiple files
     */
    @PostMapping("/batch-review")
    public Mono<ResponseEntity<Map<String, Object>>> batchReview(
            @RequestBody Map<String, Object> request) {
        
        @SuppressWarnings("unchecked")
        List<Map<String, String>> files = (List<Map<String, String>>) request.get("files");
        String reviewType = (String) request.getOrDefault("reviewType", "comprehensive");
        
        if (files == null || files.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest()
                .body(Map.of("error", "Files array is required")));
        }
        
        return copilotService.batchAnalyzeFiles(files, reviewType)
            .map(results -> ResponseEntity.ok(Map.of(
                "results", results,
                "totalFiles", files.size(),
                "reviewType", reviewType
            )))
            .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    /**
     * Generate code documentation with Copilot
     */
    @PostMapping("/generate-docs")
    public Mono<ResponseEntity<Map<String, Object>>> generateDocumentation(
            @RequestBody Map<String, Object> request) {
        
        String code = (String) request.get("code");
        String language = (String) request.getOrDefault("language", "auto");
        String docStyle = (String) request.getOrDefault("docStyle", "jsdoc");
        
        return copilotService.generateDocumentation(code, language, docStyle)
            .map(documentation -> ResponseEntity.ok(Map.of(
                "documentation", documentation,
                "language", language,
                "style", docStyle
            )))
            .onErrorReturn(ResponseEntity.internalServerError().build());
    }
} 
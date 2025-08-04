package com.vibecoding.aicodereview.controller;

import com.vibecoding.aicodereview.dto.ReviewRequest;
import com.vibecoding.aicodereview.dto.ReviewResult;
import com.vibecoding.aicodereview.model.CodeReview;
import com.vibecoding.aicodereview.service.AzureDevOpsService;
import com.vibecoding.aicodereview.service.CodeReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
public class CodeReviewController {
    
    private final CodeReviewService codeReviewService;
    
    @Autowired(required = false)
    private AzureDevOpsService azureDevOpsService;
    
    @PostMapping("/submit")
    public Mono<ResponseEntity<ReviewResult>> submitCodeForReview(@Valid @RequestBody ReviewRequest request) {
        log.info("Received code review request for file: {}", request.getFileName());
        
        return codeReviewService.submitCodeForReview(request)
            .map(result -> ResponseEntity.ok(result))
            .onErrorResume(throwable -> {
                log.error("Error processing code review request", throwable);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ReviewResult> getReviewById(@PathVariable Long id) {
        log.info("Fetching review with id: {}", id);
        
        Optional<ReviewResult> review = codeReviewService.getReviewById(id);
        return review.map(ResponseEntity::ok)
                     .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping
    public ResponseEntity<List<ReviewResult>> getAllReviews(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String fileName) {
        
        List<ReviewResult> reviews;
        
        if (status != null && !status.isEmpty()) {
            try {
                CodeReview.ReviewStatus reviewStatus = CodeReview.ReviewStatus.valueOf(status.toUpperCase());
                reviews = codeReviewService.getReviewsByStatus(reviewStatus);
                log.info("Fetching reviews with status: {}", reviewStatus);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid status parameter: {}", status);
                return ResponseEntity.badRequest().build();
            }
        } else if (fileName != null && !fileName.isEmpty()) {
            reviews = codeReviewService.searchReviewsByFileName(fileName);
            log.info("Searching reviews by file name: {}", fileName);
        } else {
            reviews = codeReviewService.getAllReviews();
            log.info("Fetching all reviews");
        }
        
        return ResponseEntity.ok(reviews);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteReview(@PathVariable Long id) {
        log.info("Deleting review with id: {}", id);
        
        boolean deleted = codeReviewService.deleteReview(id);
        return deleted ? ResponseEntity.noContent().build() 
                      : ResponseEntity.notFound().build();
    }
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getReviewStats() {
        log.info("Fetching review statistics");
        
        Double averageScore = codeReviewService.getAverageQualityScore();
        List<ReviewResult> allReviews = codeReviewService.getAllReviews();
        
        long totalReviews = allReviews.size();
        long completedReviews = allReviews.stream()
            .filter(review -> review.getStatus() == CodeReview.ReviewStatus.COMPLETED)
            .count();
        long pendingReviews = allReviews.stream()
            .filter(review -> review.getStatus() == CodeReview.ReviewStatus.PENDING || 
                            review.getStatus() == CodeReview.ReviewStatus.IN_PROGRESS)
            .count();
        long failedReviews = allReviews.stream()
            .filter(review -> review.getStatus() == CodeReview.ReviewStatus.FAILED)
            .count();
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalReviews", totalReviews);
        stats.put("completedReviews", completedReviews);
        stats.put("pendingReviews", pendingReviews);
        stats.put("failedReviews", failedReviews);
        stats.put("averageQualityScore", averageScore);
        
        // Add AI service information
        stats.put("activeAiService", codeReviewService.getActiveAiService());
        
        // Add Azure DevOps integration status
        if (azureDevOpsService != null) {
            stats.put("azureDevOpsConfigured", azureDevOpsService.isConfigured());
            stats.put("azureDevOpsEnabled", true);
        } else {
            stats.put("azureDevOpsConfigured", false);
            stats.put("azureDevOpsEnabled", false);
        }
        
        return ResponseEntity.ok(stats);
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "healthy");
        health.put("service", "AI Code Review API");
        health.put("activeAiService", codeReviewService.getActiveAiService());
        
        if (azureDevOpsService != null) {
            health.put("azureDevOpsIntegration", azureDevOpsService.isConfigured() ? "configured" : "not configured");
        } else {
            health.put("azureDevOpsIntegration", "disabled");
        }
        
        return ResponseEntity.ok(health);
    }
    
    @GetMapping("/system-info")
    public ResponseEntity<Map<String, Object>> getSystemInfo() {
        Map<String, Object> systemInfo = new HashMap<>();
        
        // AI Service Information
        systemInfo.put("aiService", Map.of(
            "active", codeReviewService.getActiveAiService(),
            "status", "available"
        ));
        
        // Azure DevOps Information
        if (azureDevOpsService != null) {
            systemInfo.put("azureDevOps", Map.of(
                "enabled", true,
                "configured", azureDevOpsService.isConfigured(),
                "status", azureDevOpsService.isConfigured() ? "configured" : "not configured"
            ));
        } else {
            systemInfo.put("azureDevOps", Map.of(
                "enabled", false,
                "configured", false,
                "status", "disabled"
            ));
        }
        
        // Webhook Information
        systemInfo.put("webhooks", Map.of(
            "enabled", true,
            "endpoint", "/api/webhook/azure-devops"
        ));
        
        return ResponseEntity.ok(systemInfo);
    }
} 
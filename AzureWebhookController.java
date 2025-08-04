package com.vibecoding.aicodereview.controller;

import com.vibecoding.aicodereview.model.azure.AzureWebhookEvent;
import com.vibecoding.aicodereview.service.AzurePullRequestReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "webhook.enabled", havingValue = "true")
public class AzureWebhookController {
    
    private final AzurePullRequestReviewService azurePullRequestReviewService;
    
    @Value("${webhook.secret:default-secret}")
    private String webhookSecret;
    
    @PostMapping("/azure-devops")
    public ResponseEntity<Map<String, String>> handleAzureDevOpsWebhook(
            @RequestBody AzureWebhookEvent webhookEvent,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        
        try {
            log.info("Received Azure DevOps webhook event: {} for PR {}", 
                    webhookEvent.getEventType(), 
                    webhookEvent.getResource() != null ? webhookEvent.getResource().getPullRequestId() : "unknown");
            
            // Basic webhook validation (you can enhance this with proper signature validation)
            if (!isValidWebhook(authorization)) {
                log.warn("Invalid webhook authorization");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Unauthorized"));
            }
            
            // Process only relevant pull request events
            if (webhookEvent.shouldTriggerReview() && webhookEvent.getResource() != null) {
                log.info("Processing pull request event for PR {}", webhookEvent.getResource().getPullRequestId());
                
                // Trigger asynchronous code review
                azurePullRequestReviewService.processPullRequestWebhook(webhookEvent)
                    .doOnSuccess(result -> log.info("Successfully processed webhook for PR {}", 
                                                  webhookEvent.getResource().getPullRequestId()))
                    .doOnError(error -> log.error("Error processing webhook for PR {}", 
                                                 webhookEvent.getResource().getPullRequestId(), error))
                    .subscribe(); // Fire and forget
                
                return ResponseEntity.ok(Map.of(
                    "status", "success", 
                    "message", "Pull request review triggered",
                    "pullRequestId", String.valueOf(webhookEvent.getResource().getPullRequestId())
                ));
            } else {
                log.debug("Webhook event {} does not require review processing", webhookEvent.getEventType());
                return ResponseEntity.ok(Map.of(
                    "status", "ignored", 
                    "message", "Event type does not trigger review"
                ));
            }
            
        } catch (Exception e) {
            log.error("Error processing Azure DevOps webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("status", "error", "message", "Internal server error"));
        }
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> webhookHealth() {
        return ResponseEntity.ok(Map.of(
            "status", "healthy",
            "service", "Azure DevOps Webhook Handler",
            "webhookEnabled", "true"
        ));
    }
    
    @PostMapping("/test")
    public ResponseEntity<Map<String, String>> testWebhook(@RequestBody Map<String, Object> testPayload) {
        log.info("Received test webhook payload: {}", testPayload);
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Test webhook received successfully",
            "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }
    
    private boolean isValidWebhook(String authorization) {
        // Basic validation - in production, implement proper webhook signature validation
        // Azure DevOps can send a basic auth header or custom headers
        if (authorization == null) {
            log.debug("No authorization header provided");
            return true; // Allow for now, enhance security as needed
        }
        
        // You can implement more sophisticated validation here:
        // 1. HMAC signature validation
        // 2. Basic auth token validation
        // 3. IP whitelist validation
        return true;
    }
} 
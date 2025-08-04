package com.vibecoding.aicodereview.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecoding.aicodereview.config.GitHubAppConfig;
import com.vibecoding.aicodereview.service.GitHubAppService;
import com.vibecoding.aicodereview.service.GitHubCopilotService;
import com.vibecoding.aicodereview.service.GitHubService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/webhook/github")
@Slf4j
@ConditionalOnProperty(name = "webhook.enabled", havingValue = "true")
public class GitHubWebhookController {
    
    private final GitHubService gitHubService;
    private final GitHubCopilotService copilotService;
    private final GitHubAppService appService;
    private final GitHubAppConfig appConfig;
    private final ObjectMapper objectMapper;
    
    @Value("${automated.review.enabled:false}")
    private boolean automatedReviewEnabled;
    
    @Value("${automated.review.trigger-on-pr:true}")
    private boolean triggerOnPR;
    
    @Value("${automated.review.min-quality-score:7.0}")
    private double minQualityScore;
    
    @Value("${github.copilot.auto-review-enabled:false}")
    private boolean copilotAutoReviewEnabled;
    
    public GitHubWebhookController(
            @Autowired(required = false) GitHubService gitHubService,
            @Autowired(required = false) GitHubCopilotService copilotService,
            @Autowired(required = false) GitHubAppService appService,
            @Autowired(required = false) GitHubAppConfig appConfig) {
        this.gitHubService = gitHubService;
        this.copilotService = copilotService;
        this.appService = appService;
        this.appConfig = appConfig;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Handle GitHub webhook events
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId) {
        
        log.info("Received GitHub webhook event: {} (delivery: {})", eventType, deliveryId);
        
        // Verify webhook signature if configured
        if (appConfig != null && !appConfig.verifyWebhookSignature(payload, signature)) {
            log.warn("Invalid webhook signature for delivery: {}", deliveryId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid signature"));
        }
        
        try {
            JsonNode webhookData = objectMapper.readTree(payload);
            
            switch (eventType) {
                case "pull_request":
                    return handlePullRequestEvent(webhookData, deliveryId);
                case "push":
                    return handlePushEvent(webhookData, deliveryId);
                case "installation":
                    return handleInstallationEvent(webhookData, deliveryId);
                case "installation_repositories":
                    return handleInstallationRepositoriesEvent(webhookData, deliveryId);
                case "check_run":
                    return handleCheckRunEvent(webhookData, deliveryId);
                case "pull_request_review":
                    return handlePullRequestReviewEvent(webhookData, deliveryId);
                default:
                    log.info("Unhandled webhook event type: {}", eventType);
                    return ResponseEntity.ok(Map.of("message", "Event received but not processed"));
            }
            
        } catch (Exception e) {
            log.error("Error processing webhook event", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to process webhook"));
        }
    }
    
    /**
     * Handle pull request events (opened, synchronized, etc.)
     */
    private ResponseEntity<Map<String, Object>> handlePullRequestEvent(JsonNode data, String deliveryId) {
        String action = data.path("action").asText();
        JsonNode pullRequest = data.path("pull_request");
        JsonNode repository = data.path("repository");
        
        String repoName = repository.path("name").asText();
        String repoOwner = repository.path("owner").path("login").asText();
        int prNumber = pullRequest.path("number").asInt();
        String prTitle = pullRequest.path("title").asText();
        
        log.info("Pull request {} event for {}/{} PR #{}: {}", action, repoOwner, repoName, prNumber, prTitle);
        
        // Trigger automated review for relevant actions
        if (automatedReviewEnabled && copilotAutoReviewEnabled && triggerOnPR && 
            ("opened".equals(action) || "synchronize".equals(action))) {
            
            triggerAutomatedReview(repoOwner, repoName, prNumber, action, deliveryId);
        }
        
        return ResponseEntity.ok(Map.of(
            "message", "Pull request event processed",
            "action", action,
            "repository", repoOwner + "/" + repoName,
            "pullRequest", prNumber
        ));
    }
    
    /**
     * Handle push events
     */
    private ResponseEntity<Map<String, Object>> handlePushEvent(JsonNode data, String deliveryId) {
        JsonNode repository = data.path("repository");
        String repoName = repository.path("name").asText();
        String repoOwner = repository.path("owner").path("login").asText();
        String ref = data.path("ref").asText();
        String commitSha = data.path("head_commit").path("id").asText();
        
        log.info("Push event for {}/{} on {} (commit: {})", repoOwner, repoName, ref, commitSha);
        
        return ResponseEntity.ok(Map.of(
            "message", "Push event processed",
            "repository", repoOwner + "/" + repoName,
            "ref", ref,
            "commit", commitSha
        ));
    }
    
    /**
     * Handle GitHub App installation events
     */
    private ResponseEntity<Map<String, Object>> handleInstallationEvent(JsonNode data, String deliveryId) {
        String action = data.path("action").asText();
        JsonNode installation = data.path("installation");
        long installationId = installation.path("id").asLong();
        
        log.info("Installation {} event for installation ID: {}", action, installationId);
        
        if ("created".equals(action)) {
            log.info("GitHub App installed successfully: {}", installationId);
        } else if ("deleted".equals(action)) {
            log.info("GitHub App uninstalled: {}", installationId);
        }
        
        return ResponseEntity.ok(Map.of(
            "message", "Installation event processed",
            "action", action,
            "installationId", installationId
        ));
    }
    
    /**
     * Handle installation repositories events
     */
    private ResponseEntity<Map<String, Object>> handleInstallationRepositoriesEvent(JsonNode data, String deliveryId) {
        String action = data.path("action").asText();
        JsonNode repositoriesAdded = data.path("repositories_added");
        JsonNode repositoriesRemoved = data.path("repositories_removed");
        
        log.info("Installation repositories {} event", action);
        
        return ResponseEntity.ok(Map.of(
            "message", "Installation repositories event processed",
            "action", action,
            "addedCount", repositoriesAdded.size(),
            "removedCount", repositoriesRemoved.size()
        ));
    }
    
    /**
     * Handle check run events
     */
    private ResponseEntity<Map<String, Object>> handleCheckRunEvent(JsonNode data, String deliveryId) {
        String action = data.path("action").asText();
        JsonNode checkRun = data.path("check_run");
        String checkRunName = checkRun.path("name").asText();
        String status = checkRun.path("status").asText();
        
        log.info("Check run {} event for '{}' (status: {})", action, checkRunName, status);
        
        return ResponseEntity.ok(Map.of(
            "message", "Check run event processed",
            "action", action,
            "checkRunName", checkRunName,
            "status", status
        ));
    }
    
    /**
     * Handle pull request review events
     */
    private ResponseEntity<Map<String, Object>> handlePullRequestReviewEvent(JsonNode data, String deliveryId) {
        String action = data.path("action").asText();
        JsonNode review = data.path("review");
        JsonNode pullRequest = data.path("pull_request");
        
        String reviewState = review.path("state").asText();
        int prNumber = pullRequest.path("number").asInt();
        
        log.info("Pull request review {} event for PR #{} (state: {})", action, prNumber, reviewState);
        
        return ResponseEntity.ok(Map.of(
            "message", "Pull request review event processed",
            "action", action,
            "pullRequest", prNumber,
            "reviewState", reviewState
        ));
    }
    
    /**
     * Trigger automated code review with GitHub Copilot
     */
    private void triggerAutomatedReview(String owner, String repository, int prNumber, 
                                      String action, String deliveryId) {
        if (copilotService == null) {
            log.warn("GitHub Copilot service not available for automated review");
            return;
        }
        
        log.info("Triggering automated Copilot review for {}/{} PR #{}", owner, repository, prNumber);
        
        // Create initial check run
        if (appService != null && appService.isConfigured()) {
            appService.createCheckRun(owner, repository, "", "Copilot Code Review", 
                "in_progress", null, "Running GitHub Copilot analysis...")
                .subscribe(
                    result -> log.info("Created check run for PR review"),
                    error -> log.error("Failed to create check run", error)
                );
        }
        
        // Perform the review asynchronously
        copilotService.analyzePullRequest(repository, prNumber)
            .subscribe(
                result -> handleReviewCompletion(owner, repository, prNumber, result, deliveryId),
                error -> handleReviewError(owner, repository, prNumber, error, deliveryId)
            );
    }
    
    /**
     * Handle successful review completion
     */
    private void handleReviewCompletion(String owner, String repository, int prNumber, 
                                      GitHubCopilotService.CopilotReviewResult result, String deliveryId) {
        log.info("Automated review completed for {}/{} PR #{} with score: {}", 
                owner, repository, prNumber, result.getQualityScore());
        
        // Post review comments if enabled and quality score meets threshold
        if (result.getQualityScore() < minQualityScore && gitHubService != null) {
            String summary = String.format(
                "🤖 **Automated GitHub Copilot Review**\n\n" +
                "Quality Score: %.1f/10\n\n%s\n\n" +
                "Found %d suggestions for improvement. Please review the feedback above.",
                result.getQualityScore(),
                result.getSummary(),
                result.getComments().size()
            );
            
            gitHubService.postPullRequestComment(repository, prNumber, summary)
                .subscribe(
                    success -> log.info("Posted automated review comment to PR #{}", prNumber),
                    error -> log.error("Failed to post review comment", error)
                );
        }
        
        // Update check run if using GitHub App
        if (appService != null && appService.isConfigured()) {
            String conclusion = result.getQualityScore() >= minQualityScore ? "success" : "neutral";
            String summary = String.format("Quality Score: %.1f/10 - %d suggestions", 
                result.getQualityScore(), result.getComments().size());
            
            // Note: We'd need to store the check run ID to update it
            // For now, we'll create a new one with the results
            appService.createCheckRun(owner, repository, "", "Copilot Code Review", 
                "completed", conclusion, summary)
                .subscribe(
                    success -> log.info("Updated check run for PR #{}", prNumber),
                    error -> log.error("Failed to update check run", error)
                );
        }
    }
    
    /**
     * Handle review error
     */
    private void handleReviewError(String owner, String repository, int prNumber, 
                                 Throwable error, String deliveryId) {
        log.error("Automated review failed for {}/{} PR #{}", owner, repository, prNumber, error);
        
        // Update check run with failure status
        if (appService != null && appService.isConfigured()) {
            appService.createCheckRun(owner, repository, "", "Copilot Code Review", 
                "completed", "failure", "Automated review failed: " + error.getMessage())
                .subscribe(
                    success -> log.info("Updated check run with failure status"),
                    updateError -> log.error("Failed to update check run", updateError)
                );
        }
    }
    
    /**
     * Get webhook status and configuration
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getWebhookStatus() {
        return ResponseEntity.ok(Map.of(
            "enabled", true,
            "automatedReviewEnabled", automatedReviewEnabled,
            "copilotAutoReviewEnabled", copilotAutoReviewEnabled,
            "triggerOnPR", triggerOnPR,
            "minQualityScore", minQualityScore,
            "appConfigured", appConfig != null && appConfig.isConfigured(),
            "copilotConfigured", copilotService != null && copilotService.isConfigured(),
            "githubServiceConfigured", gitHubService != null && gitHubService.isConfigured()
        ));
    }
} 
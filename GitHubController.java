package com.vibecoding.aicodereview.controller;

import com.vibecoding.aicodereview.model.github.GitHubPullRequest;
import com.vibecoding.aicodereview.model.github.GitHubRepository;
import com.vibecoding.aicodereview.service.GitHubService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/github")
@Slf4j
@ConditionalOnProperty(name = "github.enabled", havingValue = "true")
public class GitHubController {
    
    private final GitHubService gitHubService;
    
    public GitHubController(@Autowired(required = false) GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }
    
    /**
     * Get GitHub integration status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        boolean configured = gitHubService != null && gitHubService.isConfigured();
        
        return ResponseEntity.ok(Map.of(
            "enabled", true,
            "configured", configured,
            "status", configured ? "configured" : "not configured"
        ));
    }
    
    /**
     * Test GitHub connection
     */
    @GetMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection() {
        if (gitHubService == null) {
            return ResponseEntity.ok(Map.of(
                "connected", false,
                "message", "GitHub service not available"
            ));
        }
        
        return gitHubService.testConnection()
            .map(connected -> ResponseEntity.ok(Map.of(
                "connected", connected,
                "message", connected ? "Connection successful" : "Connection failed"
            )))
            .block();
    }
    
    /**
     * Get all repositories
     */
    @GetMapping("/repositories")
    public ResponseEntity<List<GitHubRepository>> getRepositories() {
        if (gitHubService == null) {
            return ResponseEntity.notFound().build();
        }
        
        return gitHubService.getRepositories()
            .map(ResponseEntity::ok)
            .block();
    }
    
    /**
     * Get a specific repository
     */
    @GetMapping("/repositories/{repositoryName}")
    public ResponseEntity<GitHubRepository> getRepository(@PathVariable String repositoryName) {
        if (gitHubService == null) {
            return ResponseEntity.notFound().build();
        }
        
        return gitHubService.getRepository(repositoryName)
            .map(ResponseEntity::ok)
            .switchIfEmpty(ResponseEntity.notFound().build())
            .block();
    }
    
    /**
     * Get pull requests for a repository
     */
    @GetMapping("/repositories/{repositoryName}/pull-requests")
    public ResponseEntity<List<GitHubPullRequest>> getPullRequests(
            @PathVariable String repositoryName,
            @RequestParam(required = false) String state) {
        
        if (gitHubService == null) {
            return ResponseEntity.notFound().build();
        }
        
        return gitHubService.getPullRequests(repositoryName, state)
            .map(ResponseEntity::ok)
            .block();
    }
    
    /**
     * Get a specific pull request
     */
    @GetMapping("/repositories/{repositoryName}/pull-requests/{pullRequestNumber}")
    public ResponseEntity<GitHubPullRequest> getPullRequest(
            @PathVariable String repositoryName,
            @PathVariable int pullRequestNumber) {
        
        if (gitHubService == null) {
            return ResponseEntity.notFound().build();
        }
        
        return gitHubService.getPullRequest(repositoryName, pullRequestNumber)
            .map(ResponseEntity::ok)
            .switchIfEmpty(ResponseEntity.notFound().build())
            .block();
    }
    
    /**
     * Get file content from repository
     */
    @GetMapping("/repositories/{repositoryName}/contents")
    public ResponseEntity<Map<String, String>> getFileContent(
            @PathVariable String repositoryName,
            @RequestParam String path,
            @RequestParam(required = false) String ref) {
        
        if (gitHubService == null) {
            return ResponseEntity.notFound().build();
        }
        
        String fileRef = ref != null ? ref : "main";
        
        return gitHubService.getFileContent(repositoryName, path, fileRef)
            .map(content -> ResponseEntity.ok(Map.of(
                "content", content,
                "path", path,
                "ref", fileRef
            )))
            .block();
    }
    
    /**
     * Get code scanning alerts for a repository
     */
    @GetMapping("/repositories/{repositoryName}/code-scanning/alerts")
    public ResponseEntity<List<GitHubPullRequest.GitHubCodeScanningAlert>> getCodeScanningAlerts(
            @PathVariable String repositoryName) {
        
        if (gitHubService == null) {
            return ResponseEntity.notFound().build();
        }
        
        return gitHubService.getCodeScanningAlerts(repositoryName)
            .map(ResponseEntity::ok)
            .block();
    }
    
    /**
     * Post a comment to a pull request
     */
    @PostMapping("/repositories/{repositoryName}/pull-requests/{pullRequestNumber}/comments")
    public ResponseEntity<Map<String, Object>> postPullRequestComment(
            @PathVariable String repositoryName,
            @PathVariable int pullRequestNumber,
            @RequestBody Map<String, String> commentRequest) {
        
        if (gitHubService == null) {
            return ResponseEntity.notFound().build();
        }
        
        String body = commentRequest.get("body");
        if (body == null || body.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Comment body is required"));
        }
        
        return gitHubService.postPullRequestComment(repositoryName, pullRequestNumber, body)
            .then(ResponseEntity.ok(Map.of(
                "message", "Comment posted successfully",
                "repository", repositoryName,
                "pullRequest", pullRequestNumber
            )))
            .block();
    }
    
    /**
     * Post a file comment to a pull request
     */
    @PostMapping("/repositories/{repositoryName}/pull-requests/{pullRequestNumber}/file-comments")
    public ResponseEntity<Map<String, Object>> postPullRequestFileComment(
            @PathVariable String repositoryName,
            @PathVariable int pullRequestNumber,
            @RequestBody Map<String, Object> commentRequest) {
        
        if (gitHubService == null) {
            return ResponseEntity.notFound().build();
        }
        
        String body = (String) commentRequest.get("body");
        String path = (String) commentRequest.get("path");
        Integer line = (Integer) commentRequest.get("line");
        
        if (body == null || path == null || line == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "body, path, and line are required"
            ));
        }
        
        return gitHubService.postPullRequestFileComment(repositoryName, pullRequestNumber, path, line, body)
            .then(ResponseEntity.ok(Map.of(
                "message", "File comment posted successfully",
                "repository", repositoryName,
                "pullRequest", pullRequestNumber,
                "path", path,
                "line", line
            )))
            .block();
    }
    
    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean configured = gitHubService != null && gitHubService.isConfigured();
        
        return ResponseEntity.ok(Map.of(
            "status", "healthy",
            "service", "GitHub Integration",
            "configured", configured,
            "enabled", true
        ));
    }
} 
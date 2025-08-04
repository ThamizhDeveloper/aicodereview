package com.vibecoding.aicodereview.controller;

import com.vibecoding.aicodereview.model.azure.AzurePullRequest;
import com.vibecoding.aicodereview.model.azure.AzureRepository;
import com.vibecoding.aicodereview.service.AzureDevOpsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/azure-devops")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "azure.devops.enabled", havingValue = "true")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AzureDevOpsController {
    
    private final AzureDevOpsService azureDevOpsService;
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getAzureDevOpsStatus() {
        boolean isConfigured = azureDevOpsService.isConfigured();
        
        Map<String, Object> status = Map.of(
            "configured", isConfigured,
            "service", "Azure DevOps Integration",
            "enabled", true
        );
        
        return ResponseEntity.ok(status);
    }
    
    @GetMapping("/test-connection")
    public Mono<ResponseEntity<Map<String, Object>>> testConnection() {
        return azureDevOpsService.testConnection()
            .map(connectionSuccessful -> {
                Map<String, Object> result = Map.of(
                    "connected", connectionSuccessful,
                    "message", connectionSuccessful ? "Connection successful" : "Connection failed",
                    "timestamp", System.currentTimeMillis()
                );
                
                return connectionSuccessful ? 
                    ResponseEntity.ok(result) : 
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
            })
            .onErrorResume(throwable -> {
                log.error("Error testing Azure DevOps connection", throwable);
                Map<String, Object> errorResult = Map.of(
                    "connected", false,
                    "message", "Connection test failed: " + throwable.getMessage(),
                    "timestamp", System.currentTimeMillis()
                );
                return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResult));
            });
    }
    
    @GetMapping("/repositories")
    public Mono<ResponseEntity<List<AzureRepository>>> getRepositories() {
        log.info("Fetching Azure DevOps repositories");
        
        return azureDevOpsService.getRepositories()
            .map(repositories -> {
                log.info("Found {} repositories", repositories.size());
                return ResponseEntity.ok(repositories);
            })
            .onErrorResume(throwable -> {
                log.error("Error fetching repositories", throwable);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    @GetMapping("/repositories/{repositoryId}")
    public Mono<ResponseEntity<AzureRepository>> getRepository(@PathVariable String repositoryId) {
        log.info("Fetching repository: {}", repositoryId);
        
        return azureDevOpsService.getRepository(repositoryId)
            .map(ResponseEntity::ok)
            .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
            .onErrorResume(throwable -> {
                log.error("Error fetching repository {}", repositoryId, throwable);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    @GetMapping("/repositories/{repositoryId}/pull-requests")
    public Mono<ResponseEntity<List<AzurePullRequest>>> getPullRequests(
            @PathVariable String repositoryId,
            @RequestParam(required = false) String status) {
        
        log.info("Fetching pull requests for repository: {} with status: {}", repositoryId, status);
        
        return azureDevOpsService.getPullRequests(repositoryId, status)
            .map(pullRequests -> {
                log.info("Found {} pull requests", pullRequests.size());
                return ResponseEntity.ok(pullRequests);
            })
            .onErrorResume(throwable -> {
                log.error("Error fetching pull requests for repository {}", repositoryId, throwable);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    @GetMapping("/repositories/{repositoryId}/pull-requests/{pullRequestId}")
    public Mono<ResponseEntity<AzurePullRequest>> getPullRequest(
            @PathVariable String repositoryId,
            @PathVariable int pullRequestId) {
        
        log.info("Fetching pull request: {} from repository: {}", pullRequestId, repositoryId);
        
        return azureDevOpsService.getPullRequest(repositoryId, pullRequestId)
            .map(ResponseEntity::ok)
            .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
            .onErrorResume(throwable -> {
                log.error("Error fetching pull request {} from repository {}", pullRequestId, repositoryId, throwable);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    @GetMapping("/repositories/{repositoryId}/pull-requests/{pullRequestId}/changes")
    public Mono<ResponseEntity<List<AzurePullRequest.AzureFileChange>>> getPullRequestChanges(
            @PathVariable String repositoryId,
            @PathVariable int pullRequestId) {
        
        log.info("Fetching changes for pull request: {} from repository: {}", pullRequestId, repositoryId);
        
        return azureDevOpsService.getPullRequestChanges(repositoryId, pullRequestId)
            .map(changes -> {
                log.info("Found {} file changes", changes.size());
                return ResponseEntity.ok(changes);
            })
            .onErrorResume(throwable -> {
                log.error("Error fetching changes for pull request {} from repository {}", 
                         pullRequestId, repositoryId, throwable);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    @GetMapping("/repositories/{repositoryId}/files")
    public Mono<ResponseEntity<String>> getFileContent(
            @PathVariable String repositoryId,
            @RequestParam String filePath,
            @RequestParam String commitId) {
        
        log.info("Fetching file content: {} from repository: {} at commit: {}", 
                filePath, repositoryId, commitId);
        
        return azureDevOpsService.getFileContent(repositoryId, filePath, commitId)
            .map(content -> {
                if (content.isEmpty()) {
                    return ResponseEntity.notFound().<String>build();
                }
                return ResponseEntity.ok(content);
            })
            .onErrorResume(throwable -> {
                log.error("Error fetching file content {} from repository {}", filePath, repositoryId, throwable);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }
    
    @PostMapping("/repositories/{repositoryId}/pull-requests/{pullRequestId}/comments")
    public Mono<ResponseEntity<Map<String, String>>> postComment(
            @PathVariable String repositoryId,
            @PathVariable int pullRequestId,
            @RequestBody Map<String, String> commentRequest) {
        
        String comment = commentRequest.get("comment");
        if (comment == null || comment.trim().isEmpty()) {
            return Mono.just(ResponseEntity.badRequest()
                .body(Map.of("error", "Comment text is required")));
        }
        
        log.info("Posting comment to pull request: {} in repository: {}", pullRequestId, repositoryId);
        
        return azureDevOpsService.postPullRequestComment(repositoryId, pullRequestId, comment)
            .then(Mono.just(ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Comment posted successfully"
            ))))
            .onErrorResume(throwable -> {
                log.error("Error posting comment to pull request {} in repository {}", 
                         pullRequestId, repositoryId, throwable);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to post comment")));
            });
    }
    
    @PostMapping("/repositories/{repositoryId}/pull-requests/{pullRequestId}/file-comments")
    public Mono<ResponseEntity<Map<String, String>>> postFileComment(
            @PathVariable String repositoryId,
            @PathVariable int pullRequestId,
            @RequestBody Map<String, Object> commentRequest) {
        
        String comment = (String) commentRequest.get("comment");
        String filePath = (String) commentRequest.get("filePath");
        Integer lineNumber = (Integer) commentRequest.get("lineNumber");
        
        if (comment == null || comment.trim().isEmpty() || 
            filePath == null || filePath.trim().isEmpty() || 
            lineNumber == null) {
            return Mono.just(ResponseEntity.badRequest()
                .body(Map.of("error", "comment, filePath, and lineNumber are required")));
        }
        
        log.info("Posting file comment to pull request: {} in repository: {} at {}:{}", 
                pullRequestId, repositoryId, filePath, lineNumber);
        
        return azureDevOpsService.postPullRequestFileComment(repositoryId, pullRequestId, filePath, lineNumber, comment)
            .then(Mono.just(ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "File comment posted successfully"
            ))))
            .onErrorResume(throwable -> {
                log.error("Error posting file comment to pull request {} in repository {}", 
                         pullRequestId, repositoryId, throwable);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to post file comment")));
            });
    }
} 
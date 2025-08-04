package com.vibecoding.aicodereview.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecoding.aicodereview.model.github.GitHubPullRequest;
import com.vibecoding.aicodereview.model.github.GitHubRepository;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@ConditionalOnProperty(name = "github.enabled", havingValue = "true")
public class GitHubService {
    
    private final GitHub github;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String owner;
    
    public GitHubService(
            @Value("${github.token}") String token,
            @Value("${github.owner}") String owner,
            @Value("${github.api.base-url}") String baseUrl) {
        
        this.owner = owner;
        this.objectMapper = new ObjectMapper();
        
        try {
            if (token != null && !token.isEmpty()) {
                this.github = new GitHubBuilder()
                    .withOAuthToken(token)
                    .build();
                log.info("GitHub client initialized for owner: {}", owner);
            } else {
                this.github = null;
                log.warn("GitHub token not configured");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize GitHub client", e);
        }
        
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "token " + token)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("Accept", "application/vnd.github.v3+json")
            .build();
    }
    
    /**
     * Get all repositories for the configured owner
     */
    public Mono<List<GitHubRepository>> getRepositories() {
        if (github == null) {
            return Mono.just(Collections.emptyList());
        }
        
        return Mono.fromCallable(() -> {
            try {
                GHUser user = github.getUser(owner);
                return user.listRepositories().toList().stream()
                    .map(this::convertToGitHubRepository)
                    .collect(Collectors.toList());
            } catch (IOException e) {
                log.error("Error fetching repositories from GitHub", e);
                return Collections.<GitHubRepository>emptyList();
            }
        });
    }
    
    /**
     * Get a specific repository
     */
    public Mono<GitHubRepository> getRepository(String repositoryName) {
        if (github == null) {
            return Mono.empty();
        }
        
        return Mono.fromCallable(() -> {
            try {
                GHRepository repo = github.getRepository(owner + "/" + repositoryName);
                return convertToGitHubRepository(repo);
            } catch (IOException e) {
                log.error("Error fetching repository {} from GitHub", repositoryName, e);
                return null;
            }
        });
    }
    
    /**
     * Get pull requests for a repository
     */
    public Mono<List<GitHubPullRequest>> getPullRequests(String repositoryName, String state) {
        if (github == null) {
            return Mono.just(Collections.emptyList());
        }
        
        return Mono.fromCallable(() -> {
            try {
                GHRepository repo = github.getRepository(owner + "/" + repositoryName);
                GHIssueState issueState = state != null ? 
                    GHIssueState.valueOf(state.toUpperCase()) : GHIssueState.OPEN;
                
                return repo.getPullRequests(issueState).stream()
                    .map(this::convertToGitHubPullRequest)
                    .collect(Collectors.toList());
            } catch (IOException e) {
                log.error("Error fetching pull requests from GitHub", e);
                return Collections.<GitHubPullRequest>emptyList();
            }
        });
    }
    
    /**
     * Get a specific pull request
     */
    public Mono<GitHubPullRequest> getPullRequest(String repositoryName, int pullRequestNumber) {
        if (github == null) {
            return Mono.empty();
        }
        
        return Mono.fromCallable(() -> {
            try {
                GHRepository repo = github.getRepository(owner + "/" + repositoryName);
                GHPullRequest pr = repo.getPullRequest(pullRequestNumber);
                return convertToGitHubPullRequest(pr);
            } catch (IOException e) {
                log.error("Error fetching pull request {} from GitHub", pullRequestNumber, e);
                return null;
            }
        });
    }
    
    /**
     * Get file content from a repository
     */
    public Mono<String> getFileContent(String repositoryName, String filePath, String ref) {
        if (github == null) {
            return Mono.just("");
        }
        
        return Mono.fromCallable(() -> {
            try {
                GHRepository repo = github.getRepository(owner + "/" + repositoryName);
                GHContent content = repo.getFileContent(filePath, ref);
                return content.getContent();
            } catch (IOException e) {
                log.error("Error fetching file content from GitHub", e);
                return "";
            }
        });
    }
    
    /**
     * Get code scanning alerts for a repository
     */
    public Mono<List<GitHubPullRequest.GitHubCodeScanningAlert>> getCodeScanningAlerts(String repositoryName) {
        String url = String.format("/repos/%s/%s/code-scanning/alerts", owner, repositoryName);
        
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(String.class)
            .map(response -> {
                try {
                    return objectMapper.readValue(response, 
                        new TypeReference<List<GitHubPullRequest.GitHubCodeScanningAlert>>() {});
                } catch (Exception e) {
                    log.error("Error parsing code scanning alerts response", e);
                    return Collections.<GitHubPullRequest.GitHubCodeScanningAlert>emptyList();
                }
            })
            .onErrorReturn(Collections.emptyList());
    }
    
    /**
     * Post a review comment on a pull request
     */
    public Mono<Void> postPullRequestComment(String repositoryName, int pullRequestNumber, String body) {
        if (github == null) {
            return Mono.empty();
        }
        
        return Mono.fromCallable(() -> {
            try {
                GHRepository repo = github.getRepository(owner + "/" + repositoryName);
                GHPullRequest pr = repo.getPullRequest(pullRequestNumber);
                pr.comment(body);
                log.info("Successfully posted comment to PR {}", pullRequestNumber);
                return null;
            } catch (IOException e) {
                log.error("Error posting comment to pull request {}", pullRequestNumber, e);
                throw new RuntimeException(e);
            }
        }).then();
    }
    
    /**
     * Post a review comment on a specific line of a file
     */
    public Mono<Void> postPullRequestFileComment(String repositoryName, int pullRequestNumber, 
                                                String filePath, int lineNumber, String body) {
        if (github == null) {
            return Mono.empty();
        }
        
        return Mono.fromCallable(() -> {
            try {
                GHRepository repo = github.getRepository(owner + "/" + repositoryName);
                GHPullRequest pr = repo.getPullRequest(pullRequestNumber);
                
                // Create a review comment on a specific line
                GHPullRequestReviewBuilder reviewBuilder = pr.createReview();
                reviewBuilder.comment(body, filePath, lineNumber);
                reviewBuilder.create();
                
                log.info("Successfully posted file comment to PR {} at {}:{}", 
                         pullRequestNumber, filePath, lineNumber);
                return null;
            } catch (IOException e) {
                log.error("Error posting file comment to pull request {}", pullRequestNumber, e);
                throw new RuntimeException(e);
            }
        }).then();
    }
    
    /**
     * Create a pull request review with multiple comments
     */
    public Mono<Void> createPullRequestReview(String repositoryName, int pullRequestNumber, 
                                            String body, List<Map<String, Object>> comments) {
        if (github == null) {
            return Mono.empty();
        }
        
        return Mono.fromCallable(() -> {
            try {
                GHRepository repo = github.getRepository(owner + "/" + repositoryName);
                GHPullRequest pr = repo.getPullRequest(pullRequestNumber);
                
                GHPullRequestReviewBuilder reviewBuilder = pr.createReview();
                reviewBuilder.body(body);
                
                // Add individual file comments
                for (Map<String, Object> comment : comments) {
                    String filePath = (String) comment.get("path");
                    Integer line = (Integer) comment.get("line");
                    String commentBody = (String) comment.get("body");
                    
                    if (filePath != null && line != null && commentBody != null) {
                        reviewBuilder.comment(commentBody, filePath, line);
                    }
                }
                
                reviewBuilder.create();
                log.info("Successfully created review for PR {}", pullRequestNumber);
                return null;
            } catch (IOException e) {
                log.error("Error creating review for pull request {}", pullRequestNumber, e);
                throw new RuntimeException(e);
            }
        }).then();
    }
    
    /**
     * Check if GitHub integration is properly configured
     */
    public boolean isConfigured() {
        return github != null && owner != null && !owner.isEmpty();
    }
    
    /**
     * Test GitHub connection
     */
    public Mono<Boolean> testConnection() {
        if (!isConfigured()) {
            return Mono.just(false);
        }
        
        return Mono.fromCallable(() -> {
            try {
                github.getUser(owner);
                return true;
            } catch (IOException e) {
                log.error("GitHub connection test failed", e);
                return false;
            }
        });
    }
    
    // Helper methods for conversion
    private GitHubRepository convertToGitHubRepository(GHRepository ghRepo) {
        GitHubRepository repo = new GitHubRepository();
        repo.setId(ghRepo.getId());
        repo.setName(ghRepo.getName());
        repo.setFullName(ghRepo.getFullName());
        repo.setHtmlUrl(ghRepo.getHtmlUrl().toString());
        repo.setCloneUrl(ghRepo.getCloneUrl());
        repo.setSshUrl(ghRepo.getSshUrl());
        repo.setDescription(ghRepo.getDescription());
        repo.setDefaultBranch(ghRepo.getDefaultBranch());
        repo.setLanguage(ghRepo.getLanguage());
        repo.setSize(ghRepo.getSize());
        repo.setStars(ghRepo.getStargazersCount());
        repo.setForks(ghRepo.getForksCount());
        repo.setOpenIssuesCount(ghRepo.getOpenIssueCount());
        repo.setPrivate(ghRepo.isPrivate());
        
        try {
            if (ghRepo.getCreatedAt() != null) {
                repo.setCreatedAt(ghRepo.getCreatedAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
            }
            if (ghRepo.getUpdatedAt() != null) {
                repo.setUpdatedAt(ghRepo.getUpdatedAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
            }
            if (ghRepo.getPushedAt() != null) {
                repo.setPushedAt(ghRepo.getPushedAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
            }
        } catch (IOException e) {
            log.warn("Error converting dates for repository {}", ghRepo.getName(), e);
        }
        
        return repo;
    }
    
    private GitHubPullRequest convertToGitHubPullRequest(GHPullRequest ghPr) {
        GitHubPullRequest pr = new GitHubPullRequest();
        pr.setId(ghPr.getId());
        pr.setNumber(ghPr.getNumber());
        pr.setTitle(ghPr.getTitle());
        pr.setBody(ghPr.getBody());
        pr.setState(ghPr.getState().toString());
        pr.setHtmlUrl(ghPr.getHtmlUrl().toString());
        pr.setDiffUrl(ghPr.getDiffUrl().toString());
        pr.setPatchUrl(ghPr.getPatchUrl().toString());
        pr.setAdditions(ghPr.getAdditions());
        pr.setDeletions(ghPr.getDeletions());
        pr.setChangedFiles(ghPr.getChangedFiles());
        pr.setMerged(ghPr.isMerged());
        
        try {
            if (ghPr.getCreatedAt() != null) {
                pr.setCreatedAt(ghPr.getCreatedAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
            }
            if (ghPr.getUpdatedAt() != null) {
                pr.setUpdatedAt(ghPr.getUpdatedAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
            }
            if (ghPr.getClosedAt() != null) {
                pr.setClosedAt(ghPr.getClosedAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
            }
            if (ghPr.getMergedAt() != null) {
                pr.setMergedAt(ghPr.getMergedAt().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
            }
        } catch (IOException e) {
            log.warn("Error converting dates for PR {}", ghPr.getNumber(), e);
        }
        
        return pr;
    }
} 
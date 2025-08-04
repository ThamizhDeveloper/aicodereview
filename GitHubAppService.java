package com.vibecoding.aicodereview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecoding.aicodereview.config.GitHubAppConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@Slf4j
@ConditionalOnProperty(name = "github.app.enabled", havingValue = "true")
public class GitHubAppService {
    
    private final GitHubAppConfig appConfig;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    // Cache for installation token
    private String cachedInstallationToken;
    private LocalDateTime tokenExpiresAt;
    
    public GitHubAppService(GitHubAppConfig appConfig, @Value("${github.api.base-url:https://api.github.com}") String baseUrl) {
        this.appConfig = appConfig;
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("Accept", "application/vnd.github.v3+json")
            .build();
    }
    
    /**
     * Get installation access token for GitHub App
     */
    public Mono<String> getInstallationToken() {
        // Return cached token if still valid
        if (cachedInstallationToken != null && tokenExpiresAt != null 
            && LocalDateTime.now().isBefore(tokenExpiresAt.minusMinutes(5))) {
            return Mono.just(cachedInstallationToken);
        }
        
        String jwtToken = appConfig.generateJwtToken();
        if (jwtToken == null) {
            return Mono.error(new RuntimeException("Failed to generate JWT token"));
        }
        
        String installationId = appConfig.getInstallationId();
        if (installationId == null || installationId.isEmpty()) {
            return Mono.error(new RuntimeException("Installation ID not configured"));
        }
        
        return webClient.post()
            .uri("/app/installations/{installation_id}/access_tokens", installationId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtToken)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseInstallationTokenResponse)
            .doOnNext(token -> {
                cachedInstallationToken = token;
                tokenExpiresAt = LocalDateTime.now().plusHours(1); // GitHub tokens expire in 1 hour
                log.info("Successfully obtained installation token for GitHub App");
            })
            .onErrorResume(throwable -> {
                log.error("Failed to get installation token", throwable);
                return Mono.error(new RuntimeException("Failed to get installation token", throwable));
            });
    }
    
    /**
     * Get authenticated WebClient with installation token
     */
    public Mono<WebClient> getAuthenticatedClient() {
        return getInstallationToken()
            .map(token -> webClient.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "token " + token)
                .build());
    }
    
    /**
     * Create a check run for a commit
     */
    public Mono<Void> createCheckRun(String owner, String repository, String commitSha, 
                                   String name, String status, String conclusion, String summary) {
        return getAuthenticatedClient()
            .flatMap(client -> {
                Map<String, Object> checkRun = Map.of(
                    "name", name,
                    "head_sha", commitSha,
                    "status", status,
                    "conclusion", conclusion != null ? conclusion : "",
                    "output", Map.of(
                        "title", "Code Review Results",
                        "summary", summary
                    )
                );
                
                return client.post()
                    .uri("/repos/{owner}/{repo}/check-runs", owner, repository)
                    .bodyValue(checkRun)
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnSuccess(response -> log.info("Check run created successfully for commit {}", commitSha))
                    .then();
            });
    }
    
    /**
     * Update a check run
     */
    public Mono<Void> updateCheckRun(String owner, String repository, long checkRunId,
                                   String status, String conclusion, String summary) {
        return getAuthenticatedClient()
            .flatMap(client -> {
                Map<String, Object> updateData = Map.of(
                    "status", status,
                    "conclusion", conclusion != null ? conclusion : "",
                    "output", Map.of(
                        "title", "Code Review Results",
                        "summary", summary
                    )
                );
                
                return client.patch()
                    .uri("/repos/{owner}/{repo}/check-runs/{check_run_id}", owner, repository, checkRunId)
                    .bodyValue(updateData)
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnSuccess(response -> log.info("Check run {} updated successfully", checkRunId))
                    .then();
            });
    }
    
    /**
     * Get repository installation information
     */
    public Mono<Map<String, Object>> getRepositoryInstallation(String owner, String repository) {
        return getAuthenticatedClient()
            .flatMap(client -> client.get()
                .uri("/repos/{owner}/{repo}/installation", owner, repository)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseJsonResponse)
                .onErrorReturn(Map.of("error", "Repository not accessible or app not installed"))
            );
    }
    
    /**
     * List repositories accessible to the installation
     */
    public Mono<Map<String, Object>> getAccessibleRepositories() {
        return getAuthenticatedClient()
            .flatMap(client -> client.get()
                .uri("/installation/repositories")
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseJsonResponse)
                .onErrorReturn(Map.of("error", "Failed to fetch accessible repositories"))
            );
    }
    
    /**
     * Create a deployment status
     */
    public Mono<Void> createDeploymentStatus(String owner, String repository, long deploymentId,
                                           String state, String description, String environment) {
        return getAuthenticatedClient()
            .flatMap(client -> {
                Map<String, Object> status = Map.of(
                    "state", state,
                    "description", description,
                    "environment", environment
                );
                
                return client.post()
                    .uri("/repos/{owner}/{repo}/deployments/{deployment_id}/statuses", 
                         owner, repository, deploymentId)
                    .bodyValue(status)
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnSuccess(response -> log.info("Deployment status created for deployment {}", deploymentId))
                    .then();
            });
    }
    
    /**
     * Check if GitHub App is properly configured and authenticated
     */
    public Mono<Boolean> isAuthenticated() {
        if (!appConfig.isConfigured()) {
            return Mono.just(false);
        }
        
        return getInstallationToken()
            .map(token -> token != null && !token.isEmpty())
            .onErrorReturn(false);
    }
    
    /**
     * Get GitHub App information
     */
    public Mono<Map<String, Object>> getAppInfo() {
        String jwtToken = appConfig.generateJwtToken();
        if (jwtToken == null) {
            return Mono.just(Map.of("error", "Failed to generate JWT token"));
        }
        
        return webClient.get()
            .uri("/app")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtToken)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseJsonResponse)
            .onErrorReturn(Map.of("error", "Failed to fetch app information"));
    }
    
    // Helper methods
    private String parseInstallationTokenResponse(String response) {
        try {
            JsonNode jsonNode = objectMapper.readTree(response);
            return jsonNode.path("token").asText();
        } catch (Exception e) {
            log.error("Failed to parse installation token response", e);
            throw new RuntimeException("Failed to parse installation token response", e);
        }
    }
    
    private Map<String, Object> parseJsonResponse(String response) {
        try {
            return objectMapper.readValue(response, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse JSON response", e);
            return Map.of("error", "Failed to parse response");
        }
    }
    
    public boolean isConfigured() {
        return appConfig.isConfigured();
    }
} 
package com.vibecoding.aicodereview.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecoding.aicodereview.model.azure.AzurePullRequest;
import com.vibecoding.aicodereview.model.azure.AzureRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@ConditionalOnProperty(name = "azure.devops.enabled", havingValue = "true")
public class AzureDevOpsService {
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String organization;
    private final String project;
    private final String personalAccessToken;
    private final String baseUrl;
    
    public AzureDevOpsService(
            @Value("${azure.devops.organization}") String organization,
            @Value("${azure.devops.project}") String project,
            @Value("${azure.devops.personal-access-token}") String personalAccessToken,
            @Value("${azure.devops.api.base-url}") String baseUrl) {
        
        this.organization = organization;
        this.project = project;
        this.personalAccessToken = personalAccessToken;
        this.baseUrl = baseUrl;
        this.objectMapper = new ObjectMapper();
        
        String auth = ":" + personalAccessToken;
        String encodedAuth = Base64.encodeBase64String(auth.getBytes(StandardCharsets.UTF_8));
        
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("Accept", "application/json;api-version=7.1")
            .build();
    }
    
    /**
     * Get all repositories in the project
     */
    public Mono<List<AzureRepository>> getRepositories() {
        String url = String.format("/%s/%s/_apis/git/repositories", organization, project);
        
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(String.class)
            .map(response -> {
                try {
                    JsonNode jsonNode = objectMapper.readTree(response);
                    JsonNode valueNode = jsonNode.path("value");
                    return objectMapper.convertValue(valueNode, new TypeReference<List<AzureRepository>>() {});
                } catch (Exception e) {
                    log.error("Error parsing repositories response", e);
                    return Collections.emptyList();
                }
            })
            .onErrorResume(throwable -> {
                log.error("Error fetching repositories from Azure DevOps", throwable);
                return Mono.just(Collections.emptyList());
            });
    }
    
    /**
     * Get a specific repository by ID
     */
    public Mono<AzureRepository> getRepository(String repositoryId) {
        String url = String.format("/%s/%s/_apis/git/repositories/%s", organization, project, repositoryId);
        
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(AzureRepository.class)
            .onErrorResume(throwable -> {
                log.error("Error fetching repository {} from Azure DevOps", repositoryId, throwable);
                return Mono.empty();
            });
    }
    
    /**
     * Get pull requests for a repository
     */
    public Mono<List<AzurePullRequest>> getPullRequests(String repositoryId, String status) {
        String url = String.format("/%s/%s/_apis/git/repositories/%s/pullrequests", 
                                 organization, project, repositoryId);
        
        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path(url)
                .queryParamIfPresent("searchCriteria.status", java.util.Optional.ofNullable(status))
                .build())
            .retrieve()
            .bodyToMono(String.class)
            .map(response -> {
                try {
                    JsonNode jsonNode = objectMapper.readTree(response);
                    JsonNode valueNode = jsonNode.path("value");
                    return objectMapper.convertValue(valueNode, new TypeReference<List<AzurePullRequest>>() {});
                } catch (Exception e) {
                    log.error("Error parsing pull requests response", e);
                    return Collections.emptyList();
                }
            })
            .onErrorResume(throwable -> {
                log.error("Error fetching pull requests from Azure DevOps", throwable);
                return Mono.just(Collections.emptyList());
            });
    }
    
    /**
     * Get a specific pull request
     */
    public Mono<AzurePullRequest> getPullRequest(String repositoryId, int pullRequestId) {
        String url = String.format("/%s/%s/_apis/git/repositories/%s/pullrequests/%d", 
                                 organization, project, repositoryId, pullRequestId);
        
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(AzurePullRequest.class)
            .onErrorResume(throwable -> {
                log.error("Error fetching pull request {} from Azure DevOps", pullRequestId, throwable);
                return Mono.empty();
            });
    }
    
    /**
     * Get file changes (diffs) for a pull request
     */
    public Mono<List<AzurePullRequest.AzureFileChange>> getPullRequestChanges(String repositoryId, int pullRequestId) {
        String url = String.format("/%s/%s/_apis/git/repositories/%s/pullrequests/%d/iterations/1/changes", 
                                 organization, project, repositoryId, pullRequestId);
        
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(String.class)
            .map(response -> {
                try {
                    JsonNode jsonNode = objectMapper.readTree(response);
                    JsonNode changesNode = jsonNode.path("changes");
                    return objectMapper.convertValue(changesNode, new TypeReference<List<AzurePullRequest.AzureFileChange>>() {});
                } catch (Exception e) {
                    log.error("Error parsing pull request changes response", e);
                    return Collections.emptyList();
                }
            })
            .onErrorResume(throwable -> {
                log.error("Error fetching pull request changes from Azure DevOps", throwable);
                return Mono.just(Collections.emptyList());
            });
    }
    
    /**
     * Get file content from a repository
     */
    public Mono<String> getFileContent(String repositoryId, String filePath, String commitId) {
        String url = String.format("/%s/%s/_apis/git/repositories/%s/items", 
                                 organization, project, repositoryId);
        
        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path(url)
                .queryParam("path", filePath)
                .queryParam("versionDescriptor.versionType", "commit")
                .queryParam("versionDescriptor.version", commitId)
                .queryParam("includeContent", true)
                .build())
            .retrieve()
            .bodyToMono(String.class)
            .map(response -> {
                try {
                    JsonNode jsonNode = objectMapper.readTree(response);
                    return jsonNode.path("content").asText();
                } catch (Exception e) {
                    log.error("Error parsing file content response", e);
                    return "";
                }
            })
            .onErrorResume(throwable -> {
                log.error("Error fetching file content from Azure DevOps", throwable);
                return Mono.just("");
            });
    }
    
    /**
     * Post a comment to a pull request
     */
    public Mono<Void> postPullRequestComment(String repositoryId, int pullRequestId, String comment) {
        String url = String.format("/%s/%s/_apis/git/repositories/%s/pullrequests/%d/threads", 
                                 organization, project, repositoryId, pullRequestId);
        
        Map<String, Object> commentData = Map.of(
            "comments", List.of(
                Map.of(
                    "content", comment,
                    "commentType", "text"
                )
            ),
            "status", "active"
        );
        
        return webClient.post()
            .uri(url)
            .bodyValue(commentData)
            .retrieve()
            .bodyToMono(Void.class)
            .doOnSuccess(result -> log.info("Successfully posted comment to PR {}", pullRequestId))
            .onErrorResume(throwable -> {
                log.error("Error posting comment to pull request {}", pullRequestId, throwable);
                return Mono.empty();
            });
    }
    
    /**
     * Post a review comment with file context
     */
    public Mono<Void> postPullRequestFileComment(String repositoryId, int pullRequestId, 
                                                String filePath, int lineNumber, String comment) {
        String url = String.format("/%s/%s/_apis/git/repositories/%s/pullrequests/%d/threads", 
                                 organization, project, repositoryId, pullRequestId);
        
        Map<String, Object> commentData = Map.of(
            "comments", List.of(
                Map.of(
                    "content", comment,
                    "commentType", "text"
                )
            ),
            "status", "active",
            "threadContext", Map.of(
                "filePath", filePath,
                "rightFileStart", Map.of("line", lineNumber, "offset", 1),
                "rightFileEnd", Map.of("line", lineNumber, "offset", 1)
            )
        );
        
        return webClient.post()
            .uri(url)
            .bodyValue(commentData)
            .retrieve()
            .bodyToMono(Void.class)
            .doOnSuccess(result -> log.info("Successfully posted file comment to PR {} at {}:{}", 
                                          pullRequestId, filePath, lineNumber))
            .onErrorResume(throwable -> {
                log.error("Error posting file comment to pull request {}", pullRequestId, throwable);
                return Mono.empty();
            });
    }
    
    /**
     * Check if Azure DevOps integration is properly configured
     */
    public boolean isConfigured() {
        return organization != null && !organization.isEmpty() &&
               project != null && !project.isEmpty() &&
               personalAccessToken != null && !personalAccessToken.isEmpty();
    }
    
    /**
     * Test Azure DevOps connection
     */
    public Mono<Boolean> testConnection() {
        if (!isConfigured()) {
            return Mono.just(false);
        }
        
        return getRepositories()
            .map(repos -> true)
            .onErrorReturn(false);
    }
} 
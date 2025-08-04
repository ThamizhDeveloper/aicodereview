package com.vibecoding.aicodereview.service;

import com.vibecoding.aicodereview.model.AzureCodeReview;
import com.vibecoding.aicodereview.model.CodeReview;
import com.vibecoding.aicodereview.model.ReviewComment;
import com.vibecoding.aicodereview.model.azure.AzurePullRequest;
import com.vibecoding.aicodereview.model.azure.AzureWebhookEvent;
import com.vibecoding.aicodereview.repository.CodeReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "azure.devops.enabled", havingValue = "true")
public class AzurePullRequestReviewService {
    
    private final CodeReviewRepository codeReviewRepository;
    private final EnhancedAiReviewService enhancedAiReviewService;
    private final AzureDevOpsService azureDevOpsService;
    
    @Transactional
    public Mono<AzureCodeReview> processPullRequestWebhook(AzureWebhookEvent webhookEvent) {
        log.info("Processing webhook for PR {}", webhookEvent.getResource().getPullRequestId());
        
        AzurePullRequest pullRequest = webhookEvent.getResource();
        String repositoryId = pullRequest.getRepository().getId();
        int pullRequestId = pullRequest.getPullRequestId();
        
        return azureDevOpsService.getPullRequestChanges(repositoryId, pullRequestId)
            .flatMap(changes -> {
                if (changes.isEmpty()) {
                    log.info("No code changes found for PR {}", pullRequestId);
                    return Mono.empty();
                }
                
                // Filter for code files only (exclude docs, configs, etc.)
                List<AzurePullRequest.AzureFileChange> codeFiles = changes.stream()
                    .filter(this::isCodeFile)
                    .filter(change -> !"delete".equalsIgnoreCase(change.getChangeType()))
                    .collect(Collectors.toList());
                
                if (codeFiles.isEmpty()) {
                    log.info("No code files to review in PR {}", pullRequestId);
                    return Mono.empty();
                }
                
                log.info("Found {} code files to review in PR {}", codeFiles.size(), pullRequestId);
                
                // Process each code file
                return processCodeFiles(pullRequest, codeFiles, webhookEvent.getId());
            })
            .onErrorResume(throwable -> {
                log.error("Error processing webhook for PR {}", pullRequestId, throwable);
                return Mono.empty();
            });
    }
    
    private Mono<AzureCodeReview> processCodeFiles(AzurePullRequest pullRequest, 
                                                  List<AzurePullRequest.AzureFileChange> codeFiles,
                                                  String webhookEventId) {
        
        // For now, process the first file. In production, you might want to:
        // 1. Process all files and aggregate results
        // 2. Process files in parallel
        // 3. Prioritize certain file types
        
        AzurePullRequest.AzureFileChange firstFile = codeFiles.get(0);
        String repositoryId = pullRequest.getRepository().getId();
        String commitId = pullRequest.getLastMergeSourceCommit().getCommitId();
        
        return azureDevOpsService.getFileContent(repositoryId, firstFile.getPath(), commitId)
            .flatMap(fileContent -> {
                if (fileContent.isEmpty()) {
                    log.warn("Could not retrieve content for file {}", firstFile.getPath());
                    return Mono.empty();
                }
                
                return analyzeCodeAndCreateReview(pullRequest, firstFile, fileContent, webhookEventId);
            });
    }
    
    private Mono<AzureCodeReview> analyzeCodeAndCreateReview(AzurePullRequest pullRequest,
                                                           AzurePullRequest.AzureFileChange file,
                                                           String fileContent,
                                                           String webhookEventId) {
        
        // Create initial Azure Code Review entity
        AzureCodeReview azureReview = createAzureCodeReview(pullRequest, file, webhookEventId);
        azureReview.setOriginalCode(fileContent);
        azureReview.setStatus(CodeReview.ReviewStatus.PENDING);
        
        AzureCodeReview savedReview = codeReviewRepository.save(azureReview);
        
        // Update status to IN_PROGRESS
        savedReview.setStatus(CodeReview.ReviewStatus.IN_PROGRESS);
        codeReviewRepository.save(savedReview);
        
        // Analyze code with AI
        String language = detectLanguage(file.getPath());
        String reviewFocus = "general";
        
        return enhancedAiReviewService.analyzeCode(fileContent, file.getPath(), language, reviewFocus)
            .map(aiResult -> {
                // Update the review with AI results
                savedReview.setReviewSummary(aiResult.getSummary());
                savedReview.setQualityScore(aiResult.getQualityScore());
                savedReview.setStatus(CodeReview.ReviewStatus.COMPLETED);
                savedReview.setCompletedAt(LocalDateTime.now());
                
                // Set the code review reference for each comment
                List<ReviewComment> comments = aiResult.getComments();
                comments.forEach(comment -> comment.setCodeReview(savedReview));
                savedReview.setComments(comments);
                
                try {
                    AzureCodeReview finalReview = codeReviewRepository.save(savedReview);
                    
                    // Post results back to Azure DevOps
                    postReviewToAzureDevOps(finalReview)
                        .doOnSuccess(result -> {
                            finalReview.setPostedToAzure(true);
                            codeReviewRepository.save(finalReview);
                        })
                        .subscribe();
                    
                    return finalReview;
                } catch (Exception e) {
                    log.error("Error saving Azure code review results", e);
                    savedReview.setStatus(CodeReview.ReviewStatus.FAILED);
                    savedReview.setReviewSummary("Failed to save review results: " + e.getMessage());
                    return codeReviewRepository.save(savedReview);
                }
            })
            .onErrorResume(throwable -> {
                log.error("Error during AI code analysis", throwable);
                savedReview.setStatus(CodeReview.ReviewStatus.FAILED);
                savedReview.setReviewSummary("Failed to analyze code: " + throwable.getMessage());
                savedReview.setCompletedAt(LocalDateTime.now());
                AzureCodeReview failedReview = codeReviewRepository.save(savedReview);
                return Mono.just(failedReview);
            });
    }
    
    private Mono<Void> postReviewToAzureDevOps(AzureCodeReview review) {
        if (review.getRepositoryId() == null || review.getPullRequestId() == null) {
            log.warn("Cannot post review to Azure DevOps - missing repository or PR information");
            return Mono.empty();
        }
        
        // Post a general comment with the review summary
        String summaryComment = formatReviewSummary(review);
        
        return azureDevOpsService.postPullRequestComment(
                review.getRepositoryId(),
                review.getPullRequestId(),
                summaryComment
            )
            .then(postFileComments(review));
    }
    
    private Mono<Void> postFileComments(AzureCodeReview review) {
        if (review.getComments() == null || review.getComments().isEmpty()) {
            return Mono.empty();
        }
        
        // Post file-specific comments
        return Mono.fromRunnable(() -> {
            review.getComments().forEach(comment -> {
                if (comment.getLineNumber() != null) {
                    String fileComment = formatFileComment(comment);
                    azureDevOpsService.postPullRequestFileComment(
                            review.getRepositoryId(),
                            review.getPullRequestId(),
                            review.getFileName(),
                            comment.getLineNumber(),
                            fileComment
                        )
                        .doOnError(error -> log.error("Failed to post file comment", error))
                        .subscribe();
                }
            });
        });
    }
    
    private String formatReviewSummary(AzureCodeReview review) {
        StringBuilder summary = new StringBuilder();
        summary.append("## 🤖 AI Code Review Results\n\n");
        summary.append(String.format("**File:** `%s`\n", review.getFileName()));
        summary.append(String.format("**Quality Score:** %s/10\n\n", 
                                    review.getQualityScore() != null ? review.getQualityScore() : "N/A"));
        
        if (review.getReviewSummary() != null) {
            summary.append("### Summary\n");
            summary.append(review.getReviewSummary()).append("\n\n");
        }
        
        if (review.getComments() != null && !review.getComments().isEmpty()) {
            summary.append(String.format("### Issues Found (%d)\n", review.getComments().size()));
            review.getComments().forEach(comment -> {
                summary.append(String.format("- **%s** (%s): %s\n", 
                                            comment.getType(), 
                                            comment.getSeverity(),
                                            comment.getComment()));
            });
        }
        
        summary.append("\n---\n");
        summary.append("*Generated by AI Code Review Tool*");
        
        return summary.toString();
    }
    
    private String formatFileComment(ReviewComment comment) {
        StringBuilder fileComment = new StringBuilder();
        fileComment.append(String.format("**%s** - %s\n\n", comment.getType(), comment.getSeverity()));
        fileComment.append(comment.getComment());
        
        if (comment.getSuggestion() != null && !comment.getSuggestion().isEmpty()) {
            fileComment.append("\n\n**💡 Suggestion:**\n");
            fileComment.append(comment.getSuggestion());
        }
        
        return fileComment.toString();
    }
    
    private AzureCodeReview createAzureCodeReview(AzurePullRequest pullRequest, 
                                                 AzurePullRequest.AzureFileChange file,
                                                 String webhookEventId) {
        AzureCodeReview review = new AzureCodeReview();
        
        // Set basic code review fields
        review.setFileName(file.getPath());
        review.setLanguage(detectLanguage(file.getPath()));
        
        // Set Azure-specific fields
        review.setAzureOrganization(pullRequest.getRepository().getProject().getName());
        review.setAzureProject(pullRequest.getRepository().getProject().getName());
        review.setRepositoryId(pullRequest.getRepository().getId());
        review.setRepositoryName(pullRequest.getRepository().getName());
        review.setPullRequestId(pullRequest.getPullRequestId());
        review.setPullRequestTitle(pullRequest.getTitle());
        review.setSourceBranch(pullRequest.getSourceRefName());
        review.setTargetBranch(pullRequest.getTargetRefName());
        review.setAuthorName(pullRequest.getCreatedBy().getDisplayName());
        review.setAuthorEmail(pullRequest.getCreatedBy().getUniqueName());
        review.setCommitId(pullRequest.getLastMergeSourceCommit().getCommitId());
        review.setAzurePrUrl(pullRequest.getUrl());
        review.setWebhookEventId(webhookEventId);
        review.setReviewSource(AzureCodeReview.ReviewSource.AZURE_WEBHOOK);
        
        return review;
    }
    
    private boolean isCodeFile(AzurePullRequest.AzureFileChange file) {
        String path = file.getPath().toLowerCase();
        
        // Include common code file extensions
        return path.endsWith(".java") || path.endsWith(".js") || path.endsWith(".ts") ||
               path.endsWith(".py") || path.endsWith(".cs") || path.endsWith(".cpp") ||
               path.endsWith(".c") || path.endsWith(".h") || path.endsWith(".php") ||
               path.endsWith(".go") || path.endsWith(".rs") || path.endsWith(".swift") ||
               path.endsWith(".kt") || path.endsWith(".scala") || path.endsWith(".rb") ||
               path.endsWith(".vue") || path.endsWith(".jsx") || path.endsWith(".tsx");
    }
    
    private String detectLanguage(String filePath) {
        String extension = filePath.substring(filePath.lastIndexOf('.') + 1).toLowerCase();
        
        return switch (extension) {
            case "java" -> "java";
            case "js", "jsx" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "py" -> "python";
            case "cs" -> "csharp";
            case "cpp", "cc", "cxx" -> "cpp";
            case "c", "h" -> "c";
            case "php" -> "php";
            case "go" -> "go";
            case "rs" -> "rust";
            case "swift" -> "swift";
            case "kt" -> "kotlin";
            case "scala" -> "scala";
            case "rb" -> "ruby";
            case "vue" -> "vue";
            default -> "text";
        };
    }
} 
package com.vibecoding.aicodereview.service;

import com.vibecoding.aicodereview.model.AzureCodeReview;
import com.vibecoding.aicodereview.model.CodeReview;
import com.vibecoding.aicodereview.model.ReviewComment;
import com.vibecoding.aicodereview.model.github.GitHubPullRequest;
import com.vibecoding.aicodereview.repository.CodeReviewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@ConditionalOnProperty(name = "github.enabled", havingValue = "true")
public class GitHubPullRequestReviewService {
    
    private final GitHubService gitHubService;
    private final EnhancedAiReviewService enhancedAiReviewService;
    private final CodeReviewRepository codeReviewRepository;
    
    public GitHubPullRequestReviewService(
            @Autowired(required = false) GitHubService gitHubService,
            EnhancedAiReviewService enhancedAiReviewService,
            CodeReviewRepository codeReviewRepository) {
        this.gitHubService = gitHubService;
        this.enhancedAiReviewService = enhancedAiReviewService;
        this.codeReviewRepository = codeReviewRepository;
    }
    
    /**
     * Process a GitHub pull request for review
     */
    public Mono<CodeReview> processGitHubPullRequest(String repositoryName, int pullRequestNumber, String webhookEventId) {
        log.info("Starting GitHub PR review process for repo: {}, PR: {}", repositoryName, pullRequestNumber);
        
        if (gitHubService == null) {
            log.error("GitHub service not available");
            return Mono.empty();
        }
        
        return gitHubService.getPullRequest(repositoryName, pullRequestNumber)
            .flatMap(pullRequest -> {
                log.info("Retrieved PR details: {} - {}", pullRequest.getNumber(), pullRequest.getTitle());
                return processGitHubPullRequestFiles(repositoryName, pullRequest, webhookEventId);
            })
            .doOnSuccess(review -> {
                if (review != null) {
                    log.info("Successfully completed GitHub PR review for PR {}", pullRequestNumber);
                }
            })
            .doOnError(error -> {
                log.error("Error processing GitHub PR review for PR {}", pullRequestNumber, error);
            });
    }
    
    /**
     * Process the files in a GitHub pull request
     */
    private Mono<CodeReview> processGitHubPullRequestFiles(String repositoryName, GitHubPullRequest pullRequest, String webhookEventId) {
        if (pullRequest.getFiles() == null || pullRequest.getFiles().isEmpty()) {
            log.info("No files to review in PR {}", pullRequest.getNumber());
            return Mono.empty();
        }
        
        // For now, process the first significant file (can be extended to process all files)
        GitHubPullRequest.GitHubFileChange fileToReview = findMostSignificantFile(pullRequest.getFiles());
        
        if (fileToReview == null) {
            log.info("No suitable files found for review in PR {}", pullRequest.getNumber());
            return Mono.empty();
        }
        
        return gitHubService.getFileContent(repositoryName, fileToReview.getFilename(), pullRequest.getHead().getSha())
            .flatMap(fileContent -> {
                if (fileContent.isEmpty()) {
                    log.warn("Empty file content for {}", fileToReview.getFilename());
                    return Mono.empty();
                }
                
                return analyzeFileContent(fileContent, fileToReview, pullRequest, repositoryName, webhookEventId);
            });
    }
    
    /**
     * Analyze file content using AI services
     */
    private Mono<CodeReview> analyzeFileContent(String fileContent, GitHubPullRequest.GitHubFileChange file, 
                                               GitHubPullRequest pullRequest, String repositoryName, String webhookEventId) {
        String language = detectLanguage(file.getFilename());
        String reviewFocus = determineReviewFocus(file, pullRequest);
        
        log.info("Analyzing file: {} (language: {}, focus: {})", file.getFilename(), language, reviewFocus);
        
        return enhancedAiReviewService.analyzeCode(fileContent, file.getFilename(), language, reviewFocus)
            .flatMap(aiResult -> {
                // Create and save the review
                CodeReview review = createGitHubCodeReview(aiResult, file, pullRequest, repositoryName, webhookEventId);
                CodeReview savedReview = codeReviewRepository.save(review);
                
                // Post review comments to GitHub
                return postReviewToGitHub(repositoryName, pullRequest.getNumber(), savedReview)
                    .then(Mono.just(savedReview));
            });
    }
    
    /**
     * Post review comments to GitHub PR
     */
    private Mono<Void> postReviewToGitHub(String repositoryName, int pullRequestNumber, CodeReview review) {
        if (review.getComments() == null || review.getComments().isEmpty()) {
            // Still post a general review summary
            String summaryComment = formatReviewSummary(review);
            return gitHubService.postPullRequestComment(repositoryName, pullRequestNumber, summaryComment);
        }
        
        // Prepare review with multiple comments
        List<Map<String, Object>> commentsList = new ArrayList<>();
        
        for (ReviewComment comment : review.getComments()) {
            if (comment.getLineNumber() != null && comment.getLineNumber() > 0) {
                commentsList.add(Map.of(
                    "path", review.getFileName(),
                    "line", comment.getLineNumber(),
                    "body", formatCommentForGitHub(comment)
                ));
            }
        }
        
        String reviewBody = formatReviewSummary(review);
        
        if (!commentsList.isEmpty()) {
            return gitHubService.createPullRequestReview(repositoryName, pullRequestNumber, reviewBody, commentsList);
        } else {
            return gitHubService.postPullRequestComment(repositoryName, pullRequestNumber, reviewBody);
        }
    }
    
    private CodeReview createGitHubCodeReview(AiReviewService.AiReviewResult aiResult, 
                                            GitHubPullRequest.GitHubFileChange file,
                                            GitHubPullRequest pullRequest, 
                                            String repositoryName,
                                            String webhookEventId) {
        
        // Create an extended code review for GitHub
        GitHubCodeReview review = new GitHubCodeReview();
        
        // Basic CodeReview fields
        review.setFileName(file.getFilename());
        review.setOriginalCode(""); // We don't store the full code content
        review.setReviewSummary(aiResult.getSummary());
        review.setStatus(CodeReview.ReviewStatus.COMPLETED);
        review.setCreatedAt(LocalDateTime.now());
        review.setCompletedAt(LocalDateTime.now());
        review.setQualityScore(aiResult.getQualityScore());
        review.setLanguage(detectLanguage(file.getFilename()));
        
        // Set comments
        if (aiResult.getComments() != null) {
            for (ReviewComment comment : aiResult.getComments()) {
                comment.setCodeReview(review);
            }
            review.setComments(aiResult.getComments());
        }
        
        // GitHub-specific fields
        review.setRepositoryName(repositoryName);
        review.setPullRequestId(pullRequest.getNumber());
        review.setPullRequestTitle(pullRequest.getTitle());
        review.setSourceBranch(pullRequest.getHead().getRef());
        review.setTargetBranch(pullRequest.getBase().getRef());
        review.setAuthorName(pullRequest.getUser().getLogin());
        review.setCommitId(pullRequest.getHead().getSha());
        review.setGitHubPrUrl(pullRequest.getHtmlUrl());
        review.setWebhookEventId(webhookEventId);
        review.setReviewSource(GitHubCodeReview.ReviewSource.GITHUB_WEBHOOK);
        review.setPostedToGitHub(false); // Will be set to true after posting
        
        return review;
    }
    
    private GitHubPullRequest.GitHubFileChange findMostSignificantFile(List<GitHubPullRequest.GitHubFileChange> files) {
        // Prioritize code files over configuration files
        List<String> codeExtensions = List.of(".java", ".js", ".ts", ".py", ".cs", ".cpp", ".go", ".rs", ".php");
        
        // First, look for code files
        for (GitHubPullRequest.GitHubFileChange file : files) {
            String filename = file.getFilename().toLowerCase();
            for (String ext : codeExtensions) {
                if (filename.endsWith(ext) && file.getChanges() > 10) {
                    return file;
                }
            }
        }
        
        // Fallback to any file with significant changes
        return files.stream()
            .filter(file -> file.getChanges() > 5)
            .findFirst()
            .orElse(!files.isEmpty() ? files.get(0) : null);
    }
    
    private String detectLanguage(String filename) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (extension) {
            case "java" -> "java";
            case "js", "jsx" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "py" -> "python";
            case "cs" -> "csharp";
            case "cpp", "cc", "cxx", "c" -> "cpp";
            case "go" -> "go";
            case "rs" -> "rust";
            case "php" -> "php";
            case "rb" -> "ruby";
            case "swift" -> "swift";
            case "kt" -> "kotlin";
            case "scala" -> "scala";
            default -> "text";
        };
    }
    
    private String determineReviewFocus(GitHubPullRequest.GitHubFileChange file, GitHubPullRequest pullRequest) {
        // Determine review focus based on file changes and PR context
        if (file.getAdditions() > file.getDeletions() * 2) {
            return "new feature implementation";
        } else if (file.getDeletions() > file.getAdditions()) {
            return "code refactoring and cleanup";
        } else if (file.getFilename().toLowerCase().contains("test")) {
            return "test coverage and quality";
        } else if (file.getFilename().toLowerCase().contains("security") || 
                   pullRequest.getTitle().toLowerCase().contains("security")) {
            return "security review";
        } else {
            return "general code quality";
        }
    }
    
    private String formatReviewSummary(CodeReview review) {
        StringBuilder summary = new StringBuilder();
        summary.append("## 🤖 GitHub Copilot Code Review\n\n");
        summary.append("**File:** `").append(review.getFileName()).append("`\n");
        summary.append("**Quality Score:** ").append(String.format("%.1f/10", review.getQualityScore())).append("\n\n");
        
        if (review.getReviewSummary() != null) {
            summary.append("### Summary\n");
            summary.append(review.getReviewSummary()).append("\n\n");
        }
        
        if (review.getComments() != null && !review.getComments().isEmpty()) {
            summary.append("### Review Comments\n");
            summary.append("Found ").append(review.getComments().size()).append(" suggestions for improvement.\n\n");
            
            long criticalIssues = review.getComments().stream()
                .filter(c -> c.getSeverity() == ReviewComment.Severity.CRITICAL).count();
            long highIssues = review.getComments().stream()
                .filter(c -> c.getSeverity() == ReviewComment.Severity.HIGH).count();
            
            if (criticalIssues > 0) {
                summary.append("⚠️ **").append(criticalIssues).append(" critical issue(s)** require immediate attention\n");
            }
            if (highIssues > 0) {
                summary.append("🔍 **").append(highIssues).append(" high priority issue(s)** should be addressed\n");
            }
        } else {
            summary.append("✅ No significant issues found!\n");
        }
        
        summary.append("\n---\n");
        summary.append("*Review powered by ").append(enhancedAiReviewService.getActiveService()).append("*");
        
        return summary.toString();
    }
    
    private String formatCommentForGitHub(ReviewComment comment) {
        StringBuilder formatted = new StringBuilder();
        
        // Add emoji based on severity
        String emoji = switch (comment.getSeverity()) {
            case CRITICAL -> "🚨";
            case HIGH -> "⚠️";
            case MEDIUM -> "🔍";
            case LOW -> "💡";
        };
        
        formatted.append(emoji).append(" **").append(comment.getType()).append("** (")
                .append(comment.getSeverity()).append(")\n\n");
        formatted.append(comment.getComment()).append("\n");
        
        if (comment.getSuggestion() != null && !comment.getSuggestion().isEmpty()) {
            formatted.append("\n**Suggestion:** ").append(comment.getSuggestion());
        }
        
        return formatted.toString();
    }
    
    // Extended code review entity for GitHub
    public static class GitHubCodeReview extends AzureCodeReview {
        private String repositoryName;
        private String gitHubPrUrl;
        private boolean postedToGitHub = false;
        
        // Getters and setters
        public String getRepositoryName() { return repositoryName; }
        public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
        
        public String getGitHubPrUrl() { return gitHubPrUrl; }
        public void setGitHubPrUrl(String gitHubPrUrl) { this.gitHubPrUrl = gitHubPrUrl; }
        
        public boolean isPostedToGitHub() { return postedToGitHub; }
        public void setPostedToGitHub(boolean postedToGitHub) { this.postedToGitHub = postedToGitHub; }
    }
} 
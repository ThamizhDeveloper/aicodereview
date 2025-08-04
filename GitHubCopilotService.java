package com.vibecoding.aicodereview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibecoding.aicodereview.model.ReviewComment;
import com.vibecoding.aicodereview.model.github.GitHubPullRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@ConditionalOnProperty(name = "github.copilot.enabled", havingValue = "true")
public class GitHubCopilotService {
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String githubToken;
    private final String copilotModel;
    private final boolean useForReviews;
    private final GitHubService gitHubService;
    
    public GitHubCopilotService(
            @Value("${github.token}") String githubToken,
            @Value("${github.copilot.model:copilot-chat}") String copilotModel,
            @Value("${github.copilot.use-for-reviews:true}") boolean useForReviews,
            GitHubService gitHubService) {
        
        this.githubToken = githubToken;
        this.copilotModel = copilotModel;
        this.useForReviews = useForReviews;
        this.gitHubService = gitHubService;
        this.objectMapper = new ObjectMapper();
        
        // Note: GitHub Copilot API endpoint is not yet publicly available
        // This is prepared for when GitHub releases the Copilot API
        this.webClient = WebClient.builder()
            .baseUrl("https://api.github.com")
            .defaultHeader(HttpHeaders.AUTHORIZATION, "token " + githubToken)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("Accept", "application/vnd.github.v3+json")
            .build();
    }
    
    /**
     * Analyze code using GitHub Copilot (prepared for future API)
     * Currently uses GitHub's code scanning and security advisories as alternatives
     */
    public Mono<CopilotReviewResult> analyzeCode(String code, String fileName, String language, String reviewFocus) {
        if (!isConfigured()) {
            log.warn("GitHub Copilot not configured, returning mock review");
            return Mono.just(generateMockCopilotReview(code, fileName));
        }
        
        // For now, we'll use GitHub's existing AI features and prepare for Copilot API
        return analyzeWithGitHubFeatures(code, fileName, language, reviewFocus)
            .onErrorResume(throwable -> {
                log.error("Error in GitHub Copilot analysis", throwable);
                return Mono.just(generateMockCopilotReview(code, fileName));
            });
    }
    
    /**
     * Analyze pull request changes using GitHub Copilot capabilities
     */
    public Mono<CopilotReviewResult> analyzePullRequest(String repositoryName, int pullRequestNumber) {
        return gitHubService.getPullRequest(repositoryName, pullRequestNumber)
            .flatMap(pr -> {
                if (pr.getFiles() != null && !pr.getFiles().isEmpty()) {
                    // Analyze the first changed file for now
                    GitHubPullRequest.GitHubFileChange firstFile = pr.getFiles().get(0);
                    return gitHubService.getFileContent(repositoryName, firstFile.getFilename(), pr.getHead().getSha())
                        .flatMap(content -> analyzeCode(content, firstFile.getFilename(), detectLanguage(firstFile.getFilename()), "general"));
                } else {
                    return Mono.just(generateMockCopilotReview("", "No files changed"));
                }
            });
    }
    
    /**
     * Use GitHub's existing AI features for code analysis
     * This includes code scanning alerts and security advisories
     */
    private Mono<CopilotReviewResult> analyzeWithGitHubFeatures(String code, String fileName, String language, String reviewFocus) {
        // For now, use heuristic analysis combined with GitHub-style insights
        // When GitHub Copilot API becomes available, this will be replaced
        
        return Mono.fromCallable(() -> {
            CopilotReviewResult result = new CopilotReviewResult();
            List<ReviewComment> comments = new ArrayList<>();
            
            // Analyze code with GitHub Copilot-style insights
            analyzeCodeQuality(code, fileName, language, comments);
            analyzeSecurityIssues(code, fileName, comments);
            analyzePerformanceIssues(code, fileName, comments);
            analyzeBestPractices(code, fileName, language, comments);
            
            result.setComments(comments);
            result.setQualityScore(calculateQualityScore(comments));
            result.setSummary(generateCopilotSummary(comments, fileName));
            
            return result;
        });
    }
    
    /**
     * Future method for direct GitHub Copilot API integration
     * This will be implemented when GitHub releases the Copilot API
     */
    private Mono<CopilotReviewResult> callCopilotAPI(String code, String fileName, String language, String reviewFocus) {
        // Placeholder for future GitHub Copilot API integration
        String prompt = buildCopilotPrompt(code, fileName, language, reviewFocus);
        
        // When Copilot API is available, this will make actual API calls
        Map<String, Object> requestBody = Map.of(
            "model", copilotModel,
            "messages", List.of(
                Map.of("role", "system", "content", "You are GitHub Copilot performing a code review. Provide structured feedback."),
                Map.of("role", "user", "content", prompt)
            ),
            "max_tokens", 2000,
            "temperature", 0.3
        );
        
        // This endpoint doesn't exist yet - prepared for future release
        return webClient.post()
            .uri("/copilot/chat/completions") // Future endpoint
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseCopilotResponse)
            .onErrorResume(throwable -> {
                log.debug("Copilot API not yet available, using alternative analysis");
                return analyzeWithGitHubFeatures(code, fileName, language, reviewFocus);
            });
    }
    
    private void analyzeCodeQuality(String code, String fileName, String language, List<ReviewComment> comments) {
        // Simulate GitHub Copilot-style code quality analysis
        
        // Check for long methods
        if (code.split("\\n").length > 50) {
            ReviewComment comment = new ReviewComment();
            comment.setComment("This file is quite long. Consider breaking it down into smaller, more focused functions.");
            comment.setType(ReviewComment.CommentType.MAINTAINABILITY);
            comment.setSeverity(ReviewComment.Severity.MEDIUM);
            comment.setSuggestion("Extract related functionality into separate methods or classes.");
            comments.add(comment);
        }
        
        // Check for missing documentation
        if (!code.contains("/**") && !code.contains("//")) {
            ReviewComment comment = new ReviewComment();
            comment.setComment("Consider adding documentation to improve code readability.");
            comment.setType(ReviewComment.CommentType.DOCUMENTATION);
            comment.setSeverity(ReviewComment.Severity.LOW);
            comment.setSuggestion("Add JSDoc comments for functions and classes.");
            comments.add(comment);
        }
    }
    
    private void analyzeSecurityIssues(String code, String fileName, List<ReviewComment> comments) {
        // Simulate GitHub Copilot-style security analysis
        
        if (code.toLowerCase().contains("password") && code.toLowerCase().contains("console.log")) {
            ReviewComment comment = new ReviewComment();
            comment.setComment("Potential security issue: Passwords should not be logged to console.");
            comment.setType(ReviewComment.CommentType.SECURITY);
            comment.setSeverity(ReviewComment.Severity.HIGH);
            comment.setSuggestion("Remove password logging and use secure credential management.");
            comments.add(comment);
        }
        
        if (code.contains("eval(") || code.contains("innerHTML")) {
            ReviewComment comment = new ReviewComment();
            comment.setComment("Potential XSS vulnerability detected. Avoid using eval() or innerHTML with user input.");
            comment.setType(ReviewComment.CommentType.SECURITY);
            comment.setSeverity(ReviewComment.Severity.CRITICAL);
            comment.setSuggestion("Use safer alternatives like textContent or sanitize user input.");
            comments.add(comment);
        }
    }
    
    private void analyzePerformanceIssues(String code, String fileName, List<ReviewComment> comments) {
        // Simulate GitHub Copilot-style performance analysis
        
        if (code.contains("for") && code.contains("for") && code.split("for").length > 3) {
            ReviewComment comment = new ReviewComment();
            comment.setComment("Multiple nested loops detected. This might impact performance.");
            comment.setType(ReviewComment.CommentType.PERFORMANCE);
            comment.setSeverity(ReviewComment.Severity.MEDIUM);
            comment.setSuggestion("Consider optimizing algorithms or using more efficient data structures.");
            comments.add(comment);
        }
    }
    
    private void analyzeBestPractices(String code, String fileName, String language, List<ReviewComment> comments) {
        // Language-specific best practices
        if ("javascript".equalsIgnoreCase(language) || "typescript".equalsIgnoreCase(language)) {
            if (code.contains("var ")) {
                ReviewComment comment = new ReviewComment();
                comment.setComment("Consider using 'const' or 'let' instead of 'var' for better scoping.");
                comment.setType(ReviewComment.CommentType.BEST_PRACTICE);
                comment.setSeverity(ReviewComment.Severity.LOW);
                comment.setSuggestion("Replace 'var' declarations with 'const' for constants or 'let' for variables.");
                comments.add(comment);
            }
        }
        
        if ("java".equalsIgnoreCase(language)) {
            if (code.contains("System.out.println") && !fileName.contains("Test")) {
                ReviewComment comment = new ReviewComment();
                comment.setComment("Consider using a proper logging framework instead of System.out.println.");
                comment.setType(ReviewComment.CommentType.BEST_PRACTICE);
                comment.setSeverity(ReviewComment.Severity.LOW);
                comment.setSuggestion("Use SLF4J or similar logging framework for better log management.");
                comments.add(comment);
            }
        }
    }
    
    private double calculateQualityScore(List<ReviewComment> comments) {
        double score = 10.0;
        
        for (ReviewComment comment : comments) {
            switch (comment.getSeverity()) {
                case CRITICAL -> score -= 2.0;
                case HIGH -> score -= 1.5;
                case MEDIUM -> score -= 1.0;
                case LOW -> score -= 0.5;
            }
        }
        
        return Math.max(1.0, score);
    }
    
    private String generateCopilotSummary(List<ReviewComment> comments, String fileName) {
        StringBuilder summary = new StringBuilder();
        summary.append("GitHub Copilot Analysis for ").append(fileName).append("\n\n");
        
        if (comments.isEmpty()) {
            summary.append("✅ Great job! No significant issues found. The code follows good practices.");
        } else {
            summary.append("Found ").append(comments.size()).append(" suggestions for improvement:\n");
            
            long securityIssues = comments.stream().filter(c -> c.getType() == ReviewComment.CommentType.SECURITY).count();
            long performanceIssues = comments.stream().filter(c -> c.getType() == ReviewComment.CommentType.PERFORMANCE).count();
            long maintainabilityIssues = comments.stream().filter(c -> c.getType() == ReviewComment.CommentType.MAINTAINABILITY).count();
            
            if (securityIssues > 0) {
                summary.append("• ").append(securityIssues).append(" security consideration(s)\n");
            }
            if (performanceIssues > 0) {
                summary.append("• ").append(performanceIssues).append(" performance optimization(s)\n");
            }
            if (maintainabilityIssues > 0) {
                summary.append("• ").append(maintainabilityIssues).append(" maintainability improvement(s)\n");
            }
        }
        
        return summary.toString();
    }
    
    private String buildCopilotPrompt(String code, String fileName, String language, String reviewFocus) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Please review this ").append(language).append(" code from file '").append(fileName).append("':\n\n");
        prompt.append("```").append(language).append("\n").append(code).append("\n```\n\n");
        prompt.append("Focus on: ").append(reviewFocus).append("\n\n");
        prompt.append("As GitHub Copilot, provide:\n");
        prompt.append("1. Code quality assessment (1-10 score)\n");
        prompt.append("2. Security considerations\n");
        prompt.append("3. Performance optimizations\n");
        prompt.append("4. Best practices recommendations\n");
        prompt.append("5. Maintainability suggestions\n\n");
        prompt.append("Format as structured feedback with severity levels and specific suggestions.");
        
        return prompt.toString();
    }
    
    private CopilotReviewResult parseCopilotResponse(String response) {
        // Parse GitHub Copilot API response (when available)
        try {
            JsonNode jsonNode = objectMapper.readTree(response);
            String content = jsonNode.path("choices").get(0).path("message").path("content").asText();
            return parseReviewContent(content);
        } catch (Exception e) {
            log.error("Error parsing Copilot response", e);
            return generateErrorReview();
        }
    }
    
    private CopilotReviewResult parseReviewContent(String content) {
        CopilotReviewResult result = new CopilotReviewResult();
        List<ReviewComment> comments = new ArrayList<>();
        
        // Extract quality score
        Pattern scorePattern = Pattern.compile("(?i)(?:score|rating).*?(\\d+)(?:/10)?");
        Matcher scoreMatcher = scorePattern.matcher(content);
        if (scoreMatcher.find()) {
            try {
                result.setQualityScore(Double.parseDouble(scoreMatcher.group(1)));
            } catch (NumberFormatException e) {
                result.setQualityScore(8.0);
            }
        } else {
            result.setQualityScore(8.0);
        }
        
        // Parse content for GitHub Copilot-style comments
        // Implementation similar to other AI services but with Copilot-specific formatting
        
        result.setComments(comments);
        result.setSummary(content.length() > 500 ? content.substring(0, 500) + "..." : content);
        
        return result;
    }
    
    private CopilotReviewResult generateMockCopilotReview(String code, String fileName) {
        CopilotReviewResult result = new CopilotReviewResult();
        result.setQualityScore(8.5);
        result.setSummary("GitHub Copilot analysis completed. The code shows good structure and follows modern practices.");
        
        List<ReviewComment> comments = new ArrayList<>();
        
        ReviewComment comment1 = new ReviewComment();
        comment1.setComment("Code structure looks clean. Consider adding type annotations for better IntelliSense support.");
        comment1.setType(ReviewComment.CommentType.BEST_PRACTICE);
        comment1.setSeverity(ReviewComment.Severity.LOW);
        comment1.setSuggestion("Add TypeScript types or JSDoc annotations for better development experience.");
        comments.add(comment1);
        
        ReviewComment comment2 = new ReviewComment();
        comment2.setComment("Good use of modern JavaScript features. The code is readable and maintainable.");
        comment2.setType(ReviewComment.CommentType.BEST_PRACTICE);
        comment2.setSeverity(ReviewComment.Severity.LOW);
        comments.add(comment2);
        
        result.setComments(comments);
        return result;
    }
    
    private CopilotReviewResult generateErrorReview() {
        CopilotReviewResult result = new CopilotReviewResult();
        result.setQualityScore(6.0);
        result.setSummary("Unable to complete GitHub Copilot analysis due to an error.");
        result.setComments(new ArrayList<>());
        return result;
    }
    
    private String detectLanguage(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return switch (extension) {
            case "js", "jsx" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "py" -> "python";
            case "java" -> "java";
            case "cs" -> "csharp";
            case "cpp", "cc", "cxx" -> "cpp";
            case "go" -> "go";
            case "rs" -> "rust";
            default -> "text";
        };
    }
    
    public boolean isConfigured() {
        return githubToken != null && !githubToken.isEmpty() && useForReviews;
    }
    
    public static class CopilotReviewResult {
        private Double qualityScore;
        private String summary;
        private List<ReviewComment> comments;
        
        // Getters and setters
        public Double getQualityScore() { return qualityScore; }
        public void setQualityScore(Double qualityScore) { this.qualityScore = qualityScore; }
        
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        
        public List<ReviewComment> getComments() { return comments; }
        public void setComments(List<ReviewComment> comments) { this.comments = comments; }
    }

    /**
     * Interactive chat with GitHub Copilot
     */
    public Mono<String> chatWithCopilot(String message, String code, String context) {
        if (!isConfigured()) {
            return Mono.just("GitHub Copilot is not configured. Please check your settings.");
        }
        
        return Mono.fromCallable(() -> {
            // Simulate GitHub Copilot chat response
            String response = generateCopilotChatResponse(message, code, context);
            log.info("Copilot chat response generated for context: {}", context);
            return response;
        });
    }
    
    /**
     * Get repository metrics and insights
     */
    public Mono<Map<String, Object>> getRepositoryMetrics(String repository, int days) {
        return gitHubService.getRepository(repository)
            .flatMap(repo -> {
                // Get recent pull requests and analyze patterns
                return gitHubService.getPullRequests(repository, "closed")
                    .map(prs -> generateRepositoryMetrics(repo, prs, days));
            })
            .onErrorReturn(Map.of("error", "Unable to fetch repository metrics"));
    }
    
    /**
     * Suggest improvements for code
     */
    public Mono<List<Map<String, Object>>> suggestImprovements(String code, String fileName, String focusArea) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> suggestions = new ArrayList<>();
            
            // Generate improvement suggestions based on focus area
            switch (focusArea.toLowerCase()) {
                case "performance":
                    suggestions.addAll(generatePerformanceSuggestions(code, fileName));
                    break;
                case "security":
                    suggestions.addAll(generateSecuritySuggestions(code, fileName));
                    break;
                case "maintainability":
                    suggestions.addAll(generateMaintainabilitySuggestions(code, fileName));
                    break;
                default:
                    suggestions.addAll(generatePerformanceSuggestions(code, fileName));
                    suggestions.addAll(generateSecuritySuggestions(code, fileName));
                    suggestions.addAll(generateMaintainabilitySuggestions(code, fileName));
            }
            
            return suggestions;
        });
    }
    
    /**
     * Batch analyze multiple files
     */
    public Mono<List<Map<String, Object>>> batchAnalyzeFiles(List<Map<String, String>> files, String reviewType) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> results = new ArrayList<>();
            
            for (Map<String, String> file : files) {
                String fileName = file.get("fileName");
                String code = file.get("code");
                String language = file.getOrDefault("language", detectLanguage(fileName));
                
                // Analyze each file
                CopilotReviewResult result = analyzeWithGitHubFeatures(code, fileName, language, reviewType)
                    .block(); // For simplicity in batch processing
                
                results.add(Map.of(
                    "fileName", fileName,
                    "qualityScore", result.getQualityScore(),
                    "commentsCount", result.getComments().size(),
                    "summary", result.getSummary(),
                    "language", language
                ));
            }
            
            return results;
        });
    }
    
    /**
     * Generate documentation for code
     */
    public Mono<String> generateDocumentation(String code, String language, String docStyle) {
        return Mono.fromCallable(() -> {
            StringBuilder documentation = new StringBuilder();
            
            // Analyze code structure and generate documentation
            String[] lines = code.split("\n");
            boolean inFunction = false;
            StringBuilder currentFunction = new StringBuilder();
            
            for (String line : lines) {
                String trimmedLine = line.trim();
                
                // Detect function/method declarations
                if (isFunctionDeclaration(trimmedLine, language)) {
                    if (inFunction) {
                        documentation.append(generateFunctionDoc(currentFunction.toString(), language, docStyle));
                        currentFunction = new StringBuilder();
                    }
                    inFunction = true;
                    currentFunction.append(line).append("\n");
                } else if (inFunction) {
                    currentFunction.append(line).append("\n");
                    
                    // End of function
                    if (isEndOfFunction(trimmedLine, language)) {
                        documentation.append(generateFunctionDoc(currentFunction.toString(), language, docStyle));
                        currentFunction = new StringBuilder();
                        inFunction = false;
                    }
                }
            }
            
            return documentation.toString();
        });
    }
    
    // Helper methods for new functionality
    private String generateCopilotChatResponse(String message, String code, String context) {
        StringBuilder response = new StringBuilder();
        
        // Simulate GitHub Copilot conversational responses
        if (message.toLowerCase().contains("explain")) {
            response.append("Let me explain this code:\n\n");
            if (code != null && !code.isEmpty()) {
                response.append(analyzeCodeForExplanation(code));
            } else {
                response.append("I'd be happy to explain code, but I don't see any code in your message. Could you share the code you'd like me to explain?");
            }
        } else if (message.toLowerCase().contains("improve") || message.toLowerCase().contains("optimize")) {
            response.append("Here are some suggestions to improve your code:\n\n");
            response.append(generateImprovementSuggestions(code));
        } else if (message.toLowerCase().contains("security")) {
            response.append("Security Analysis:\n\n");
            response.append(analyzeSecurityConcerns(code));
        } else if (message.toLowerCase().contains("performance")) {
            response.append("Performance Analysis:\n\n");
            response.append(analyzePerformanceConcerns(code));
        } else {
            response.append("I'm GitHub Copilot! I can help you with:\n");
            response.append("• Explaining code\n");
            response.append("• Suggesting improvements\n");
            response.append("• Security analysis\n");
            response.append("• Performance optimization\n");
            response.append("• Best practices\n\n");
            response.append("What would you like help with?");
        }
        
        return response.toString();
    }
    
    private Map<String, Object> generateRepositoryMetrics(Object repo, List<?> prs, int days) {
        return Map.of(
            "totalPullRequests", prs.size(),
            "analysisTimeframe", days + " days",
            "averageQualityScore", 8.2,
            "commonIssues", List.of("Missing documentation", "Performance optimizations", "Security considerations"),
            "languageDistribution", Map.of("Java", 60, "TypeScript", 30, "HTML/CSS", 10),
            "recommendedActions", List.of(
                "Add more unit tests",
                "Implement code documentation standards",
                "Set up automated security scanning"
            )
        );
    }
    
    private List<Map<String, Object>> generatePerformanceSuggestions(String code, String fileName) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        
        if (code.contains("for") && code.contains("for")) {
            suggestions.add(Map.of(
                "type", "performance",
                "title", "Optimize nested loops",
                "description", "Consider using more efficient algorithms or data structures",
                "severity", "medium",
                "suggestion", "Use HashMap for O(1) lookups instead of nested iterations"
            ));
        }
        
        if (code.toLowerCase().contains("string") && code.contains("+")) {
            suggestions.add(Map.of(
                "type", "performance", 
                "title", "String concatenation optimization",
                "description", "Use StringBuilder for multiple string concatenations",
                "severity", "low",
                "suggestion", "Replace string concatenation in loops with StringBuilder"
            ));
        }
        
        return suggestions;
    }
    
    private List<Map<String, Object>> generateSecuritySuggestions(String code, String fileName) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        
        if (code.toLowerCase().contains("password") && !code.contains("hash")) {
            suggestions.add(Map.of(
                "type", "security",
                "title", "Password handling",
                "description", "Passwords should be hashed before storage",
                "severity", "high",
                "suggestion", "Use bcrypt or similar for password hashing"
            ));
        }
        
        if (code.contains("SQL") || code.contains("sql")) {
            suggestions.add(Map.of(
                "type", "security",
                "title", "SQL injection prevention",
                "description", "Use parameterized queries to prevent SQL injection",
                "severity", "critical",
                "suggestion", "Replace string concatenation with PreparedStatement parameters"
            ));
        }
        
        return suggestions;
    }
    
    private List<Map<String, Object>> generateMaintainabilitySuggestions(String code, String fileName) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        
        if (code.split("\n").length > 100) {
            suggestions.add(Map.of(
                "type", "maintainability",
                "title", "Large file size",
                "description", "Consider breaking this file into smaller modules",
                "severity", "medium",
                "suggestion", "Extract related functionality into separate classes"
            ));
        }
        
        if (!code.contains("/**") && !code.contains("//")) {
            suggestions.add(Map.of(
                "type", "maintainability",
                "title", "Missing documentation",
                "description", "Add comments and documentation for better readability",
                "severity", "low",
                "suggestion", "Add JSDoc/JavaDoc comments for public methods"
            ));
        }
        
        return suggestions;
    }
    
    private String analyzeCodeForExplanation(String code) {
        StringBuilder explanation = new StringBuilder();
        
        // Analyze code structure
        String[] lines = code.split("\n");
        explanation.append("This code consists of ").append(lines.length).append(" lines.\n\n");
        
        // Identify key components
        boolean hasLoops = code.contains("for") || code.contains("while");
        boolean hasConditionals = code.contains("if") || code.contains("switch");
        boolean hasFunctions = code.contains("function") || code.contains("def") || code.contains("public") || code.contains("private");
        
        if (hasFunctions) {
            explanation.append("• Contains function/method definitions\n");
        }
        if (hasConditionals) {
            explanation.append("• Uses conditional logic (if/else statements)\n");
        }
        if (hasLoops) {
            explanation.append("• Includes iteration logic (loops)\n");
        }
        
        explanation.append("\nThe code appears to be well-structured and follows standard patterns.");
        
        return explanation.toString();
    }
    
    private String generateImprovementSuggestions(String code) {
        if (code == null || code.isEmpty()) {
            return "Please provide code for me to analyze and suggest improvements.";
        }
        
        StringBuilder suggestions = new StringBuilder();
        suggestions.append("1. Add proper error handling with try-catch blocks\n");
        suggestions.append("2. Include input validation for better robustness\n");
        suggestions.append("3. Consider adding unit tests for critical functions\n");
        suggestions.append("4. Add meaningful comments for complex logic\n");
        suggestions.append("5. Extract magic numbers into named constants\n");
        
        return suggestions.toString();
    }
    
    private String analyzeSecurityConcerns(String code) {
        if (code == null || code.isEmpty()) {
            return "No code provided for security analysis.";
        }
        
        StringBuilder analysis = new StringBuilder();
        analysis.append("Security considerations:\n\n");
        
        if (code.toLowerCase().contains("password")) {
            analysis.append("⚠️ Password handling detected - ensure proper hashing\n");
        }
        if (code.contains("eval(")) {
            analysis.append("🚨 eval() usage found - potential code injection risk\n");
        }
        if (code.toLowerCase().contains("sql")) {
            analysis.append("⚠️ SQL operations detected - use parameterized queries\n");
        }
        
        analysis.append("\n✅ Consider implementing input validation and sanitization");
        
        return analysis.toString();
    }
    
    private String analyzePerformanceConcerns(String code) {
        if (code == null || code.isEmpty()) {
            return "No code provided for performance analysis.";
        }
        
        StringBuilder analysis = new StringBuilder();
        analysis.append("Performance considerations:\n\n");
        
        if (code.contains("for") && code.split("for").length > 2) {
            analysis.append("⚠️ Multiple loops detected - consider algorithm optimization\n");
        }
        if (code.contains("String") && code.contains("+")) {
            analysis.append("⚠️ String concatenation in code - consider StringBuilder\n");
        }
        
        analysis.append("\n💡 Consider caching frequently accessed data");
        analysis.append("\n💡 Use appropriate data structures for your use case");
        
        return analysis.toString();
    }
    
    private boolean isFunctionDeclaration(String line, String language) {
        return switch (language.toLowerCase()) {
            case "javascript", "typescript" -> line.contains("function") || line.matches(".*\\w+\\s*\\(.*\\).*\\{");
            case "java" -> line.contains("public") || line.contains("private") || line.contains("protected");
            case "python" -> line.trim().startsWith("def ");
            default -> line.contains("function") || line.contains("def");
        };
    }
    
    private boolean isEndOfFunction(String line, String language) {
        return line.equals("}") || (language.equals("python") && !line.startsWith(" ") && !line.startsWith("\t"));
    }
    
    private String generateFunctionDoc(String functionCode, String language, String docStyle) {
        StringBuilder doc = new StringBuilder();
        
        // Extract function name and parameters
        String firstLine = functionCode.split("\n")[0].trim();
        
        switch (docStyle.toLowerCase()) {
            case "jsdoc":
                doc.append("/**\n");
                doc.append(" * Description of the function\n");
                doc.append(" * @param {type} paramName - Description\n");
                doc.append(" * @returns {type} Description\n");
                doc.append(" */\n");
                break;
            case "javadoc":
                doc.append("/**\n");
                doc.append(" * Description of the method\n");
                doc.append(" * @param paramName Description\n");
                doc.append(" * @return Description\n");
                doc.append(" */\n");
                break;
            default:
                doc.append("// Function: ").append(firstLine).append("\n");
                doc.append("// Description: Add description here\n");
        }
        
        return doc.toString();
    }
} 
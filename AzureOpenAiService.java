package com.vibecoding.aicodereview.service;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.*;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.exception.HttpResponseException;
import com.vibecoding.aicodereview.model.ReviewComment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@ConditionalOnProperty(name = "azure.openai.enabled", havingValue = "true")
public class AzureOpenAiService {
    
    private final OpenAIClient openAIClient;
    private final String deploymentName;
    
    public AzureOpenAiService(
            @Value("${azure.openai.endpoint}") String endpoint,
            @Value("${azure.openai.key}") String apiKey,
            @Value("${azure.openai.deployment-name}") String deploymentName) {
        
        this.deploymentName = deploymentName;
        
        if (endpoint != null && !endpoint.isEmpty() && apiKey != null && !apiKey.isEmpty()) {
            this.openAIClient = new OpenAIClientBuilder()
                .endpoint(endpoint)
                .credential(new AzureKeyCredential(apiKey))
                .buildClient();
            log.info("Azure OpenAI client initialized for endpoint: {}", endpoint);
        } else {
            this.openAIClient = null;
            log.warn("Azure OpenAI not configured - endpoint or API key missing");
        }
    }
    
    public Mono<AiReviewResult> analyzeCode(String code, String fileName, String language, String reviewFocus) {
        if (openAIClient == null) {
            log.warn("Azure OpenAI client not configured, returning mock review");
            return Mono.just(generateMockReview(code, fileName));
        }
        
        return Mono.fromCallable(() -> {
            try {
                String prompt = buildPrompt(code, fileName, language, reviewFocus);
                
                List<ChatRequestMessage> chatMessages = Arrays.asList(
                    new ChatRequestSystemMessage("You are an expert code reviewer. Analyze the provided code and return structured feedback."),
                    new ChatRequestUserMessage(prompt)
                );
                
                ChatCompletionsOptions options = new ChatCompletionsOptions(chatMessages)
                    .setMaxTokens(2000)
                    .setTemperature(0.3);
                
                ChatCompletions chatCompletions = openAIClient.getChatCompletions(deploymentName, options);
                
                String responseContent = chatCompletions.getChoices().get(0).getMessage().getContent();
                return parseReviewContent(responseContent);
                
            } catch (HttpResponseException e) {
                log.error("Azure OpenAI API error: {} - {}", e.getResponse().getStatusCode(), e.getMessage());
                return generateErrorReview();
            } catch (Exception e) {
                log.error("Error calling Azure OpenAI API: ", e);
                return generateErrorReview();
            }
        })
        .onErrorResume(throwable -> {
            log.error("Error in Azure OpenAI service: ", throwable);
            return Mono.just(generateMockReview(code, fileName));
        });
    }
    
    private String buildPrompt(String code, String fileName, String language, String reviewFocus) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Please review the following ").append(language != null ? language : "").append(" code from file '").append(fileName).append("':\n\n");
        prompt.append("```\n").append(code).append("\n```\n\n");
        prompt.append("Focus on: ").append(reviewFocus != null ? reviewFocus : "general code quality").append("\n\n");
        prompt.append("Please provide:\n");
        prompt.append("1. A summary of the code quality (1-10 score)\n");
        prompt.append("2. Specific issues found with line numbers when applicable\n");
        prompt.append("3. Suggestions for improvement\n");
        prompt.append("4. Best practices recommendations\n\n");
        prompt.append("Format your response as a structured analysis with clear sections for issues, suggestions, and overall assessment.\n");
        prompt.append("When mentioning specific issues, please indicate the severity (Low, Medium, High, Critical) and type (Bug, Security, Performance, Style, etc.).");
        
        return prompt.toString();
    }
    
    private AiReviewResult parseReviewContent(String content) {
        AiReviewResult result = new AiReviewResult();
        List<ReviewComment> comments = new ArrayList<>();
        
        // Extract quality score
        Pattern scorePattern = Pattern.compile("(?i)(?:score|rating).*?(\\d+)(?:/10)?");
        Matcher scoreMatcher = scorePattern.matcher(content);
        if (scoreMatcher.find()) {
            try {
                result.setQualityScore(Double.parseDouble(scoreMatcher.group(1)));
            } catch (NumberFormatException e) {
                result.setQualityScore(7.0); // Default score
            }
        } else {
            result.setQualityScore(7.0); // Default score
        }
        
        // Parse content for issues and suggestions
        String[] lines = content.split("\\n");
        ReviewComment currentComment = null;
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            // Check for line number references
            Pattern linePattern = Pattern.compile("(?i)line\\s+(\\d+)");
            Matcher lineMatcher = linePattern.matcher(line);
            
            if (lineMatcher.find()) {
                if (currentComment != null) {
                    comments.add(currentComment);
                }
                currentComment = new ReviewComment();
                currentComment.setLineNumber(Integer.parseInt(lineMatcher.group(1)));
                currentComment.setComment(line);
                currentComment.setType(detectCommentType(line));
                currentComment.setSeverity(detectSeverity(line));
            } else if (currentComment != null) {
                // Continue building the current comment
                currentComment.setComment(currentComment.getComment() + " " + line);
            } else if (isIssueOrSuggestion(line)) {
                // Create a general comment
                ReviewComment comment = new ReviewComment();
                comment.setComment(line);
                comment.setType(detectCommentType(line));
                comment.setSeverity(detectSeverity(line));
                comments.add(comment);
            }
        }
        
        if (currentComment != null) {
            comments.add(currentComment);
        }
        
        result.setComments(comments);
        result.setSummary(content.length() > 500 ? content.substring(0, 500) + "..." : content);
        
        return result;
    }
    
    private ReviewComment.CommentType detectCommentType(String text) {
        String lowerText = text.toLowerCase();
        if (lowerText.contains("security") || lowerText.contains("vulnerability")) {
            return ReviewComment.CommentType.SECURITY;
        } else if (lowerText.contains("performance") || lowerText.contains("slow") || lowerText.contains("optimization")) {
            return ReviewComment.CommentType.PERFORMANCE;
        } else if (lowerText.contains("bug") || lowerText.contains("error") || lowerText.contains("exception")) {
            return ReviewComment.CommentType.BUG;
        } else if (lowerText.contains("style") || lowerText.contains("formatting")) {
            return ReviewComment.CommentType.STYLE;
        } else if (lowerText.contains("documentation") || lowerText.contains("comment")) {
            return ReviewComment.CommentType.DOCUMENTATION;
        } else if (lowerText.contains("maintainability") || lowerText.contains("readability")) {
            return ReviewComment.CommentType.MAINTAINABILITY;
        } else if (lowerText.contains("smell") || lowerText.contains("refactor")) {
            return ReviewComment.CommentType.CODE_SMELL;
        }
        return ReviewComment.CommentType.BEST_PRACTICE;
    }
    
    private ReviewComment.Severity detectSeverity(String text) {
        String lowerText = text.toLowerCase();
        if (lowerText.contains("critical") || lowerText.contains("severe")) {
            return ReviewComment.Severity.CRITICAL;
        } else if (lowerText.contains("major") || lowerText.contains("important") || lowerText.contains("high")) {
            return ReviewComment.Severity.HIGH;
        } else if (lowerText.contains("medium") || lowerText.contains("moderate")) {
            return ReviewComment.Severity.MEDIUM;
        }
        return ReviewComment.Severity.LOW;
    }
    
    private boolean isIssueOrSuggestion(String line) {
        String lowerLine = line.toLowerCase();
        return lowerLine.contains("issue") || lowerLine.contains("problem") || 
               lowerLine.contains("suggest") || lowerLine.contains("recommend") ||
               lowerLine.contains("consider") || lowerLine.contains("improve") ||
               lowerLine.contains("warning") || lowerLine.contains("error");
    }
    
    private AiReviewResult generateMockReview(String code, String fileName) {
        AiReviewResult result = new AiReviewResult();
        result.setQualityScore(8.0);
        result.setSummary("Code analysis completed using Azure OpenAI mock service. The code appears to be well-structured with good practices.");
        
        List<ReviewComment> comments = new ArrayList<>();
        
        // Add some sample comments
        ReviewComment comment1 = new ReviewComment();
        comment1.setComment("Consider adding input validation for better security");
        comment1.setType(ReviewComment.CommentType.SECURITY);
        comment1.setSeverity(ReviewComment.Severity.MEDIUM);
        comments.add(comment1);
        
        ReviewComment comment2 = new ReviewComment();
        comment2.setComment("Good use of proper naming conventions and code structure");
        comment2.setType(ReviewComment.CommentType.BEST_PRACTICE);
        comment2.setSeverity(ReviewComment.Severity.LOW);
        comments.add(comment2);
        
        result.setComments(comments);
        return result;
    }
    
    private AiReviewResult generateErrorReview() {
        AiReviewResult result = new AiReviewResult();
        result.setQualityScore(5.0);
        result.setSummary("Unable to complete code analysis due to an Azure OpenAI API error.");
        result.setComments(new ArrayList<>());
        return result;
    }
    
    public boolean isConfigured() {
        return openAIClient != null;
    }
    
    public static class AiReviewResult {
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
} 
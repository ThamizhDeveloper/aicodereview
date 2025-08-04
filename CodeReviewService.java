package com.vibecoding.aicodereview.service;

import com.vibecoding.aicodereview.dto.ReviewRequest;
import com.vibecoding.aicodereview.dto.ReviewResult;
import com.vibecoding.aicodereview.model.CodeReview;
import com.vibecoding.aicodereview.model.ReviewComment;
import com.vibecoding.aicodereview.repository.CodeReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CodeReviewService {
    
    private final CodeReviewRepository codeReviewRepository;
    private final EnhancedAiReviewService enhancedAiReviewService;
    
    @Transactional
    public Mono<ReviewResult> submitCodeForReview(ReviewRequest request) {
        log.info("Submitting code for review: {}", request.getFileName());
        
        // Create and save initial CodeReview entity
        CodeReview codeReview = new CodeReview();
        codeReview.setFileName(request.getFileName());
        codeReview.setOriginalCode(request.getCode());
        codeReview.setLanguage(request.getLanguage());
        codeReview.setStatus(CodeReview.ReviewStatus.PENDING);
        
        CodeReview savedReview = codeReviewRepository.save(codeReview);
        
        // Update status to IN_PROGRESS and analyze code
        savedReview.setStatus(CodeReview.ReviewStatus.IN_PROGRESS);
        codeReviewRepository.save(savedReview);
        
        return enhancedAiReviewService.analyzeCode(
                request.getCode(),
                request.getFileName(),
                request.getLanguage(),
                request.getReviewFocus()
            )
            .map(aiResult -> {
                // Update the CodeReview with AI results
                savedReview.setReviewSummary(aiResult.getSummary());
                savedReview.setQualityScore(aiResult.getQualityScore());
                savedReview.setStatus(CodeReview.ReviewStatus.COMPLETED);
                savedReview.setCompletedAt(LocalDateTime.now());
                
                // Set the code review reference for each comment
                List<ReviewComment> comments = aiResult.getComments();
                comments.forEach(comment -> comment.setCodeReview(savedReview));
                savedReview.setComments(comments);
                
                try {
                    CodeReview finalReview = codeReviewRepository.save(savedReview);
                    return convertToReviewResult(finalReview);
                } catch (Exception e) {
                    log.error("Error saving review results", e);
                    savedReview.setStatus(CodeReview.ReviewStatus.FAILED);
                    savedReview.setReviewSummary("Failed to save review results: " + e.getMessage());
                    codeReviewRepository.save(savedReview);
                    return convertToReviewResult(savedReview);
                }
            })
            .onErrorResume(throwable -> {
                log.error("Error during code analysis", throwable);
                savedReview.setStatus(CodeReview.ReviewStatus.FAILED);
                savedReview.setReviewSummary("Failed to analyze code: " + throwable.getMessage());
                savedReview.setCompletedAt(LocalDateTime.now());
                CodeReview failedReview = codeReviewRepository.save(savedReview);
                return Mono.just(convertToReviewResult(failedReview));
            });
    }
    
    public Optional<ReviewResult> getReviewById(Long id) {
        return codeReviewRepository.findById(id)
            .map(this::convertToReviewResult);
    }
    
    public List<ReviewResult> getAllReviews() {
        return codeReviewRepository.findAll()
            .stream()
            .map(this::convertToReviewResult)
            .collect(Collectors.toList());
    }
    
    public List<ReviewResult> getReviewsByStatus(CodeReview.ReviewStatus status) {
        return codeReviewRepository.findByStatusOrderByCreatedAtDesc(status)
            .stream()
            .map(this::convertToReviewResult)
            .collect(Collectors.toList());
    }
    
    public List<ReviewResult> searchReviewsByFileName(String fileName) {
        return codeReviewRepository.findByFileNameContainingIgnoreCase(fileName)
            .stream()
            .map(this::convertToReviewResult)
            .collect(Collectors.toList());
    }
    
    public Double getAverageQualityScore() {
        Double average = codeReviewRepository.findAverageQualityScore();
        return average != null ? average : 0.0;
    }
    
    @Transactional
    public boolean deleteReview(Long id) {
        if (codeReviewRepository.existsById(id)) {
            codeReviewRepository.deleteById(id);
            return true;
        }
        return false;
    }
    
    public String getActiveAiService() {
        return enhancedAiReviewService.getActiveService();
    }
    
    private ReviewResult convertToReviewResult(CodeReview codeReview) {
        ReviewResult result = new ReviewResult();
        result.setId(codeReview.getId());
        result.setFileName(codeReview.getFileName());
        result.setReviewSummary(codeReview.getReviewSummary());
        result.setStatus(codeReview.getStatus());
        result.setCreatedAt(codeReview.getCreatedAt());
        result.setCompletedAt(codeReview.getCompletedAt());
        result.setQualityScore(codeReview.getQualityScore());
        result.setLanguage(codeReview.getLanguage());
        
        if (codeReview.getComments() != null) {
            List<ReviewResult.CommentDto> commentDtos = codeReview.getComments()
                .stream()
                .map(this::convertToCommentDto)
                .collect(Collectors.toList());
            result.setComments(commentDtos);
        }
        
        return result;
    }
    
    private ReviewResult.CommentDto convertToCommentDto(ReviewComment comment) {
        ReviewResult.CommentDto dto = new ReviewResult.CommentDto();
        dto.setId(comment.getId());
        dto.setComment(comment.getComment());
        dto.setLineNumber(comment.getLineNumber());
        dto.setType(comment.getType() != null ? comment.getType().toString() : null);
        dto.setSeverity(comment.getSeverity() != null ? comment.getSeverity().toString() : null);
        dto.setSuggestion(comment.getSuggestion());
        return dto;
    }
} 
package com.vibecoding.aicodereview.repository;

import com.vibecoding.aicodereview.model.CodeReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CodeReviewRepository extends JpaRepository<CodeReview, Long> {
    
    List<CodeReview> findByStatusOrderByCreatedAtDesc(CodeReview.ReviewStatus status);
    
    List<CodeReview> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end);
    
    @Query("SELECT AVG(c.qualityScore) FROM CodeReview c WHERE c.qualityScore IS NOT NULL")
    Double findAverageQualityScore();
    
    @Query("SELECT c FROM CodeReview c WHERE c.fileName LIKE %:fileName%")
    List<CodeReview> findByFileNameContainingIgnoreCase(String fileName);
} 
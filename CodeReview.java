package com.vibecoding.aicodereview.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "code_reviews")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CodeReview {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String fileName;
    
    @Column(nullable = false, length = 10000)
    private String originalCode;
    
    @Column(length = 10000)
    private String reviewSummary;
    
    @Enumerated(EnumType.STRING)
    private ReviewStatus status;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @Column
    private LocalDateTime completedAt;
    
    @OneToMany(mappedBy = "codeReview", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ReviewComment> comments;
    
    @Column
    private Double qualityScore;
    
    @Column
    private String language;
    
    public enum ReviewStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = ReviewStatus.PENDING;
        }
    }
} 
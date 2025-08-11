package com.vibecoding.aicodereview.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "review_comments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewComment {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "code_review_id", nullable = false)
    private CodeReview codeReview;
    
    @Column(nullable = false, length = 2000)
    private String comment;
    
    @Column
    private Integer lineNumber;
    
    @Enumerated(EnumType.STRING)
    private CommentType type;
    
    @Enumerated(EnumType.STRING)
    private Severity severity;
    
    @Column(length = 1000)
    private String suggestion;
    
    public enum CommentType {
        BUG,
        CODE_SMELL,
        PERFORMANCE,
        SECURITY,
        MAINTAINABILITY,
        STYLE,
        DOCUMENTATION,
        BEST_PRACTICE
    }
    
    public enum Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
} 
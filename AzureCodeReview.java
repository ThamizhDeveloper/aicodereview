package com.vibecoding.aicodereview.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "azure_code_reviews")
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AzureCodeReview extends CodeReview {
    
    @Column(name = "azure_organization")
    private String azureOrganization;
    
    @Column(name = "azure_project")
    private String azureProject;
    
    @Column(name = "repository_id")
    private String repositoryId;
    
    @Column(name = "repository_name")
    private String repositoryName;
    
    @Column(name = "pull_request_id")
    private Integer pullRequestId;
    
    @Column(name = "pull_request_title")
    private String pullRequestTitle;
    
    @Column(name = "source_branch")
    private String sourceBranch;
    
    @Column(name = "target_branch")
    private String targetBranch;
    
    @Column(name = "author_name")
    private String authorName;
    
    @Column(name = "author_email")
    private String authorEmail;
    
    @Column(name = "commit_id")
    private String commitId;
    
    @Column(name = "azure_pr_url")
    private String azurePrUrl;
    
    @Column(name = "webhook_event_id")
    private String webhookEventId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "review_source")
    private ReviewSource reviewSource = ReviewSource.AZURE_WEBHOOK;
    
    @Column(name = "posted_to_azure")
    private boolean postedToAzure = false;
    
    public enum ReviewSource {
        MANUAL_UPLOAD,
        AZURE_WEBHOOK,
        AZURE_API
    }
} 
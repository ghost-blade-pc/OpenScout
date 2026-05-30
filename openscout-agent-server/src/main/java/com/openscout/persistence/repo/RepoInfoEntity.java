package com.openscout.persistence.repo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("repo_info")
public class RepoInfoEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String owner;
    private String repoName;
    private String fullName;
    private String htmlUrl;
    private String description;
    private String language;
    private Long stars;
    private Long forks;
    private String topicsJson;
    private String license;
    private Long openIssues;
    private String defaultBranch;
    private Integer archived;
    private LocalDateTime pushedAt;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime modifiedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getRepoName() { return repoName; }
    public void setRepoName(String repoName) { this.repoName = repoName; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getHtmlUrl() { return htmlUrl; }
    public void setHtmlUrl(String htmlUrl) { this.htmlUrl = htmlUrl; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public Long getStars() { return stars; }
    public void setStars(Long stars) { this.stars = stars; }

    public Long getForks() { return forks; }
    public void setForks(Long forks) { this.forks = forks; }

    public String getTopicsJson() { return topicsJson; }
    public void setTopicsJson(String topicsJson) { this.topicsJson = topicsJson; }

    public String getLicense() { return license; }
    public void setLicense(String license) { this.license = license; }

    public Long getOpenIssues() { return openIssues; }
    public void setOpenIssues(Long openIssues) { this.openIssues = openIssues; }

    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }

    public Integer getArchived() { return archived; }
    public void setArchived(Integer archived) { this.archived = archived; }

    public LocalDateTime getPushedAt() { return pushedAt; }
    public void setPushedAt(LocalDateTime pushedAt) { this.pushedAt = pushedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(LocalDateTime modifiedAt) { this.modifiedAt = modifiedAt; }
}

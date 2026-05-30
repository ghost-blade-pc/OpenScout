package com.openscout.persistence.analysis;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("repo_analysis")
public class RepoAnalysisEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long repoId;
    private String fullName;
    private String summary;
    private String techStackJson;
    private String learningValue;
    private String difficulty;
    private String moduleSummary;
    private Integer totalScore;
    private String scoreBreakdownJson;
    private String evidenceJson;
    private LocalDateTime analyzedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRepoId() { return repoId; }
    public void setRepoId(Long repoId) { this.repoId = repoId; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getTechStackJson() { return techStackJson; }
    public void setTechStackJson(String techStackJson) { this.techStackJson = techStackJson; }

    public String getLearningValue() { return learningValue; }
    public void setLearningValue(String learningValue) { this.learningValue = learningValue; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public String getModuleSummary() { return moduleSummary; }
    public void setModuleSummary(String moduleSummary) { this.moduleSummary = moduleSummary; }

    public Integer getTotalScore() { return totalScore; }
    public void setTotalScore(Integer totalScore) { this.totalScore = totalScore; }

    public String getScoreBreakdownJson() { return scoreBreakdownJson; }
    public void setScoreBreakdownJson(String scoreBreakdownJson) { this.scoreBreakdownJson = scoreBreakdownJson; }

    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }

    public LocalDateTime getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(LocalDateTime analyzedAt) { this.analyzedAt = analyzedAt; }
}

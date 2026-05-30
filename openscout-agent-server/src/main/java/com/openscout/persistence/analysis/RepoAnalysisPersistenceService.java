package com.openscout.persistence.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.scoring.ProjectScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class RepoAnalysisPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(RepoAnalysisPersistenceService.class);

    private final RepoAnalysisMapper mapper;
    private final ObjectMapper objectMapper;

    public RepoAnalysisPersistenceService(RepoAnalysisMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void saveAnalysis(String fullName, ProjectScore score, String summary) {
        try {
            RepoAnalysisEntity entity = new RepoAnalysisEntity();
            entity.setFullName(fullName);
            entity.setTotalScore(score.totalScore());
            entity.setScoreBreakdownJson(serializeScoreBreakdown(score));
            entity.setEvidenceJson(serializeEvidence(score.evidence()));
            entity.setSummary(truncate(summary, 2000));
            entity.setLearningValue(null);
            mapper.insert(entity);
        } catch (Exception e) {
            log.warn("repo_analysis 保存失败 full_name={}: {}", fullName, e.getMessage());
        }
    }

    private String serializeScoreBreakdown(ProjectScore score) {
        try {
            Map<String, Integer> breakdown = Map.of(
                    "activityScore", score.activityScore(),
                    "docScore", score.docScore(),
                    "matchScore", score.matchScore(),
                    "learningScore", score.learningScore(),
                    "resumeValueScore", score.resumeValueScore()
            );
            return objectMapper.writeValueAsString(breakdown);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String serializeEvidence(java.util.List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

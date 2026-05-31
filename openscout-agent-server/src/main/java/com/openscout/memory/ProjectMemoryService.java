package com.openscout.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.openscout.client.RepoSummary;
import com.openscout.config.OpenScoutProperties;
import com.openscout.persistence.analysis.RepoAnalysisEntity;
import com.openscout.persistence.analysis.RepoAnalysisMapper;
import com.openscout.persistence.repo.RepoInfoEntity;
import com.openscout.persistence.repo.RepoInfoMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringTokenizer;

/**
 * Project Memory 服务：复用 MySQL 中已有的项目画像和分析结果。
 *
 * <p>第一版只使用 MySQL LIKE 关键词检索，不做向量库或全文索引。
 * 所有查询异常静默 fallback 为空结果，不阻塞 ask 主流程。</p>
 */
@Service
public class ProjectMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ProjectMemoryService.class);
    private static final int DEFAULT_MAX_RESULTS = 20;

    private final RepoInfoMapper repoInfoMapper;
    private final RepoAnalysisMapper repoAnalysisMapper;
    private final OpenScoutProperties properties;
    private final ObjectMapper objectMapper;

    public ProjectMemoryService(RepoInfoMapper repoInfoMapper,
                                RepoAnalysisMapper repoAnalysisMapper,
                                OpenScoutProperties properties,
                                ObjectMapper objectMapper) {
        this.repoInfoMapper = repoInfoMapper;
        this.repoAnalysisMapper = repoAnalysisMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 按关键词在 repo_info 表中搜索匹配的项目。
     *
     * @param keyword   搜索关键词（多词时按空白字符拆分做 OR 匹配）
     * @param maxResults 最大返回数
     * @return 匹配的 RepoSummary 列表，异常时返回空列表
     */
    public List<RepoSummary> searchByKeyword(String keyword, int maxResults) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        try {
            List<String> words = tokenize(keyword);
            if (words.isEmpty()) {
                return List.of();
            }
            QueryWrapper<RepoInfoEntity> qw = new QueryWrapper<>();
            qw.and(wrapper -> {
                for (String word : words) {
                    String pattern = "%" + word + "%";
                    wrapper.or(w -> w.like("full_name", pattern)
                            .or().like("description", pattern)
                            .or().like("language", pattern));
                }
            });
            qw.last("LIMIT " + Math.max(1, Math.min(maxResults, 100)));
            List<RepoInfoEntity> entities = repoInfoMapper.selectList(qw);
            return entities.stream()
                    .map(this::toRepoSummary)
                    .toList();
        } catch (Exception e) {
            log.warn("Memory 关键词搜索失败 keyword={}: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    /**
     * 按 keyword 搜索，使用默认最大返回数。
     */
    public List<RepoSummary> searchByKeyword(String keyword) {
        return searchByKeyword(keyword, DEFAULT_MAX_RESULTS);
    }

    /**
     * 获取指定项目的最新分析记录摘要。
     *
     * @param fullName 项目全名（如 "facebook/react"）
     * @return 最新分析记录，无记录或异常时返回 empty
     */
    public Optional<CachedAnalysis> getCachedAnalysis(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return Optional.empty();
        }
        try {
            QueryWrapper<RepoAnalysisEntity> qw = new QueryWrapper<>();
            qw.eq("full_name", fullName);
            qw.orderByDesc("analyzed_at");
            qw.last("LIMIT 1");
            RepoAnalysisEntity entity = repoAnalysisMapper.selectOne(qw);
            if (entity == null) {
                return Optional.empty();
            }
            return Optional.of(toCachedAnalysis(entity));
        } catch (Exception e) {
            log.warn("Memory README 缓存查询失败 fullName={}: {}", fullName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 判断记录时间是否在 freshness TTL 内。
     *
     * @param recordTime 记录的更新时间
     * @return true 表示记录仍然新鲜
     */
    public boolean isFresh(LocalDateTime recordTime) {
        if (recordTime == null) {
            return false;
        }
        return isFresh(recordTime, getFreshnessHours());
    }

    /**
     * 判断记录时间是否在指定 freshness TTL 内。
     */
    public boolean isFresh(LocalDateTime recordTime, int freshnessHours) {
        if (recordTime == null) {
            return false;
        }
        Instant cutoff = Instant.now().minus(freshnessHours, ChronoUnit.HOURS);
        Instant recordInstant = recordTime.atZone(ZoneId.systemDefault()).toInstant();
        return recordInstant.isAfter(cutoff);
    }

    /**
     * 获取配置的 freshness 小时数。
     */
    public int getFreshnessHours() {
        return properties.getMemory().getFreshnessHours();
    }

    /**
     * 判断 Memory 是否启用。
     */
    public boolean isEnabled() {
        return properties.getMemory().isEnabled() && properties.getPersistence().isEnabled();
    }

    // ---- private helpers ----

    private List<String> tokenize(String keyword) {
        List<String> words = new ArrayList<>();
        StringTokenizer tokenizer = new StringTokenizer(keyword);
        while (tokenizer.hasMoreTokens()) {
            String word = tokenizer.nextToken().trim();
            if (word.length() >= 2) {
                words.add(word);
            }
        }
        return words;
    }

    private RepoSummary toRepoSummary(RepoInfoEntity entity) {
        return new RepoSummary(
                entity.getOwner() != null ? entity.getOwner() : "",
                entity.getRepoName() != null ? entity.getRepoName() : "",
                entity.getFullName() != null ? entity.getFullName() : "",
                entity.getDescription(),
                entity.getLanguage(),
                entity.getStars() != null ? entity.getStars() : 0L,
                entity.getForks() != null ? entity.getForks() : 0L,
                parseTopics(entity.getTopicsJson()),
                entity.getLicense(),
                entity.getOpenIssues() != null ? entity.getOpenIssues() : 0L,
                toInstant(entity.getUpdatedAt()),
                toInstant(entity.getPushedAt()),
                0,   // readmeLength — memory 不存储此字段
                false, // hasExamples — 需要重新分析
                false, // hasDocker — 需要重新分析
                "cache"
        );
    }

    private CachedAnalysis toCachedAnalysis(RepoAnalysisEntity entity) {
        return new CachedAnalysis(
                entity.getFullName(),
                entity.getSummary(),
                entity.getTotalScore() != null ? entity.getTotalScore() : 0,
                parseEvidenceList(entity.getEvidenceJson()),
                entity.getAnalyzedAt()
        );
    }

    private List<String> parseTopics(String topicsJson) {
        if (topicsJson == null || topicsJson.isBlank() || "[]".equals(topicsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(topicsJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private List<String> parseEvidenceList(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank() || "[]".equals(evidenceJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(evidenceJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private Instant toInstant(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * 从 repo_analysis 缓存中提取的分析数据。
     */
    public record CachedAnalysis(
            String fullName,
            String summary,
            int totalScore,
            List<String> evidence,
            LocalDateTime analyzedAt
    ) {}
}

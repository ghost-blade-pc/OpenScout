package com.openscout.persistence.repo;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.client.RepoSummary;
import com.openscout.scoring.ProjectScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
public class RepoPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(RepoPersistenceService.class);

    private final RepoInfoMapper repoInfoMapper;
    private final ObjectMapper objectMapper;

    public RepoPersistenceService(RepoInfoMapper repoInfoMapper, ObjectMapper objectMapper) {
        this.repoInfoMapper = repoInfoMapper;
        this.objectMapper = objectMapper;
    }

    public void upsertRepoInfo(RepoSummary repo) {
        try {
            RepoInfoEntity existing = findEntityByFullName(repo.fullName()).orElse(null);
            if (existing != null) {
                updateFromRepo(existing, repo);
                repoInfoMapper.updateById(existing);
            } else {
                repoInfoMapper.insert(toRepoInfoEntity(repo));
            }
        } catch (Exception e) {
            log.warn("repo_info 保存失败 full_name={}: {}", repo.fullName(), e.getMessage());
        }
    }

    private Optional<RepoInfoEntity> findEntityByFullName(String fullName) {
        QueryWrapper<RepoInfoEntity> qw = new QueryWrapper<>();
        qw.eq("full_name", fullName);
        return Optional.ofNullable(repoInfoMapper.selectOne(qw));
    }

    private RepoInfoEntity toRepoInfoEntity(RepoSummary repo) {
        RepoInfoEntity entity = new RepoInfoEntity();
        entity.setOwner(repo.owner());
        entity.setRepoName(repo.repo());
        entity.setFullName(repo.fullName());
        entity.setDescription(repo.description());
        entity.setLanguage(repo.language());
        entity.setStars(repo.stars());
        entity.setForks(repo.forks());
        entity.setTopicsJson(serializeTopics(repo.topics()));
        entity.setLicense(repo.license());
        entity.setOpenIssues(repo.openIssues());
        entity.setPushedAt(toLocalDateTime(repo.pushedAt()));
        entity.setUpdatedAt(toLocalDateTime(repo.updatedAt()));
        return entity;
    }

    private void updateFromRepo(RepoInfoEntity entity, RepoSummary repo) {
        entity.setOwner(repo.owner());
        entity.setRepoName(repo.repo());
        entity.setDescription(repo.description());
        entity.setLanguage(repo.language());
        entity.setStars(repo.stars());
        entity.setForks(repo.forks());
        entity.setTopicsJson(serializeTopics(repo.topics()));
        entity.setLicense(repo.license());
        entity.setOpenIssues(repo.openIssues());
        entity.setPushedAt(toLocalDateTime(repo.pushedAt()));
        entity.setUpdatedAt(toLocalDateTime(repo.updatedAt()));
    }

    private String serializeTopics(java.util.List<String> topics) {
        if (topics == null || topics.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(topics);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private LocalDateTime toLocalDateTime(java.time.Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}

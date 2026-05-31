package com.openscout.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openscout.config.OpenScoutProperties;
import com.openscout.persistence.analysis.RepoAnalysisEntity;
import com.openscout.persistence.analysis.RepoAnalysisMapper;
import com.openscout.persistence.repo.RepoInfoEntity;
import com.openscout.persistence.repo.RepoInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectMemoryServiceTest {

    private OpenScoutProperties properties;
    private ObjectMapper objectMapper;
    private RepoInfoMapper repoInfoMapper;
    private RepoAnalysisMapper repoAnalysisMapper;
    private ProjectMemoryService service;

    @BeforeEach
    void setUp() {
        properties = new OpenScoutProperties();
        properties.getPersistence().setEnabled(true);
        objectMapper = new ObjectMapper();
        repoInfoMapper = mock(RepoInfoMapper.class);
        repoAnalysisMapper = mock(RepoAnalysisMapper.class);
        service = new ProjectMemoryService(repoInfoMapper, repoAnalysisMapper, properties, objectMapper);
    }

    @Test
    void shouldReturnEmptyForNullKeyword() {
        assertThat(service.searchByKeyword(null)).isEmpty();
    }

    @Test
    void shouldReturnEmptyForBlankKeyword() {
        assertThat(service.searchByKeyword("   ")).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenNoMatch() {
        when(repoInfoMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        assertThat(service.searchByKeyword("xyznotexist")).isEmpty();
    }

    @Test
    void shouldReturnMatchesForKeyword() {
        RepoInfoEntity entity = entity("octocat", "Hello-World", "Java");
        when(repoInfoMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(entity));

        var results = service.searchByKeyword("Hello");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).fullName()).isEqualTo("octocat/Hello-World");
        assertThat(results.get(0).language()).isEqualTo("Java");
        assertThat(results.get(0).source()).isEqualTo("cache");
    }

    @Test
    void shouldReturnEmptyCachedAnalysisWhenNoRecord() {
        when(repoAnalysisMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        var result = service.getCachedAnalysis("nonexist/repo");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnCachedAnalysisWhenRecordExists() {
        RepoAnalysisEntity entity = new RepoAnalysisEntity();
        entity.setFullName("facebook/react");
        entity.setSummary("A declarative JS library");
        entity.setTotalScore(85);
        entity.setEvidenceJson("[\"good docs\",\"active\"]");
        entity.setAnalyzedAt(LocalDateTime.now().minusHours(1));
        when(repoAnalysisMapper.selectOne(any(QueryWrapper.class))).thenReturn(entity);

        var result = service.getCachedAnalysis("facebook/react");

        assertThat(result).isPresent();
        assertThat(result.get().fullName()).isEqualTo("facebook/react");
        assertThat(result.get().summary()).isEqualTo("A declarative JS library");
        assertThat(result.get().evidence()).containsExactly("good docs", "active");
    }

    @Test
    void shouldConsiderFreshRecordWithinTtl() {
        assertThat(service.isFresh(LocalDateTime.now().minusHours(1), 24)).isTrue();
        assertThat(service.isFresh(LocalDateTime.now().minusMinutes(30), 1)).isTrue();
    }

    @Test
    void shouldConsiderStaleRecordBeyondTtl() {
        assertThat(service.isFresh(LocalDateTime.now().minusHours(25), 24)).isFalse();
        assertThat(service.isFresh(LocalDateTime.now().minusMinutes(61), 1)).isFalse();
    }

    @Test
    void shouldReturnFalseForNullRecordTime() {
        assertThat(service.isFresh(null)).isFalse();
    }

    @Test
    void shouldReturnEmptyOnDbException() {
        when(repoInfoMapper.selectList(any(QueryWrapper.class)))
                .thenThrow(new RuntimeException("DB connection failed"));

        var results = service.searchByKeyword("react");

        assertThat(results).isEmpty(); // 异常时静默返回空
    }

    @Test
    void shouldReturnEnabledOnlyWhenMemoryAndPersistenceEnabled() {
        assertThat(service.isEnabled()).isTrue();

        properties.getMemory().setEnabled(false);
        assertThat(service.isEnabled()).isFalse();

        properties.getMemory().setEnabled(true);
        properties.getPersistence().setEnabled(false);
        assertThat(service.isEnabled()).isFalse();
    }

    private RepoInfoEntity entity(String owner, String repo, String language) {
        RepoInfoEntity e = new RepoInfoEntity();
        e.setOwner(owner);
        e.setRepoName(repo);
        e.setFullName(owner + "/" + repo);
        e.setDescription("A test project");
        e.setLanguage(language);
        e.setStars(1000L);
        e.setForks(100L);
        e.setTopicsJson("[\"web\",\"framework\"]");
        e.setLicense("MIT");
        e.setOpenIssues(10L);
        e.setUpdatedAt(LocalDateTime.now().minusHours(1));
        e.setPushedAt(LocalDateTime.now().minusHours(2));
        return e;
    }
}

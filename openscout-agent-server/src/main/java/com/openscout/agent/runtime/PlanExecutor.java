package com.openscout.agent.runtime;

import com.openscout.agent.AnswerGenerator;
import com.openscout.agent.GoalInterpreter;
import com.openscout.agent.GoalInterpretation;
import com.openscout.agent.ProjectRecommendation;
import com.openscout.client.CollectorClient;
import com.openscout.client.GitHubApiException;
import com.openscout.client.RateLimitException;
import com.openscout.client.ReadmeResponse;
import com.openscout.client.RepoSummary;
import com.openscout.config.OpenScoutProperties;
import com.openscout.learning.LearningPlanGenerator;
import com.openscout.learning.LearningPlanResponse;
import com.openscout.persistence.analysis.RepoAnalysisPersistenceService;
import com.openscout.persistence.learning.LearningPlanPersistenceService;
import com.openscout.persistence.repo.RepoPersistenceService;
import com.openscout.scoring.ProjectScoreService;
import com.openscout.trace.AgentTrace;
import com.openscout.trace.TraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);
    private static final int MAX_README_FETCH = 5;
    private static final int MAX_LLM_RECS = 5;
    private static final Pattern EXAMPLES_PATTERN = Pattern.compile(
            "(?i)\\b(example|sample|demo|tutorial|quickstart)\\b");

    private final RuleBasedAgentPlanner planner;
    private final CollectorClient collectorClient;
    private final ProjectScoreService scoreService;
    private final TraceService traceService;
    private final OpenScoutProperties properties;
    private final RepoPersistenceService repoPersistenceService;
    private final RepoAnalysisPersistenceService repoAnalysisPersistenceService;
    private final GoalInterpreter goalInterpreter;
    private final AnswerGenerator answerGenerator;
    private final LearningPlanGenerator learningPlanGenerator;
    private final LearningPlanPersistenceService learningPlanPersistenceService;

    public PlanExecutor(RuleBasedAgentPlanner planner,
                        CollectorClient collectorClient,
                        ProjectScoreService scoreService,
                        TraceService traceService,
                        OpenScoutProperties properties,
                        RepoPersistenceService repoPersistenceService,
                        RepoAnalysisPersistenceService repoAnalysisPersistenceService,
                        GoalInterpreter goalInterpreter,
                        AnswerGenerator answerGenerator,
                        LearningPlanGenerator learningPlanGenerator,
                        LearningPlanPersistenceService learningPlanPersistenceService) {
        this.planner = planner;
        this.collectorClient = collectorClient;
        this.scoreService = scoreService;
        this.traceService = traceService;
        this.properties = properties;
        this.repoPersistenceService = repoPersistenceService;
        this.repoAnalysisPersistenceService = repoAnalysisPersistenceService;
        this.goalInterpreter = goalInterpreter;
        this.answerGenerator = answerGenerator;
        this.learningPlanGenerator = learningPlanGenerator;
        this.learningPlanPersistenceService = learningPlanPersistenceService;
    }

    public AgentRuntimeResult execute(String question, AgentTrace trace) {
        AgentPlan plan = planner.plan(question, properties.isMockAgent());
        traceService.recordPlanCreated(trace, plan);
        AgentContext context = new AgentContext(question, plan.getMode());
        for (PlanStep step : plan.getSteps()) {
            executeStep(step, context, trace);
        }
        return new AgentRuntimeResult(
                context.getAnswer(),
                context.getRecommendations(),
                context.getLearningPlan(),
                buildScoreSummary(context.getRecommendations())
        );
    }

    private void executeStep(PlanStep step, AgentContext context, AgentTrace trace) {
        step.setStatus(PlanStepStatus.RUNNING);
        traceService.recordStepStarted(trace, step);
        Instant start = Instant.now();
        try {
            String outputSummary = switch (step.getToolName()) {
                case "interpret_goal" -> interpretGoal(context, trace);
                case "search_repos" -> searchRepos(context, trace);
                case "fetch_readme" -> fetchReadme(context, trace);
                case "score_projects" -> scoreProjects(context);
                case "generate_learning_plan" -> generateLearningPlan(context, trace);
                case "generate_answer" -> generateAnswer(context, trace);
                default -> throw new IllegalStateException("unsupported step: " + step.getToolName());
            };
            step.setStatus(PlanStepStatus.SUCCESS);
            recordFinished(trace, step, outputSummary, null, Duration.between(start, Instant.now()).toMillis());
        } catch (RuntimeException ex) {
            step.setStatus(PlanStepStatus.FAILED);
            recordFinished(trace, step, null, ex.getMessage(), Duration.between(start, Instant.now()).toMillis());
            if (!step.isContinueOnFailure()) {
                throw ex;
            }
        }
    }

    private void recordFinished(AgentTrace trace, PlanStep step, String outputSummary, String errorSummary,
                                long latencyMs) {
        StepObservation observation = new StepObservation(
                step.getStepId(),
                step.getToolName(),
                step.getStatus(),
                outputSummary,
                errorSummary,
                latencyMs
        );
        traceService.recordStepFinished(trace, step, observation);
        traceService.recordObservation(trace, observation);
    }

    private String interpretGoal(AgentContext context, AgentTrace trace) {
        GoalInterpretation interpretation = goalInterpreter.interpret(context.getUserGoal(), trace, traceService);
        context.setInterpretation(interpretation);
        return "keyword=" + interpretation.keyword();
    }

    private String searchRepos(AgentContext context, AgentTrace trace) {
        Instant toolStart = Instant.now();
        List<RepoSummary> repos;
        if (context.getMode() == AgentRuntimeMode.MOCK) {
            repos = collectorClient.fetchMockRepos(context.keyword());
            traceService.recordToolCall(trace, "repo_search_mock",
                    "keyword=" + context.keyword(), "items=" + repos.size(),
                    Duration.between(toolStart, Instant.now()).toMillis());
        } else {
            repos = collectorClient.searchRepos(context.keyword(), 10, "github");
            traceService.recordToolCall(trace, "repo_search_github",
                    "keyword=" + context.keyword(), "items=" + repos.size(),
                    Duration.between(toolStart, Instant.now()).toMillis());
        }
        context.setRepos(repos);
        return "items=" + repos.size();
    }

    private String fetchReadme(AgentContext context, AgentTrace trace) {
        if (context.getMode() != AgentRuntimeMode.REAL) {
            return "skipped mode=" + context.getMode();
        }
        Instant start = Instant.now();
        List<RepoSummary> repos = context.getRepos();
        List<RepoSummary> enriched = new ArrayList<>();
        int targets = Math.min(repos.size(), MAX_README_FETCH);
        int fetched = 0;
        int skipped = 0;
        for (int i = 0; i < targets; i++) {
            EnrichedRepo result = enrichWithReadme(repos.get(i));
            enriched.add(result.repo());
            if (result.fetched()) {
                fetched++;
            } else {
                skipped++;
            }
        }
        if (repos.size() > MAX_README_FETCH) {
            enriched.addAll(repos.subList(MAX_README_FETCH, repos.size()));
        }
        context.setRepos(enriched);
        long latencyMs = Duration.between(start, Instant.now()).toMillis();
        traceService.recordToolCall(trace, "readme_fetch_github",
                "targets=" + targets,
                "fetched=" + fetched + " skipped=" + skipped,
                latencyMs);
        return "targets=" + targets + " fetched=" + fetched + " skipped=" + skipped;
    }

    private EnrichedRepo enrichWithReadme(RepoSummary repo) {
        try {
            ReadmeResponse readme = collectorClient.getReadme(repo.owner(), repo.repo(), "github");
            String readmeText = readme.readme() != null ? readme.readme() : "";
            boolean hasExamples = EXAMPLES_PATTERN.matcher(
                    readmeText.substring(0, Math.min(2000, readmeText.length()))).find();
            boolean hasDocker = repo.topics() != null && repo.topics().stream()
                    .anyMatch(t -> "docker".equalsIgnoreCase(t));
            RepoSummary enriched = new RepoSummary(
                    repo.owner(), repo.repo(), repo.fullName(), repo.description(),
                    repo.language(), repo.stars(), repo.forks(), repo.topics(),
                    repo.license(), repo.openIssues(), repo.updatedAt(), repo.pushedAt(),
                    readme.length(), hasExamples, hasDocker, repo.source()
            );
            return new EnrichedRepo(enriched, readme.length() > 0);
        } catch (GitHubApiException e) {
            log.warn("README fetch skipped for {}: code={} status={}",
                    repo.fullName(), e.getErrorCode(), e.getHttpStatus());
            return new EnrichedRepo(repo, false);
        } catch (RateLimitException e) {
            log.warn("README fetch skipped for {} due to rate limit (retry after {}s)",
                    repo.fullName(), e.getRetryAfterSeconds());
            return new EnrichedRepo(repo, false);
        } catch (Exception e) {
            log.warn("README fetch failed for {}, skipping enrichment: {}", repo.fullName(), e.getMessage());
            return new EnrichedRepo(repo, false);
        }
    }

    private String scoreProjects(AgentContext context) {
        List<ProjectRecommendation> recommendations = context.getRepos().stream()
                .map(repo -> ProjectRecommendation.from(repo, scoreService.score(context.getUserGoal(), repo),
                        context.getUserGoal()))
                .sorted(Comparator.comparing(
                        (ProjectRecommendation item) -> item.score().totalScore()).reversed())
                .toList();
        context.setRecommendations(recommendations);
        persistReposIfEnabled(context.getRepos(), recommendations, context.getUserGoal());
        return "recommendations=" + recommendations.size();
    }

    private String generateLearningPlan(AgentContext context, AgentTrace trace) {
        if (!properties.getLearning().isEnabled()) {
            context.setLearningPlan(null);
            return "enabled=false skipped=true";
        }
        Instant generateStart = Instant.now();
        LearningPlanResponse plan = learningPlanGenerator.generate(context.getUserGoal(), context.getRecommendations());
        long generateLatencyMs = Duration.between(generateStart, Instant.now()).toMillis();
        traceService.recordToolCall(trace, "learning_plan_generate",
                "goal=" + context.getUserGoal(),
                "tasks=" + plan.tasks().size(),
                generateLatencyMs);

        if (!properties.getPersistence().isEnabled() || plan.tasks().isEmpty()) {
            traceService.recordToolCall(trace, "learning_plan_persist",
                    "enabled=" + properties.getPersistence().isEnabled(),
                    "persisted=false", 0);
            context.setLearningPlan(plan);
            return "tasks=" + plan.tasks().size() + " persisted=false";
        }
        Instant persistStart = Instant.now();
        try {
            LearningPlanResponse persisted = learningPlanPersistenceService.savePlan(plan);
            traceService.recordToolCall(trace, "learning_plan_persist",
                    "goalId=" + persisted.goalId(),
                    "persisted=true tasks=" + persisted.tasks().size(),
                    Duration.between(persistStart, Instant.now()).toMillis());
            context.setLearningPlan(persisted);
            return "tasks=" + persisted.tasks().size() + " persisted=true";
        } catch (Exception e) {
            log.warn("learning plan 持久化失败，返回临时计划：{}", e.getMessage());
            traceService.recordToolCall(trace, "learning_plan_persist",
                    "goal=" + context.getUserGoal(),
                    "persisted=false error=" + e.getMessage(),
                    Duration.between(persistStart, Instant.now()).toMillis());
            context.setLearningPlan(plan);
            return "tasks=" + plan.tasks().size() + " persisted=false";
        }
    }

    private String generateAnswer(AgentContext context, AgentTrace trace) {
        List<ProjectRecommendation> topForLlm = limit(context.getRecommendations(), MAX_LLM_RECS);
        String answer = answerGenerator.generate(context.getUserGoal(), topForLlm, trace, traceService);
        context.setAnswer(answer);
        return "answerLength=" + (answer == null ? 0 : answer.length());
    }

    private List<ProjectRecommendation> limit(List<ProjectRecommendation> recommendations, int max) {
        return recommendations.size() > max ? recommendations.subList(0, max) : recommendations;
    }

    private String buildScoreSummary(List<ProjectRecommendation> recommendations) {
        return recommendations.stream()
                .map(item -> item.fullName() + "=" + item.score().totalScore())
                .reduce((left, right) -> left + ", " + right)
                .orElse("no recommendations");
    }

    private void persistReposIfEnabled(List<RepoSummary> repos, List<ProjectRecommendation> recommendations,
                                       String goal) {
        if (!properties.getPersistence().isEnabled()) {
            return;
        }
        Map<String, ProjectRecommendation> byFullName = recommendations.stream()
                .collect(Collectors.toMap(ProjectRecommendation::fullName, Function.identity(), (left, right) -> left));
        for (RepoSummary repo : repos) {
            ProjectRecommendation rec = byFullName.get(repo.fullName());
            if (rec == null) {
                continue;
            }
            try {
                repoPersistenceService.upsertRepoInfo(repo);
                String summary = "目标：" + goal + "；推荐理由：" + rec.reason();
                repoAnalysisPersistenceService.saveAnalysis(repo.fullName(), rec.score(), summary);
            } catch (Exception e) {
                log.warn("repo 持久化失败 full_name={}: {}", repo.fullName(), e.getMessage());
            }
        }
    }

    private record EnrichedRepo(RepoSummary repo, boolean fetched) {
    }
}

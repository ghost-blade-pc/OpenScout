package com.openscout.persistence.learning;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.openscout.learning.LearningPlanResponse;
import com.openscout.learning.LearningTaskResponse;
import com.openscout.learning.LearningTaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class LearningPlanPersistenceService {

    private final LearningGoalMapper goalMapper;
    private final LearningTaskMapper taskMapper;

    public LearningPlanPersistenceService(LearningGoalMapper goalMapper, LearningTaskMapper taskMapper) {
        this.goalMapper = goalMapper;
        this.taskMapper = taskMapper;
    }

    @Transactional
    public LearningPlanResponse savePlan(LearningPlanResponse plan) {
        return savePlan(plan, null);
    }

    @Transactional
    public LearningPlanResponse savePlan(LearningPlanResponse plan, Long userId) {
        LearningGoalEntity goal = new LearningGoalEntity();
        goal.setUserId(userId);
        goal.setGoalText(truncate(plan.goal(), 1000));
        goal.setTargetStack(truncate(plan.targetStack(), 500));
        goal.setDurationDays(plan.durationDays());
        goalMapper.insert(goal);

        List<LearningTaskResponse> persistedTasks = plan.tasks().stream()
                .map(task -> insertTask(goal.getId(), userId, task))
                .toList();
        return plan.withPersistence(goal.getId(), true, persistedTasks);
    }

    public Optional<LearningPlanResponse> findPlan(Long goalId) {
        LearningGoalEntity goal = goalMapper.selectById(goalId);
        if (goal == null) {
            return Optional.empty();
        }
        QueryWrapper<LearningTaskEntity> query = new QueryWrapper<>();
        query.eq("goal_id", goalId).orderByAsc("day_no");
        List<LearningTaskResponse> tasks = taskMapper.selectList(query).stream()
                .sorted(Comparator.comparing(LearningTaskEntity::getDayNo))
                .map(this::toResponse)
                .toList();
        return Optional.of(new LearningPlanResponse(
                goal.getId(),
                goal.getGoalText(),
                goal.getTargetStack(),
                goal.getDurationDays() == null ? 0 : goal.getDurationDays(),
                true,
                tasks
        ));
    }

    @Transactional
    public Optional<LearningTaskResponse> updateTaskStatus(Long taskId, LearningTaskStatus status) {
        LearningTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            return Optional.empty();
        }
        task.setStatus(status.name());
        taskMapper.updateById(task);
        return Optional.of(toResponse(task));
    }

    private LearningTaskResponse insertTask(Long goalId, Long userId, LearningTaskResponse task) {
        LearningTaskEntity entity = new LearningTaskEntity();
        entity.setGoalId(goalId);
        entity.setUserId(userId);
        entity.setDayNo(task.dayNo());
        entity.setTaskTitle(truncate(task.title(), 300));
        entity.setTaskDetail(task.detail());
        entity.setExpectedOutput(task.expectedOutput());
        entity.setStatus(LearningTaskStatus.parse(task.status()).name());
        taskMapper.insert(entity);
        return task.withIdAndStatus(entity.getId(), entity.getStatus());
    }

    private LearningTaskResponse toResponse(LearningTaskEntity entity) {
        return new LearningTaskResponse(
                entity.getId(),
                entity.getDayNo() == null ? 0 : entity.getDayNo(),
                entity.getTaskTitle(),
                entity.getTaskDetail(),
                entity.getExpectedOutput(),
                entity.getStatus()
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

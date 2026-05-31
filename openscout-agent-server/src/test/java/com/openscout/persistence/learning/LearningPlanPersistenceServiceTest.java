package com.openscout.persistence.learning;

import com.openscout.learning.LearningPlanResponse;
import com.openscout.learning.LearningTaskResponse;
import com.openscout.learning.LearningTaskStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearningPlanPersistenceServiceTest {

    private final LearningGoalMapper goalMapper = mock(LearningGoalMapper.class);
    private final LearningTaskMapper taskMapper = mock(LearningTaskMapper.class);
    private final LearningPlanPersistenceService service =
            new LearningPlanPersistenceService(goalMapper, taskMapper);

    @Test
    void shouldSaveGoalAndTasks() {
        doAnswer(invocation -> {
            LearningGoalEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            return 1;
        }).when(goalMapper).insert(any(LearningGoalEntity.class));
        doAnswer(invocation -> {
            LearningTaskEntity entity = invocation.getArgument(0);
            entity.setId(100L + entity.getDayNo());
            return 1;
        }).when(taskMapper).insert(any(LearningTaskEntity.class));

        LearningPlanResponse saved = service.savePlan(new LearningPlanResponse(
                null,
                "learn spring ai",
                "Java",
                7,
                false,
                List.of(
                        new LearningTaskResponse(null, 1, "Day 1", "detail", "output", "TODO"),
                        new LearningTaskResponse(null, 2, "Day 2", "detail", "output", "TODO")
                )
        ));

        assertThat(saved.goalId()).isEqualTo(10L);
        assertThat(saved.persisted()).isTrue();
        assertThat(saved.tasks()).extracting(LearningTaskResponse::id).containsExactly(101L, 102L);
        verify(goalMapper).insert(any(LearningGoalEntity.class));
        verify(taskMapper, times(2)).insert(any(LearningTaskEntity.class));
    }

    @Test
    void shouldFindPlanWithOrderedTasks() {
        LearningGoalEntity goal = new LearningGoalEntity();
        goal.setId(10L);
        goal.setGoalText("learn");
        goal.setTargetStack("Java");
        goal.setDurationDays(7);
        when(goalMapper.selectById(10L)).thenReturn(goal);

        LearningTaskEntity day2 = task(2L, 10L, 2, "Day 2", "DOING");
        LearningTaskEntity day1 = task(1L, 10L, 1, "Day 1", "TODO");
        when(taskMapper.selectList(any())).thenReturn(List.of(day2, day1));

        LearningPlanResponse plan = service.findPlan(10L).orElseThrow();

        assertThat(plan.goal()).isEqualTo("learn");
        assertThat(plan.tasks()).extracting(LearningTaskResponse::dayNo).containsExactly(1, 2);
    }

    @Test
    void shouldUpdateTaskStatus() {
        LearningTaskEntity entity = task(1L, 10L, 1, "Day 1", "TODO");
        when(taskMapper.selectById(1L)).thenReturn(entity);

        LearningTaskResponse updated = service.updateTaskStatus(1L, LearningTaskStatus.DONE).orElseThrow();

        assertThat(updated.status()).isEqualTo("DONE");
        ArgumentCaptor<LearningTaskEntity> captor = ArgumentCaptor.forClass(LearningTaskEntity.class);
        verify(taskMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DONE");
    }

    private LearningTaskEntity task(Long id, Long goalId, int dayNo, String title, String status) {
        LearningTaskEntity entity = new LearningTaskEntity();
        entity.setId(id);
        entity.setGoalId(goalId);
        entity.setDayNo(dayNo);
        entity.setTaskTitle(title);
        entity.setTaskDetail("detail");
        entity.setExpectedOutput("output");
        entity.setStatus(status);
        return entity;
    }
}

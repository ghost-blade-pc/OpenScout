package com.openscout.learning;

import com.openscout.persistence.learning.LearningPlanPersistenceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearningControllerTest {

    private final LearningPlanPersistenceService persistenceService = mock(LearningPlanPersistenceService.class);
    private final LearningController controller = new LearningController(persistenceService);

    @Test
    void shouldReturnGoalWhenFound() {
        LearningPlanResponse plan = new LearningPlanResponse(1L, "goal", "Java", 7, true, List.of());
        when(persistenceService.findPlan(1L)).thenReturn(Optional.of(plan));

        var response = controller.getGoal(1L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(plan);
    }

    @Test
    void shouldReturnNotFoundWhenGoalMissing() {
        when(persistenceService.findPlan(99L)).thenReturn(Optional.empty());

        var response = controller.getGoal(99L);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void shouldUpdateTaskStatus() {
        LearningTaskResponse task = new LearningTaskResponse(2L, 1, "title", "detail", "output", "DONE");
        when(persistenceService.updateTaskStatus(2L, LearningTaskStatus.DONE)).thenReturn(Optional.of(task));

        var response = controller.updateTaskStatus(2L, new UpdateTaskStatusRequest("DONE"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(task);
        verify(persistenceService).updateTaskStatus(2L, LearningTaskStatus.DONE);
    }

    @Test
    void shouldRenderBadRequestForInvalidStatus() {
        var response = controller.handleBadRequest(new IllegalArgumentException("status must be one of TODO"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "status must be one of TODO");
    }
}

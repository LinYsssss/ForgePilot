package com.example.codereview.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.project.ProjectEntity;
import com.example.codereview.project.ProjectRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Negative-first coverage of the shared authorization façade frozen in Phase 0.
 *
 * <p>Both parallel workstreams route object-level checks through this class, so the failure modes
 * matter more than the happy path: a silent pass here would open every endpoint that adopts it.
 */
class ProjectAuthorizationTest {

    private static final long OWNER_ID = 7L;
    private static final long STRANGER_ID = 8L;
    private static final long PROJECT_ID = 42L;

    private ProjectRepository projects;
    private ProjectAuthorization authorization;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        authorization = new ProjectAuthorization(projects);
    }

    @Test
    void ownerMayReadAndWrite() {
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(projectOwnedBy(OWNER_ID)));

        authorization.requireRead(PROJECT_ID, OWNER_ID);
        authorization.requireWrite(PROJECT_ID, OWNER_ID);
    }

    @Test
    void strangerIsRejectedOnRead() {
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(projectOwnedBy(OWNER_ID)));

        assertThatThrownBy(() -> authorization.requireRead(PROJECT_ID, STRANGER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROJECT_FORBIDDEN);
    }

    @Test
    void strangerIsRejectedOnWrite() {
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(projectOwnedBy(OWNER_ID)));

        assertThatThrownBy(() -> authorization.requireWrite(PROJECT_ID, STRANGER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROJECT_FORBIDDEN);
    }

    @Test
    void missingProjectIsNotFoundRatherThanForbidden() {
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorization.requireRead(PROJECT_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROJECT_NOT_FOUND);
    }

    @Test
    void nullIdentifiersAreRejectedWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> authorization.requireRead(null, OWNER_ID))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> authorization.requireRead(PROJECT_ID, null))
                .isInstanceOf(BusinessException.class);

        verify(projects, never()).findById(anyLong());
    }

    @Test
    void rejectionUsesForbiddenStatusSoClientsCanTellItApartFromLogin() {
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(projectOwnedBy(OWNER_ID)));

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class, () -> authorization.requireRead(PROJECT_ID, STRANGER_ID));

        assertThat(ex.getHttpStatus()).isEqualTo(403);
    }

    private ProjectEntity projectOwnedBy(Long ownerId) {
        return new ProjectEntity(ownerId, "demo", "demo project", "main");
    }
}

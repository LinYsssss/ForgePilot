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
import com.example.codereview.member.ProjectMemberEntity;
import com.example.codereview.member.ProjectMemberRepository;
import com.example.codereview.member.ProjectRole;
import com.example.codereview.project.ProjectEntity;
import com.example.codereview.project.ProjectRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Negative-first coverage of the shared authorization façade frozen in Phase 0, evolved in P1a:
 * requireRead accepts any member, requireWrite means LEADER, requireRole is the fine-grained check.
 *
 * <p>Both parallel workstreams route object-level checks through this class, so the failure modes
 * matter more than the happy path: a silent pass here would open every endpoint that adopts it.
 */
class ProjectAuthorizationTest {

    private static final long OWNER_ID = 7L;
    private static final long STRANGER_ID = 8L;
    private static final long DEVELOPER_ID = 9L;
    private static final long REVIEWER_ID = 10L;
    private static final long PROJECT_ID = 42L;

    private ProjectRepository projects;
    private ProjectMemberRepository members;
    private ProjectAuthorization authorization;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        members = mock(ProjectMemberRepository.class);
        authorization = new ProjectAuthorization(projects, members);
    }

    @Test
    void ownerMayReadAndWriteEvenWithoutMembershipRow() {
        // owner 兜底:成员行缺失(存量数据漂移)也不锁死负责人。
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(projectOwnedBy(OWNER_ID)));
        when(members.findByProjectIdAndUserId(anyLong(), anyLong())).thenReturn(Optional.empty());

        authorization.requireRead(PROJECT_ID, OWNER_ID);
        authorization.requireWrite(PROJECT_ID, OWNER_ID);
    }

    @Test
    void memberMayReadButOnlyLeaderMayWrite() {
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(projectOwnedBy(OWNER_ID)));
        when(members.findByProjectIdAndUserId(PROJECT_ID, DEVELOPER_ID))
                .thenReturn(Optional.of(new ProjectMemberEntity(PROJECT_ID, DEVELOPER_ID, ProjectRole.DEVELOPER)));

        authorization.requireRead(PROJECT_ID, DEVELOPER_ID);
        assertThatThrownBy(() -> authorization.requireWrite(PROJECT_ID, DEVELOPER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROJECT_FORBIDDEN);
    }

    @Test
    void requireRoleChecksTheMembersActualRole() {
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(projectOwnedBy(OWNER_ID)));
        when(members.findByProjectIdAndUserId(PROJECT_ID, REVIEWER_ID))
                .thenReturn(Optional.of(new ProjectMemberEntity(PROJECT_ID, REVIEWER_ID, ProjectRole.REVIEWER)));

        authorization.requireRole(PROJECT_ID, REVIEWER_ID, Set.of(ProjectRole.LEADER, ProjectRole.REVIEWER));
        assertThatThrownBy(() -> authorization.requireRole(
                PROJECT_ID, REVIEWER_ID, Set.of(ProjectRole.LEADER, ProjectRole.DEVELOPER)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROJECT_FORBIDDEN);
    }

    @Test
    void strangerIsRejectedOnRead() {
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(projectOwnedBy(OWNER_ID)));
        when(members.findByProjectIdAndUserId(PROJECT_ID, STRANGER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorization.requireRead(PROJECT_ID, STRANGER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROJECT_FORBIDDEN);
    }

    @Test
    void strangerIsRejectedOnWrite() {
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(projectOwnedBy(OWNER_ID)));
        when(members.findByProjectIdAndUserId(PROJECT_ID, STRANGER_ID)).thenReturn(Optional.empty());

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
        when(members.findByProjectIdAndUserId(PROJECT_ID, STRANGER_ID)).thenReturn(Optional.empty());

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class, () -> authorization.requireRead(PROJECT_ID, STRANGER_ID));

        assertThat(ex.getHttpStatus()).isEqualTo(403);
    }

    private ProjectEntity projectOwnedBy(Long ownerId) {
        return new ProjectEntity(ownerId, "demo", "demo project", "main");
    }
}

package com.acme.projects.service;

import org.springframework.stereotype.Service;

@Service
public class ProjectMemberService {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;

    public ProjectMemberService(ProjectRepository projects, ProjectMemberRepository members) {
        this.projects = projects;
        this.members = members;
    }

    public void removeMember(long organizationId, long projectId, long memberId) {
        projects.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        members.deleteByProjectAndMember(projectId, memberId);
    }
}

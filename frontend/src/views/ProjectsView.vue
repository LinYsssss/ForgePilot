<template>
    <div class="panel">
      <div class="panel-head">
        <div><h2>{{ projectForm.projectId ? '编辑项目' : '创建项目' }}</h2><div class="sub">管理你的代码审查项目</div></div>
        <button v-if="projectForm.projectId" class="sm secondary" @click="resetProjectForm">取消编辑</button>
      </div>
      <div class="grid three">
        <label class="field">项目名称<input v-model="projectForm.name" placeholder="mall-order-service" /></label>
        <label class="field">默认分支<input v-model="projectForm.defaultBranch" placeholder="main" /></label>
        <label class="field">描述<input v-model="projectForm.description" placeholder="电商订单服务" /></label>
      </div>
      <div class="actions">
        <button @click="run(saveProject, 'project')" :disabled="busy.project">
          <span v-if="busy.project" class="spinner"></span>{{ projectForm.projectId ? '保存修改' : '创建项目' }}
        </button>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head"><div><h2>我的项目</h2><div class="sub">共 {{ projects.length }} 个</div></div></div>
      <div v-if="busy.projects" class="skeleton"><div class="sk-row" v-for="n in 3" :key="n"></div></div>
      <div v-else-if="!projects.length" class="empty"><div class="ico" aria-hidden="true">▤</div><p>还没有项目</p><p>在上方创建你的第一个项目。</p></div>
      <div v-else class="proj-grid">
        <div v-for="p in projects" :key="p.projectId" class="proj-card" :class="{ active: activeProject && activeProject.projectId === p.projectId }" tabindex="0" @click="selectProject(p)" @keyup.enter="selectProject(p)">
          <div class="meta">
            <span class="status-pill" :class="'st-' + p.status">{{ p.status }}</span>
            <div class="card-actions">
              <button class="secondary sm" title="编辑" @click.stop="editProject(p)">编辑</button>
              <button class="danger sm" title="删除" @click.stop="askDeleteProject(p)">删除</button>
            </div>
          </div>
          <h3>{{ p.name }}</h3>
          <div class="desc">{{ p.description || '暂无描述' }}</div>
          <div class="meta">
            <span class="mono">⎇ {{ p.defaultBranch }}</span>
            <span class="when">{{ fmtDate(p.createdAt) }}</span>
          </div>
        </div>
      </div>
    </div>
</template>

<script setup>
import { fmtDate } from '../utils/format.js'
import { useBusy } from '../composables/useBusy.js'
import { useSession } from '../composables/useSession.js'
import { useProjects } from '../composables/useProjects.js'
import { useWorkspace } from '../composables/useWorkspace.js'

const { busy, run } = useBusy()
const { projects, activeProject } = useSession()
const { projectForm, resetProjectForm, editProject, saveProject, askDeleteProject } = useProjects()
const { selectProject } = useWorkspace()
</script>

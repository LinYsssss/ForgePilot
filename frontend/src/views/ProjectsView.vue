<template>
  <div class="view">
    <el-card class="form-card" shadow="never">
      <template #header>
        <div class="card-head">
          <div><h2>{{ projectForm.projectId ? '编辑项目' : '创建项目' }}</h2><div class="card-sub">管理你的代码审查项目</div></div>
          <el-button v-if="projectForm.projectId" @click="resetProjectForm">取消编辑</el-button>
        </div>
      </template>
      <el-form label-position="top" @submit.prevent>
        <el-row :gutter="16">
          <el-col :xs="24" :sm="8"><el-form-item label="项目名称"><el-input v-model="projectForm.name" placeholder="mall-order-service" /></el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="默认分支"><el-input v-model="projectForm.defaultBranch" placeholder="main" /></el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="描述"><el-input v-model="projectForm.description" placeholder="电商订单服务" /></el-form-item></el-col>
        </el-row>
        <div class="form-actions">
          <el-button type="primary" :loading="busy.project" @click="run(saveProject, 'project')">{{ projectForm.projectId ? '保存修改' : '创建项目' }}</el-button>
        </div>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head"><div><h2>我的项目</h2><div class="card-sub">共 {{ projects.length }} 个</div></div></div>
      </template>
      <el-skeleton v-if="busy.projects" :rows="3" animated />
      <el-empty v-else-if="!projects.length" description="还没有项目"><p class="empty-extra">在上方创建你的第一个项目。</p></el-empty>
      <div v-else class="proj-grid">
        <div v-for="p in projects" :key="p.projectId" class="proj-card" :class="{ active: activeProject && activeProject.projectId === p.projectId }" tabindex="0" @click="selectProject(p)" @keyup.enter="selectProject(p)">
          <div class="meta">
            <span class="status-pill" :class="'st-' + p.status">{{ p.status }}</span>
            <div class="card-actions">
              <el-button size="small" title="编辑" @click.stop="editProject(p)">编辑</el-button>
              <el-button size="small" type="danger" plain title="删除" @click.stop="askDeleteProject(p)">删除</el-button>
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
    </el-card>
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

<style scoped>
/* 本页亮色工作台基线(迁移期 AppShell 仍是 Observatory,基线只挂本视图根) */
.view {
  font-family: var(--rs-font-body);
  color: var(--rs-text);
}

.form-card { margin-bottom: var(--sp-5); }

/* 卡头(与 DashboardView 同构:h2 + 弱化副题;h2 显式定字族,
   躲开 styles.css 元素级 --font-display 泄漏) */
.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); flex-wrap: wrap; }
.card-head h2 { margin: 0; font-family: var(--rs-font-body); font-size: var(--rs-fs-lg); font-weight: 700; color: var(--rs-text); }
.card-sub { margin-top: 3px; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); }

.form-actions { display: flex; gap: var(--sp-3); }

.empty-extra { margin: 0; color: var(--rs-text-dim); font-size: var(--rs-fs-sm); }

/* ---- 项目卡网格:非表格形态,tokens 手铸重定义(styles.css 冻结,
        scoped 同名类特异性天然压过全局,视觉即刻脱离 Observatory) ---- */
.proj-grid { display: grid; gap: var(--sp-4); grid-template-columns: repeat(auto-fill, minmax(272px, 1fr)); }

.proj-card {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-5);
  background: var(--rs-surface);
  border: 1px solid var(--rs-border);
  border-radius: var(--rs-radius-base);
  box-shadow: none;
  color: var(--rs-text);
  cursor: pointer;
  transition: border-color var(--rs-t-base) var(--rs-ease-out), box-shadow var(--rs-t-base) var(--rs-ease-out), transform var(--rs-t-base) var(--rs-ease-out);
}
/* 灭掉 styles.css 泄漏的 Observatory 渐变顶条(styles.css 退役后本条无害) */
.proj-card::after { content: none; }
.proj-card:hover { border-color: var(--el-color-primary-light-5); box-shadow: var(--rs-shadow-md); transform: translateY(-2px); }
/* active 置于 :hover 之后:同特异性下选中态 teal 描边不被悬停态冲掉 */
.proj-card.active { border-color: var(--rs-primary); box-shadow: 0 0 0 1px var(--rs-primary); }
.proj-card:focus-visible { outline: 2px solid var(--rs-primary); outline-offset: 2px; }

.proj-card h3 { margin: 0; font-family: var(--rs-font-body); font-size: var(--rs-fs-md); font-weight: 700; color: var(--rs-text); }
.proj-card .desc { color: var(--rs-text-soft); font-size: var(--rs-fs-sm); min-height: 38px; line-height: 1.5; }
.proj-card .meta { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2); margin-top: var(--sp-1); }
.proj-card .meta .mono { color: var(--rs-text-dim); font-size: var(--rs-fs-sm); font-family: var(--rs-font-mono); }
.proj-card .meta .when { color: var(--rs-text-dim); font-size: var(--rs-fs-xs); font-family: var(--rs-font-mono); }

.proj-card .card-actions { display: flex; gap: var(--sp-1); opacity: 0; transform: translateY(-2px); transition: opacity var(--rs-t-fast), transform var(--rs-t-fast); }
.proj-card:hover .card-actions, .proj-card:focus-within .card-actions { opacity: 1; transform: translateY(0); }
/* 双按钮间距交给 gap;Element 相邻按钮默认 12px margin 归零(作用于组件根,非内部结构) */
.card-actions .el-button + .el-button { margin-left: 0; }

/* ---- 状态 pill:st-* 同名 scoped 重定义为 tokens 配色,分组与 tokens.css
        @layer 工具类一致(styles.css 冻结期间由本段供色,收尾后 @layer 接管)。
        padding 4px 11px → 3px 10px:补入 1px 描边后外框尺寸不变。 ---- */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: var(--rs-radius-round);
  font-size: var(--rs-fs-xs);
  font-weight: 700;
  white-space: nowrap;
  line-height: 1.5;
}
.status-pill::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }

.st-SUCCESS, .st-INDEXED, .st-ACTIVE, .st-CONSUMED, .st-PUBLISHED, .st-PASSED, .st-OPEN, .st-MERGED {
  color: var(--rs-risk-low-text);
  background: var(--rs-risk-low-bg);
  border-color: var(--rs-risk-low-border);
}
.st-RUNNING, .st-PENDING, .st-WAIVED {
  color: var(--rs-risk-medium-text);
  background: var(--rs-risk-medium-bg);
  border-color: var(--rs-risk-medium-border);
}
.st-FAILED, .st-DEAD, .st-ERROR, .st-CHANGES_REQUESTED {
  color: var(--rs-risk-high-text);
  background: var(--rs-risk-high-bg);
  border-color: var(--rs-risk-high-border);
}
.st-CANCELED, .st-CLOSED {
  color: var(--rs-risk-none-text);
  background: var(--rs-risk-none-bg);
  border-color: var(--rs-risk-none-border);
}
</style>

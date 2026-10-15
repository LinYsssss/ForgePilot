import { computed, onMounted, ref, shallowRef } from "vue";
import { useRoute, useRouter, type RouteLocationRaw } from "vue-router";

import { parseId, PROJECT_QUERY_KEY } from "../app/routes";
import { hasProjectRole, listProjects, type Project } from "../features/project/api";
import { apiErrorMessage } from "../lib/http";

/**
 * 项目列表的加载状态。六个页面各自抄过同一段 onMounted + listProjects +
 * 错误处理的样板；这里收拢成一处。列表失败只写 `error`，不抛。
 */
export function useProjectList(options: { immediate?: boolean } = {}) {
  const projects = shallowRef<Project[]>([]);
  // 初值 true：列表在首次加载落定前都算加载中（ProjectsPage 的首屏依赖它）。
  const loading = ref(true);
  const error = ref<string | null>(null);

  async function reload(): Promise<void> {
    loading.value = true;
    try {
      projects.value = await listProjects();
    } catch (failure: unknown) {
      error.value = apiErrorMessage(failure);
    } finally {
      loading.value = false;
    }
  }

  if (options.immediate !== false) {
    onMounted(reload);
  }

  return { projects, loading, error, reload };
}

interface ProjectSelectionOptions {
  /** 切换项目时写回查询参数的目标路由名。 */
  routeName: string;
  /** 限定项目的查询参数键，默认 `project`。 */
  queryKey?: string;
  /**
   * URL 缺少 project query 且列表非空时，replace 到列表第一个项目。
   * 默认选中不是一次用户导航，不该在历史里留一格；query 有值时条件不成立，
   * 因此不会覆盖用户已经做出的选择。默认关闭，当前只有工作台启用
   * （`workspace.spec.ts` 锁的行为）。
   */
  autoSelectFirst?: boolean;
}

/**
 * 页面级的项目选择：项目列表、由路由查询参数派生的选中项目、切换导航。
 * 每页一份实例，不跨页面缓存；页面自己的数据加载与错误展示仍归页面。
 * 页面的数据错误沿用返回的 `error` ref 写入——各页原本就与列表失败共用
 * 同一个错误 ref，模板因此不变。
 */
export function useProjectSelection(options: ProjectSelectionOptions) {
  const queryKey = options.queryKey ?? PROJECT_QUERY_KEY;
  const route = useRoute();
  const router = useRouter();
  const { projects, loading, error, reload } = useProjectList({ immediate: false });

  const selectedProjectId = computed(() => parseId(route.query[queryKey]));
  const selectedProject = computed(
    () => projects.value.find((project) => project.id === selectedProjectId.value) ?? null,
  );
  const isLeader = computed(() => hasProjectRole(selectedProject.value, "LEADER"));

  function target(projectId: number): RouteLocationRaw {
    return { name: options.routeName, query: { [queryKey]: String(projectId) } };
  }

  /** 用户切换项目：push 一条新历史（与各页原实现一致）。null 为无操作。 */
  function select(projectId: number | null): void {
    if (projectId !== null) {
      void router.push(target(projectId));
    }
  }

  onMounted(async () => {
    await reload();
    if (
      options.autoSelectFirst === true &&
      error.value === null &&
      selectedProjectId.value === null &&
      projects.value.length > 0
    ) {
      await router.replace(target(projects.value[0].id));
    }
  });

  return {
    projects,
    selectedProjectId,
    selectedProject,
    isLeader,
    loading,
    error,
    select,
    reload,
  };
}

import { reactive, ref } from 'vue'

// 全局会话状态(单例):登录态、当前用户、项目列表与当前选中项目。
// 这几个原子被 App.vue 及后续拆出的各 tab 组件共享,故上移到共享 store。
// 解构使用不改变响应性:ref 的 .value 赋值、reactive 的 Object.assign 都作用于同一实例。
const authenticated = ref(false)
const me = reactive({ userId: null, username: '', nickname: '', role: '' })
const projects = ref([])
const activeProject = ref(null)

export function useSession() {
  return { authenticated, me, projects, activeProject }
}

// 轻量导航接口:router 启动时注册实现;composable 通过它读当前路由/跳转,
// 不直接 import router(那会连带整棵 .vue 视图树,让 node 环境的行为测试无法加载)。
// 注意 name()/query() 的实现内部读取 router.currentRoute(一个 ref),
// 因此在 watch/computed 的 getter 里调用它们仍然保持响应式追踪。
let impl = {
  name: () => 'dashboard',
  query: () => ({}),
  push: () => {},
}

export function registerNav(fns) { impl = { ...impl, ...fns } }

export const nav = {
  name: () => impl.name(),
  query: () => impl.query(),
  push: to => impl.push(to),
}

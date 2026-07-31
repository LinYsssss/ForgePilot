import { reactive } from 'vue'
import { ApiError } from '../api/client'
import { useToast } from './useToast'

// 共享 busy 状态(单例):模板里大量 `busy.xxx` 判断,拆视图后仍指向同一实例。
const busy = reactive({})
const { toastMsg } = useToast()

// 统一的异步动作包装:错误弹 toast;401 由 client 的全局失效处理,这里静默短路。
async function run(action, key) {
  if (key) busy[key] = true
  try {
    await action()
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return
    toastMsg(error?.message || '操作失败', 'error')
  } finally {
    if (key) busy[key] = false
  }
}

export function useBusy() {
  return { busy, run }
}

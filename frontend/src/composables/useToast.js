import { reactive } from 'vue'

// 轻量 toast(单例)。行为与原 App.vue 内实现一致:3.2s 后自动清除。
const toast = reactive({ text: '', type: '' })

function toastMsg(text, type = '') {
  toast.text = text
  toast.type = type
  setTimeout(() => (toast.text = ''), 3200)
}

export function useToast() {
  return { toast, toastMsg }
}

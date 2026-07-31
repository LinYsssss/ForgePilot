import { ref } from 'vue'
import { useBusy } from './useBusy.js'

// 确认模态(单例):{ title, body, confirmLabel?, onConfirm }。
const confirmModal = ref(null)
const { busy, run } = useBusy()

function ask(modal) { confirmModal.value = modal }
function dismiss() { confirmModal.value = null }

async function confirmAction() {
  if (!confirmModal.value) return
  busy.confirm = true
  try {
    await confirmModal.value.onConfirm()
    confirmModal.value = null
  } finally { busy.confirm = false }
}

export function useConfirm() {
  return { confirmModal, ask, dismiss, confirmAction, run }
}

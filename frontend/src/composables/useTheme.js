import { ref } from 'vue'

// 深/浅色主题(单例状态)。data-theme 挂在 <html>,配合 styles.css 的令牌切换。
const theme = ref(localStorage.getItem('theme') || 'dark')

function applyTheme() {
  document.documentElement.setAttribute('data-theme', theme.value)
}

// 模块首次导入即应用一次(应用启动、首帧前)。
applyTheme()

export function useTheme() {
  function toggleTheme() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
    localStorage.setItem('theme', theme.value)
    applyTheme()
  }
  return { theme, toggleTheme }
}

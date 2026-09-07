import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import '@fontsource-variable/inter'
import App from './App.vue'
import router from './router'
import i18n from './i18n'
import { setUploadAuthRefresher } from '@platform-shared/upload/uploadAuthRefresh'
import { refreshToken } from '@/api/auth'
import './styles/index.scss'

const app = createApp(App)

// 注册所有图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, {
  // 设置弹出层的 z-index 基础值
  zIndex: 3000
})
app.use(i18n)

setUploadAuthRefresher(async () => {
  try {
    await refreshToken()
    return true
  } catch {
    return false
  }
})

app.mount('#app')

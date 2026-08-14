import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from '@/App.vue'

import 'element-plus/dist/index.css'
import '@/styles/global.css'

createApp(App)
  .use(createPinia())
  .use(ElementPlus, { locale: zhCn })
  .mount('#app')

<template>
  <el-config-provider :locale="locale">
    <router-view />
    <HermesMaster v-if="showHermesMaster" />
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import zhTw from 'element-plus/dist/locale/zh-tw.mjs'
import en from 'element-plus/dist/locale/en.mjs'
import HermesMaster from '@/components/hermes-master/HermesMaster.vue'
import { getUser } from '@/api/auth'
import { HERMES_MASTER_ENABLED } from '@/utils/featureFlags'

const { locale: currentLocale } = useI18n()

const localeMap: Record<string, any> = {
  'zh-CN': zhCn,
  'zh-TW': zhTw,
  'en': en
}

const locale = computed(() => localeMap[currentLocale.value] || en)

const route = useRoute()
// 登录后的每个 DW 页面都带着 HM；登录回调与 403 页没有可用的 DW 会话。
// getUser 读 localStorage（非响应式），靠 route.name 这个依赖在每次导航后重算。
const showHermesMaster = computed(() =>
  HERMES_MASTER_ENABLED
  && typeof route.name === 'string'
  && route.name !== 'SsoCallback'
  && route.name !== 'Forbidden'
  && getUser() !== null
)
</script>

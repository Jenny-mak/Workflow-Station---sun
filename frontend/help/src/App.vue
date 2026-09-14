<template>
  <div class="help-shell">
    <aside class="help-aside">
      <router-link class="help-brand" to="/" data-testid="help-brand">
        <img class="help-mark" :src="markUrl" alt="" width="28" height="28" />
        <span>{{ t('app.name') }}</span>
      </router-link>
      <nav class="help-aside-nav" :aria-label="t('app.navAria')">
        <router-link class="help-nav-home" to="/">{{ t('app.home') }}</router-link>
        <HelpNav :nodes="NAV_TREE" :open-ids="openIds" @toggle="toggleGroup" />
      </nav>
    </aside>

    <div class="help-body">
      <header class="help-header">
        <nav class="help-breadcrumb">
          <router-link to="/" class="crumb-home">{{ t('app.name') }}</router-link>
          <template v-if="crumbTitle">
            <span class="crumb-sep">/</span>
            <span class="crumb-current">{{ crumbTitle }}</span>
          </template>
        </nav>
        <div class="help-header-tools">
          <HelpSearch />
          <div class="help-langs">
            <select
              class="help-lang-select"
              data-testid="help-locale-select"
              :aria-label="t('app.langAria')"
              :value="locale"
              @change="onLocaleChange"
            >
              <option v-for="opt in locales" :key="opt.id" :value="opt.id">
                {{ t(opt.labelKey) }}
              </option>
            </select>
          </div>
        </div>
      </header>
      <main class="help-main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import HelpNav from '@/components/HelpNav.vue'
import HelpSearch from '@/components/HelpSearch.vue'
import { GUIDELINES, NAV_TREE, navGroupIdsForArticle } from '@/guidelines'
import { isHelpLocale, persistLocale, type HelpLocale } from '@/i18n'

const { t, locale } = useI18n()
const route = useRoute()
const markUrl = `${import.meta.env.BASE_URL}hermes-mark.svg`

const openIds = reactive(new Set(['dw', 'dw-fu']))

watch(
  () => [route.path, route.hash] as const,
  ([path, hash]) => {
    for (const id of navGroupIdsForArticle(path, hash)) {
      openIds.add(id)
    }
  },
  { immediate: true },
)

const locales: { id: HelpLocale; labelKey: string }[] = [
  { id: 'en', labelKey: 'app.langEn' },
  { id: 'zh-CN', labelKey: 'app.langZhCn' },
  { id: 'zh-TW', labelKey: 'app.langZhTw' },
]

function setLocale(next: HelpLocale): void {
  locale.value = next
  persistLocale(next)
}

function onLocaleChange(event: Event): void {
  const next = (event.target as HTMLSelectElement).value
  if (!isHelpLocale(next)) {
    throw new Error(`Unsupported help locale: ${next}`)
  }
  setLocale(next)
}

function toggleGroup(id: string): void {
  if (openIds.has(id)) openIds.delete(id)
  else openIds.add(id)
}

const crumbTitle = computed(() => {
  const guide = GUIDELINES.find((g) => g.path === route.path)
  return guide ? t(guide.titleKey) : ''
})
</script>

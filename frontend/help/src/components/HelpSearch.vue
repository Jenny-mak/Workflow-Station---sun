<template>
  <div class="help-search" data-testid="help-search">
    <label class="help-search-label">
      <span class="sr-only">{{ t('app.searchAria') }}</span>
      <input
        ref="inputRef"
        v-model="query"
        class="help-search-input"
        type="search"
        :placeholder="t('app.searchPlaceholder')"
        :aria-label="t('app.searchAria')"
        aria-autocomplete="list"
        :aria-expanded="open"
        aria-controls="help-search-results"
        @focus="open = true"
        @keydown.escape.prevent="close"
        @keydown.enter.prevent="goFirst"
      />
    </label>
    <ul
      v-if="open && query.trim()"
      id="help-search-results"
      class="help-search-results"
      role="listbox"
    >
      <li v-if="!hits.length" class="help-search-empty">{{ t('app.searchEmpty') }}</li>
      <li v-for="hit in hits" :key="hit.id">
        <router-link
          class="help-search-hit"
          :to="hit.path"
          :data-testid="`help-search-hit-${hit.id}`"
          @click="close"
        >
          <span class="help-search-hit-title">{{ t(hit.titleKey) }}</span>
          <span class="help-search-hit-summary">{{ t(hit.summaryKey) }}</span>
        </router-link>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { GUIDELINES } from '@/guidelines'
import { filterHelpSearch } from '@/utils/helpSearch'

const { t } = useI18n()
const router = useRouter()
const query = ref('')
const open = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)
const catalog = GUIDELINES.map((guide) => ({
  id: guide.id,
  path: guide.path,
  titleKey: guide.titleKey,
  summaryKey: guide.summaryKey,
}))

const hits = computed(() => filterHelpSearch(catalog, query.value, (key) => String(t(key))))

function close(): void {
  open.value = false
}

function goFirst(): void {
  const first = hits.value[0]
  if (!first) return
  void router.push(first.path)
  query.value = ''
  close()
}

function onDocPointer(event: PointerEvent): void {
  const root = (event.target as Node | null)
  const el = inputRef.value?.closest('.help-search')
  if (el && root && el.contains(root)) return
  close()
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocPointer)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocPointer)
})
</script>

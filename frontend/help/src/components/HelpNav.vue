<template>
  <ul class="help-tree" :class="{ 'is-nested': nested }">
    <li v-for="node in nodes" :key="node.id">
      <template v-if="node.kind === 'group'">
        <button
          type="button"
          class="help-tree-group"
          :aria-expanded="openIds.has(node.id)"
          @click="toggle(node.id)"
        >
          <span class="help-tree-chevron" :class="{ 'is-open': openIds.has(node.id) }" />
          {{ t(node.titleKey) }}
        </button>
        <HelpNav
          v-if="openIds.has(node.id)"
          nested
          :nodes="node.children"
          :open-ids="openIds"
          @toggle="toggle"
        />
      </template>
      <router-link
        v-else-if="node.to"
        class="help-tree-leaf"
        :class="{ 'is-on': isOn(node.to) }"
        :to="node.to"
        :data-testid="`help-nav-${node.id}`"
      >
        {{ t(node.titleKey) }}
      </router-link>
      <span
        v-else
        class="help-tree-leaf is-empty"
        :data-testid="`help-nav-${node.id}`"
        :title="t('nav.noArticleYet')"
        :aria-label="`${t(node.titleKey)}. ${t('nav.noArticleYet')}`"
        aria-disabled="true"
      >{{ t(node.titleKey) }}</span>
    </li>
  </ul>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { NavNode } from '@/guidelines'

defineOptions({ name: 'HelpNav' })

defineProps<{
  nodes: NavNode[]
  nested?: boolean
  openIds: Set<string>
}>()

const emit = defineEmits<{ toggle: [id: string] }>()

const { t } = useI18n()
const route = useRoute()

function toggle(id: string): void {
  emit('toggle', id)
}

function isOn(to: string): boolean {
  const hashIndex = to.indexOf('#')
  const path = hashIndex >= 0 ? to.slice(0, hashIndex) : to
  const hash = hashIndex >= 0 ? to.slice(hashIndex) : ''
  if (route.path !== path) return false
  if (hash) return route.hash === hash
  return true
}
</script>

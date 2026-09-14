<template>
  <div class="help-home" data-testid="help-home">
    <p class="help-kicker">{{ t('app.tagline') }}</p>
    <h1>{{ t('home.title') }}</h1>
    <p class="help-lede">{{ t('home.intro') }}</p>
    <section class="help-portal-block" data-testid="help-by-need">
      <h2>{{ t('home.byNeedTitle') }}</h2>
      <p class="help-portal-hint">{{ t('home.byNeedIntro') }}</p>
      <ul class="help-cards">
        <li v-for="guide in listedGuides" :key="guide.id">
          <router-link
            class="help-card"
            :to="guide.path"
            :data-testid="`help-need-${guide.id}`"
          >
            <h3>{{ t(guide.titleKey) }}</h3>
            <p>{{ t(guide.summaryKey) }}</p>
          </router-link>
        </li>
      </ul>
    </section>
    <section v-for="portal in portals" :key="portal.id" class="help-portal-block">
      <h2>{{ t(portal.titleKey) }}</h2>
      <p class="help-portal-hint">{{ t(portal.hintKey) }}</p>
      <ul v-if="portal.leaves.length" class="help-cards">
        <li v-for="leaf in portal.leaves" :key="leaf.id">
          <router-link
            class="help-card"
            :to="leaf.to"
            :data-testid="`help-card-${leaf.id}`"
          >
            <p class="help-card-menu">{{ t(leaf.titleKey) }}</p>
            <h3>{{ t(summaryTitleKey(leaf.to)) }}</h3>
            <p>{{ t(summaryBodyKey(leaf.to)) }}</p>
          </router-link>
        </li>
      </ul>
      <p v-else class="help-empty">{{ t('home.emptyPortal') }}</p>
    </section>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { GUIDELINES, NAV_TREE, navLeavesWithArticles, type NavGroup } from '@/guidelines'

const { t } = useI18n()

const listedGuides = GUIDELINES.filter((guide) => !guide.omitFromHome)

const portals = NAV_TREE.filter((n): n is NavGroup => n.kind === 'group').map((group) => ({
  id: group.id,
  titleKey: group.titleKey,
  hintKey: `home.hint.${group.id}`,
  leaves: navLeavesWithArticles(group.children).filter((leaf) => !leaf.omitFromHome),
}))

function summaryTitleKey(to: string): string {
  const guide = GUIDELINES.find((g) => g.path === to.split('#')[0])
  if (!guide) {
    throw new Error(`No guideline for home card path: ${to}`)
  }
  return guide.titleKey
}

function summaryBodyKey(to: string): string {
  const guide = GUIDELINES.find((g) => g.path === to.split('#')[0])
  if (!guide) {
    throw new Error(`No guideline for home card path: ${to}`)
  }
  return guide.summaryKey
}
</script>

<template>
  <article class="help-guide" :class="{ 'help-guide--wide': wide }" :data-testid="testId">
    <p v-if="crumbKey" class="help-crumb">{{ t(crumbKey) }}</p>
    <h1>{{ t(pageTitleKey) }}</h1>
    <p class="help-intro">{{ t(introKey) }}</p>
    <ol v-if="flowKeys?.length" class="help-flow" :aria-label="t(flowTitleKey)">
      <li v-for="(key, index) in flowKeys" :key="key">
        <span class="help-flow-n">{{ index + 1 }}</span>
        <span>{{ t(key) }}</span>
      </li>
    </ol>
    <nav
      v-if="jumpLinks?.length"
      class="help-jump"
      :aria-label="t('app.jumpAria')"
      data-testid="help-jump"
    >
      <a v-for="item in jumpLinks" :key="item.anchor" :href="`#${item.anchor}`">{{
        t(item.titleKey)
      }}</a>
    </nav>
    <section v-for="section in sections" :id="section.anchor" :key="section.titleKey">
      <h2>{{ t(section.titleKey) }}</h2>
      <div
        class="help-section-body"
        :class="{ 'help-section-body--split': section.figure && section.figureBeside }"
      >
        <div class="help-section-copy">
          <p v-if="section.bodyKey">{{ t(section.bodyKey) }}</p>
          <p v-for="key in section.bodyKeys" :key="key" class="help-body-more">{{ t(key) }}</p>
          <div v-if="section.intentKey" class="help-howto-card">
            <p class="help-howto-purpose">{{ t(section.intentKey) }}</p>
            <ul class="help-howto-facts">
              <li v-if="section.beforeKey">
                <strong>{{ t('app.howToDefault') }}</strong>
                {{ t(section.beforeKey) }}
              </li>
              <li v-if="section.afterKey">
                <strong>{{ t('app.howToResult') }}</strong>
                {{ t(section.afterKey) }}
              </li>
              <li v-if="section.noteKey">
                <strong>{{ t('app.howToNote') }}</strong>
                {{ t(section.noteKey) }}
              </li>
            </ul>
          </div>
          <ul v-if="section.failKeys?.length" class="help-fail">
            <li v-for="key in section.failKeys" :key="key">{{ t(key) }}</li>
          </ul>
          <ul
            v-if="section.samples?.length && samplesWithCopy(section)"
            :class="sampleListClass(section)"
          >
            <li v-for="sample in section.samples" :key="sample.code">
              <pre v-if="section.sampleLayout === 'block'"><code>{{ sample.code }}</code></pre>
              <code v-else>{{ sample.code }}</code>
              <span class="help-sample-hint">{{ t(sample.hintKey) }}</span>
            </li>
          </ul>
        </div>
        <figure v-if="section.figure" class="help-figure">
          <img
            :src="assetUrl(section.figure.src)"
            :alt="t(section.figure.captionKey)"
          />
          <figcaption>{{ t(section.figure.captionKey) }}</figcaption>
        </figure>
        <ul
          v-if="section.samples?.length && !samplesWithCopy(section)"
          :class="sampleListClass(section)"
        >
          <li v-for="sample in section.samples" :key="sample.code">
            <pre v-if="section.sampleLayout === 'block'"><code>{{ sample.code }}</code></pre>
            <code v-else>{{ sample.code }}</code>
            <span class="help-sample-hint">{{ t(sample.hintKey) }}</span>
          </li>
        </ul>
      </div>
    </section>
    <nav v-if="related?.length" class="help-related" :aria-label="t(relatedTitleKey)">
      <h2>{{ t(relatedTitleKey) }}</h2>
      <ul>
        <li v-for="item in related" :key="item.to">
          <RouterLink :to="item.to">{{ t(item.titleKey) }}</RouterLink>
        </li>
      </ul>
    </nav>
  </article>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'

export interface GuideSample {
  code: string
  hintKey: string
}

export interface GuideFigure {
  src: string
  captionKey: string
}

export interface GuideRelated {
  to: string
  titleKey: string
}

export interface GuideJump {
  anchor: string
  titleKey: string
}

export interface GuideSection {
  titleKey: string
  bodyKey?: string
  bodyKeys?: string[]
  intentKey?: string
  beforeKey?: string
  afterKey?: string
  noteKey?: string
  failKeys?: string[]
  sampleLayout?: 'inline' | 'block'
  figureBeside?: boolean
  anchor?: string
  figure?: GuideFigure
  samples?: GuideSample[]
}

withDefaults(
  defineProps<{
    testId: string
    pageTitleKey: string
    introKey: string
    crumbKey?: string
    flowTitleKey?: string
    flowKeys?: string[]
    jumpLinks?: GuideJump[]
    relatedTitleKey?: string
    related?: GuideRelated[]
    sections: GuideSection[]
    wide?: boolean
  }>(),
  {
    flowTitleKey: 'app.flowAria',
    relatedTitleKey: 'app.relatedTitle',
    wide: false,
  },
)

const { t } = useI18n()

/** Bump when replacing `public/guides/*.png` so browsers do not keep the old file. */
const GUIDE_FIGURE_REV = '20260908-1'

function assetUrl(src: string): string {
  const base = import.meta.env.BASE_URL
  return `${base}${src.replace(/^\//, '')}?v=${GUIDE_FIGURE_REV}`
}

function samplesWithCopy(section: GuideSection): boolean {
  return Boolean(section.figure && section.figureBeside)
}

function sampleListClass(section: GuideSection): string {
  return section.sampleLayout === 'block' ? 'help-samples help-samples--block' : 'help-samples'
}
</script>

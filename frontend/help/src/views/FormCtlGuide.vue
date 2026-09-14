<template>
  <GuideArticle
    :test-id="`${def.id}-guide-page`"
    :page-title-key="def.navTitleKey"
    :intro-key="`${prefix}.intro`"
    :crumb-key="`${prefix}.crumb`"
    :flow-title-key="flowTitleKey"
    :flow-keys="flowKeys"
    :related="related"
    :jump-links="jumpLinks"
    :sections="sections"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import GuideArticle, { type GuideJump, type GuideSection } from '@/components/GuideArticle.vue'
import {
  FORM_CTL_BASIS_CATALOG,
  FORM_CTL_STYLE_CATALOG,
  formCtlByPath,
  formCtlRelated,
} from '@/formCtlBasic'
import { EXTEND_FORM_CONTROLS } from '@/formCtlExtend'

const DEFAULT_FLOW = [
  'formCtl.flow1',
  'formCtl.flow2',
  'formCtl.flow3',
  'formCtl.flow4',
  'formCtl.flow5',
]

const route = useRoute()

const def = computed(() => {
  const found = formCtlByPath(route.path, EXTEND_FORM_CONTROLS)
  if (!found) {
    throw new Error(`No control article for ${route.path}`)
  }
  return found
})

const prefix = computed(() => `formCtl.${def.value.id}`)

const flowTitleKey = computed(() =>
  def.value.group === 'extend' ? 'formCtl.extendFlowTitle' : 'formCtl.flowTitle',
)

const flowKeys = computed(() => def.value.flowKeys ?? DEFAULT_FLOW)

const related = computed(() => formCtlRelated(def.value, EXTEND_FORM_CONTROLS))

const jumpLinks = computed((): GuideJump[] => {
  const links: GuideJump[] = [
    { anchor: 'how', titleKey: 'formCtl.howTitle' },
    { anchor: 'catalog', titleKey: 'formCtl.catalogTitle' },
  ]
  if (def.value.includeStyle !== false) {
    links.push({ anchor: 'style', titleKey: 'formCtl.styleTitle' })
  }
  links.push({ anchor: 'events', titleKey: 'formCtl.eventTitle' })
  return links
})

const sections = computed((): GuideSection[] => {
  const p = `formCtl.${def.value.id}`
  const basis = def.value.includeBasis === false ? [] : FORM_CTL_BASIS_CATALOG
  const how: GuideSection = {
    anchor: 'how',
    titleKey: 'formCtl.howTitle',
    bodyKey: `${p}.howBody`,
  }
  if (def.value.figure) {
    how.figureBeside = true
    how.figure = { src: def.value.figure, captionKey: `${p}.propsFigure` }
  }
  const out: GuideSection[] = [
    {
      titleKey: `${p}.whatTitle`,
      bodyKey: `${p}.whatBody`,
    },
    how,
    {
      anchor: 'catalog',
      titleKey: 'formCtl.catalogTitle',
      bodyKey: def.value.group === 'extend' ? 'formCtl.catalogExtendBody' : 'formCtl.catalogBody',
      sampleLayout: 'block',
      samples: [...basis, ...def.value.catalog],
    },
  ]
  if (def.value.includeStyle !== false) {
    out.push({
      anchor: 'style',
      titleKey: 'formCtl.styleTitle',
      bodyKey: 'formCtl.styleBody',
      sampleLayout: 'block',
      samples: FORM_CTL_STYLE_CATALOG,
    })
  }
  out.push(
    {
      anchor: 'events',
      titleKey: 'formCtl.eventTitle',
      bodyKey: 'formCtl.eventWhen',
      intentKey: `${p}.eventIntent`,
      beforeKey: `${p}.eventBefore`,
      afterKey: `${p}.eventAfter`,
      noteKey: `${p}.eventNote`,
      sampleLayout: 'block',
      samples: def.value.shapeSample
        ? [...def.value.eventSamples, def.value.shapeSample]
        : def.value.eventSamples,
    },
    {
      titleKey: 'formCtl.failTitle',
      failKeys: def.value.failKeys.map((key) => `${p}.${key}`),
    },
  )
  return out
})
</script>

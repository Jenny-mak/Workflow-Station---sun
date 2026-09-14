<template>
  <div
    class="help-code-block"
    data-testid="help-code-block"
  >
    <div class="help-code-toolbar">
      <span class="help-code-lang">{{ langLabel }}</span>
      <GuideCopyButton
        v-if="copyable"
        :text="formatted"
        :copy-aria="t('app.copyCodeAria')"
      />
    </div>
    <pre
      class="help-code-pre"
      tabindex="0"
    ><code><span
      v-for="(token, index) in tokens"
      :key="index"
      :class="['help-tok', `help-tok--${token.type}`]"
    >{{ token.text }}</span></code></pre>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import GuideCopyButton from '@/components/GuideCopyButton.vue'
import {
  formatHelpSnippet,
  isHelpCopyWorth,
  resolveHelpCodeLang,
  tokenizeHelpCode,
  type HelpCodeLang,
} from '@/utils/helpCodeSnippet'

const props = defineProps<{
  code: string
  lang?: HelpCodeLang
}>()

const { t } = useI18n()

const resolvedLang = computed(() => resolveHelpCodeLang(props.code, props.lang))
const formatted = computed(() => formatHelpSnippet(props.code, resolvedLang.value))
const tokens = computed(() => tokenizeHelpCode(formatted.value))
const copyable = computed(() => isHelpCopyWorth(props.code, 'snippet'))
const langLabel = computed(() =>
  resolvedLang.value === 'formula' ? t('app.langFormula') : t('app.langJs'),
)
</script>

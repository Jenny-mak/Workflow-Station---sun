<template>
  <span class="help-code-chip">
    <code>{{ code }}</code>
  </span>
</template>

<script setup lang="ts">
defineProps<{
  code: string
}>()
</script>

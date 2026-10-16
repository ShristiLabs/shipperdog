<script setup lang="ts">
import { useData } from 'vitepress';

// Renders only when the build set themeConfig.snapshotBanner (DOCS_VERSION
// === 'snapshot', PLAN 2.2). Pure config-driven render — no browser APIs —
// so it is SSR-safe.
const { theme } = useData();
</script>

<template>
  <div v-if="theme.snapshotBanner" class="snapshot-banner" role="note">
    Unreleased — documentation for main
  </div>
</template>

<style scoped>
.snapshot-banner {
  padding: 8px 16px;
  text-align: center;
  font-size: 14px;
  font-weight: 500;
  color: var(--vp-button-brand-text);
  background-color: var(--vp-c-brand-1);
}
</style>

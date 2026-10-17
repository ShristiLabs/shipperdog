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
/* A slim prerelease strip (the amber convention for unreleased docs),
   not a brand-colored band — a full-width brand-blue banner read as the
   site's header, which it is not. */
.snapshot-banner {
  padding: 6px 16px;
  text-align: center;
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: #92400e;
  background-color: #fef3c7;
  border-bottom: 1px solid #fcd34d;
}

.dark .snapshot-banner {
  color: #fcd34d;
  background-color: rgba(146, 64, 14, 0.25);
  border-bottom-color: rgba(252, 211, 77, 0.35);
}
</style>

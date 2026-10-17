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
   site's header, which it is not.

   Fixed at the viewport top at the layout-top z-layer: the default
   theme's VPNav is position:fixed at top: var(--vp-layout-top-height)
   and every content region pads by the same variable, so the navbar
   slides BELOW this strip once the variable matches the strip's
   height (config.mts injects it for snapshot builds only). */
.snapshot-banner {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: var(--vp-z-index-layout-top);
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
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

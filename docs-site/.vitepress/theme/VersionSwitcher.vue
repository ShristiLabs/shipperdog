<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue';
import { useData } from 'vitepress';

// Version switcher (PLAN 2.2). Reads versions.json from the gh-pages SITE
// ROOT — the VitePress base minus its version segment — so every subtree
// (snapshot/, v0.1/, ...) reads the same manifest. If that fetch fails
// (local dev server, where public/ is served under the version base only)
// it falls back to a base-relative copy: the dev fixture
// docs-site/public/versions.json. When both fail the component hides.
const { site } = useData();
// site is a ComputedRef — unwrap once; base never changes at runtime.
const base = site.value.base as string;

// '/shipperdog/snapshot/' -> site root '/shipperdog/', version 'snapshot'
const segments = base.split('/').filter(Boolean);
const siteRoot = segments.length >= 2 ? `/${segments.slice(0, -1).join('/')}/` : null;
const currentVersion = siteRoot ? segments[segments.length - 1] : null;

interface VersionEntry {
  id: string;
  label: string;
}
const versions = ref<VersionEntry[] | null>(null);
const open = ref(false);
const root = ref<HTMLElement | null>(null);
const currentLabel = computed(
  () => versions.value?.find((v) => v.id === currentVersion)?.label ?? currentVersion ?? '',
);

let disposed = false;
onMounted(async () => {
  if (!siteRoot || !currentVersion) return;
  for (const url of [`${siteRoot}versions.json`, `${base}versions.json`]) {
    try {
      const res = await fetch(url);
      if (!res.ok) continue;
      const manifest = await res.json();
      if (!Array.isArray(manifest.versions)) continue;
      if (!disposed) versions.value = manifest.versions;
      return;
    } catch {
      // try next candidate
    }
  }
});

const onDocClick = (e: MouseEvent) => {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false;
};
const onKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Escape') open.value = false;
};
onMounted(() => {
  document.addEventListener('click', onDocClick);
  document.addEventListener('keydown', onKeydown);
});
onBeforeUnmount(() => {
  disposed = true;
  document.removeEventListener('click', onDocClick);
  document.removeEventListener('keydown', onKeydown);
});

// Map the current page onto the target version; probe the mapped URL and
// fall back to that version's home when it does not exist there. Runs in
// the click handler: window access must stay client-side, never SSR.
const switching = ref(false);
async function switchTo(id: string) {
  if (id === currentVersion || switching.value || !siteRoot) return;
  switching.value = true;
  const path = window.location.pathname;
  const rest = path.startsWith(base) ? path.slice(base.length - 1) : '/';
  const mapped = `${siteRoot}${id}${rest}`;
  try {
    const res = await fetch(mapped, { method: 'HEAD' });
    window.location.assign(res.ok ? mapped : `${siteRoot}${id}/`);
  } catch {
    window.location.assign(`${siteRoot}${id}/`);
  }
}
</script>

<template>
  <div v-if="versions && versions.length > 1" ref="root" class="version-switcher">
    <button
      type="button"
      class="version-button"
      :aria-expanded="open"
      aria-haspopup="listbox"
      @click="open = !open"
    >
      <span>{{ currentLabel }}</span>
      <span class="caret" :class="{ open }" aria-hidden="true">▾</span>
    </button>
    <ul v-if="open" class="version-menu" role="listbox" aria-label="Documentation version">
      <li
        v-for="v in versions"
        :key="v.id"
        role="option"
        :aria-selected="v.id === currentVersion"
        :class="{ active: v.id === currentVersion }"
        @click="switchTo(v.id)"
      >
        <span>{{ v.label }}</span>
        <span v-if="v.id === currentVersion" class="check" aria-hidden="true">✓</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.version-switcher {
  position: relative;
  margin-left: 8px;
  font-size: 14px;
}

.version-button {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border: 1px solid var(--vp-c-border);
  border-radius: 8px;
  background-color: var(--vp-c-bg);
  color: var(--vp-c-text-1);
  font-size: 14px;
  font-weight: 500;
  line-height: 22px;
  cursor: pointer;
}

.version-button:hover {
  border-color: var(--vp-c-brand-1);
}

.caret {
  font-size: 11px;
  color: var(--vp-c-text-3);
  transition: transform 0.15s;
}

.caret.open {
  transform: rotate(180deg);
}

.version-menu {
  position: absolute;
  right: 0;
  z-index: 21;
  min-width: 12rem;
  margin: 6px 0 0;
  padding: 4px;
  list-style: none;
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
  background-color: var(--vp-c-bg);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.version-menu li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 10px;
  border-radius: 6px;
  color: var(--vp-c-text-1);
  cursor: pointer;
  white-space: nowrap;
}

.version-menu li:hover {
  background-color: var(--vp-c-bg-soft);
}

.version-menu li.active {
  color: var(--vp-c-brand-1);
  font-weight: 600;
}

.check {
  color: var(--vp-c-brand-1);
}
</style>

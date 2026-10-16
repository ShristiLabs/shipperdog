import type { Theme } from 'vitepress';
import DefaultTheme from 'vitepress/theme';
import { h } from 'vue';
import SnapshotBanner from './SnapshotBanner.vue';
import VersionSwitcher from './VersionSwitcher.vue';

export default {
  extends: DefaultTheme,
  Layout: () =>
    h(DefaultTheme.Layout, null, {
      'layout-top': () => h(SnapshotBanner),
      'nav-bar-content-after': () => h(VersionSwitcher),
      'nav-screen-content-after': () => h(VersionSwitcher),
    }),
} satisfies Theme;

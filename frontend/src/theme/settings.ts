/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'auto',
  grayscale: false,
  colourWeakness: false,
  recommendColor: true,
  themeColor: '#065F46',
  otherColor: { info: '#0F766E', success: '#047857', warning: '#D97706', error: '#DC2626' },
  isInfoFollowPrimary: true,
  resetCacheStrategy: 'close',
  layout: { mode: 'vertical', scrollMode: 'content', reverseHorizontalMix: false },
  page: { animate: true, animateMode: 'fade-slide' },
  header: { height: 56, breadcrumb: { visible: false, showIcon: true }, multilingual: { visible: false } },
  tab: { visible: false, cache: true, height: 44, mode: 'chrome' },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 180,
    collapsedWidth: 64,
    mixWidth: 90,
    mixCollapsedWidth: 64,
    mixChildMenuWidth: 200
  },
  footer: { visible: false, fixed: false, height: 48, right: true },
  watermark: { visible: false, text: '考辅智聊 ExamPrep RAG Chat' },
  tokens: {
    light: {
      colors: {
        container: 'rgb(255, 255, 255)',
        layout: 'rgb(250, 247, 240)',
        inverted: 'rgb(15, 41, 34)',
        'base-text': 'rgb(15, 41, 34)'
      },
      boxShadow: {
        header: '0 1px 2px rgba(15, 41, 34, 0.04)',
        sider: '1px 0 2px 0 rgba(15, 41, 34, 0.04)',
        tab: '0 1px 2px rgba(15, 41, 34, 0.04)'
      }
    },
    dark: { colors: { container: 'rgb(20, 24, 22)', layout: 'rgb(14, 18, 16)', 'base-text': 'rgb(224, 224, 224)' } }
  }
};

/**
 * Override theme settings
 *
 * If publish new version, use `overrideThemeSettings` to override certain theme settings
 */
export const overrideThemeSettings: Partial<App.Theme.ThemeSetting> = {};

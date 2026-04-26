<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import { NConfigProvider } from 'naive-ui';
import { loginModuleRecord } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import loginIllustration from '@/assets/imgs/login-illustration.svg';
import PwdLogin from './modules/pwd-login.vue';
import CodeLogin from './modules/code-login.vue';
import Register from './modules/register.vue';
import ResetPwd from './modules/reset-pwd.vue';
import BindWechat from './modules/bind-wechat.vue';

interface Props {
  /** The login module */
  module?: UnionKey.LoginModule;
}

const props = defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();

interface LoginModule {
  label: string;
  component: Component;
}

const moduleMap: Record<UnionKey.LoginModule, LoginModule> = {
  'pwd-login': { label: loginModuleRecord['pwd-login'], component: PwdLogin },
  'code-login': { label: loginModuleRecord['code-login'], component: CodeLogin },
  register: { label: loginModuleRecord.register, component: Register },
  'reset-pwd': { label: loginModuleRecord['reset-pwd'], component: ResetPwd },
  'bind-wechat': { label: loginModuleRecord['bind-wechat'], component: BindWechat }
};

const activeModule = computed(() => moduleMap[props.module || 'pwd-login']);

const pageStyle = computed(() => ({
  backgroundColor: '#FAF7F0',
  backgroundImage: `url(${loginIllustration})`
}));
</script>

<template>
  <NConfigProvider :theme="null" :theme-overrides="themeStore.naiveTheme">
    <div class="login-page login-force-light relative min-h-screen" :style="pageStyle">
      <div class="login-page-overlay"></div>
      <div class="login-shell relative z-4">
        <header class="login-brand-row">
          <div class="login-brand-mark">
            <SystemLogo class="text-34px text-primary" />
            <span class="login-brand-name">{{ $t('system.title') }}</span>
          </div>
          <div class="i-flex-col shrink-0">
            <LangSwitch
              v-if="themeStore.header.multilingual.visible"
              :lang="appStore.locale"
              :lang-options="appStore.localeOptions"
              :show-tooltip="false"
              @change-lang="appStore.changeLocale"
            />
          </div>
        </header>

        <div class="login-layout">
          <section class="login-hero">
            <div class="login-hero-copy">
              <p class="login-eyebrow">Exam prep, rebuilt for focus</p>
              <h1 class="login-slogan">
                <span>聊出</span>
                <span>未来高分</span>
              </h1>
              <p class="login-description">
                用知识库检索、题目理解和对话推理，把复习流程收敛成更清晰的一次次提问与回答。
              </p>
            </div>
          </section>

          <div class="login-card-wrap">
            <NCard :bordered="false" class="login-card relative">
              <section class="login-form-panel relative">
              <header class="flex-y-center justify-end gap-16px md:hidden">
                <div class="i-flex-col shrink-0">
                  <LangSwitch
                    v-if="themeStore.header.multilingual.visible"
                    :lang="appStore.locale"
                    :lang-options="appStore.localeOptions"
                    :show-tooltip="false"
                    @change-lang="appStore.changeLocale"
                  />
                </div>
              </header>
              <main class="pt-6 md:pt-0">
                <div class="flex-y-center gap-10px">
                  <span class="login-accent-bar"></span>
                  <h3 class="text-16px font-600 color-#0f2922 tracking-wide">
                    {{ $t(activeModule.label) }}
                  </h3>
                </div>
                <div class="pt-22px">
                  <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
                    <component :is="activeModule.component" />
                  </Transition>
                </div>
              </main>
              </section>
            </NCard>
          </div>
        </div>
      </div>
    </div>
  </NConfigProvider>
</template>

<style scoped>
.login-page {
  position: relative;
  overflow: hidden;
  background-position: center;
  background-repeat: no-repeat;
  background-size: cover;
  background-color: #f7f2e8;
}

.login-page-overlay {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 14% 18%, rgba(255, 255, 255, 0.7), transparent 26%),
    radial-gradient(circle at 82% 78%, rgba(222, 239, 232, 0.5), transparent 30%),
    linear-gradient(180deg, rgba(247, 242, 232, 0.88) 0%, rgba(247, 242, 232, 0.66) 100%);
  pointer-events: none;
}

.login-shell {
  min-height: 100vh;
  max-width: 1520px;
  margin: 0 auto;
  padding: 28px 40px 36px;
}

.login-brand-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding-left: 30px;
}

.login-brand-mark {
  display: inline-flex;
  align-items: center;
  gap: 12px;
}

.login-brand-name {
  color: #13352d;
  font-size: 23px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.login-layout {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(400px, 500px);
  min-height: calc(100vh - 88px);
  align-items: center;
  gap: clamp(28px, 5vw, 68px);
  padding: 24px 48px 0 0;
}

.login-card-wrap {
  width: min(100%, 500px);
  justify-self: end;
  position: relative;
  overflow: visible;
  z-index: 1;
  transform: translateY(clamp(-22px, -2vw, -10px));
}

.login-card-wrap::before {
  content: '';
  position: absolute;
  left: -72px;
  bottom: -42px;
  width: 240px;
  height: 92px;
  border-radius: 50%;
  background: rgba(59, 155, 127, 0.14);
  box-shadow:
    -36px 16px 52px -20px rgba(59, 155, 127, 0.14),
    -12px 8px 24px -16px rgba(19, 53, 45, 0.12);
  filter: blur(18px);
  transform: rotate(-10deg);
  pointer-events: none;
  z-index: -2;
}

.login-card {
  position: relative;
  z-index: 1;
  width: 100%;
  min-width: 0;
  border: 2px solid rgba(59, 155, 127, 0.92) !important;
  border-radius: 38px !important;
  background: rgba(255, 255, 255, 0.92) !important;
  box-shadow:
    -72px 18px 72px -34px rgba(59, 155, 127, 0.18),
    -28px 8px 22px -18px rgba(59, 155, 127, 0.12),
    0 24px 48px -32px rgba(19, 53, 45, 0.2),
    0 14px 28px -24px rgba(19, 53, 45, 0.18) !important;
  overflow: visible;
}

.login-card::before {
  content: '';
  position: absolute;
  left: -26px;
  top: 38px;
  bottom: 38px;
  width: 34px;
  border-radius: 999px;
  background: rgba(59, 155, 127, 0.08);
  box-shadow:
    -18px 10px 28px -14px rgba(59, 155, 127, 0.14),
    -36px 18px 42px -18px rgba(59, 155, 127, 0.1);
  filter: blur(6px);
  pointer-events: none;
  z-index: -1;
}

.login-card::after {
  content: '';
  position: absolute;
  left: 56px;
  bottom: -18px;
  width: 30px;
  height: 30px;
  background: rgba(255, 255, 255, 0.92);
  border-right: 2px solid rgba(59, 155, 127, 0.92);
  border-bottom: 2px solid rgba(59, 155, 127, 0.92);
  transform: rotate(45deg);
  border-bottom-right-radius: 10px;
}

.login-title {
  color: #0f2922;
  letter-spacing: 0.2px;
}

.login-accent-bar {
  display: inline-block;
  width: 4px;
  height: 16px;
  border-radius: 999px;
  background: #065F46;
}

.login-hero {
  display: flex;
  align-items: center;
  justify-content: flex-start;
}

.login-hero-copy {
  max-width: 640px;
  padding: 0;
  transform: translateX(-30px);
}

.login-eyebrow {
  margin: 0 0 20px;
  color: #2f7f68;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.login-slogan {
  margin: 0;
  color: #10352d;
  font-size: clamp(56px, 7.2vw, 108px);
  line-height: 0.98;
  font-weight: 700;
  letter-spacing: -0.04em;
  font-family: "Iowan Old Style", "Palatino Linotype", "Noto Serif SC", "Songti SC", serif;
}

.login-slogan span {
  display: block;
}

.login-description {
  max-width: 460px;
  margin: 28px 0 0;
  color: rgba(16, 53, 45, 0.72);
  font-size: clamp(16px, 1.5vw, 20px);
  line-height: 1.8;
  font-weight: 400;
}

.login-form-panel {
  padding: 42px 34px 40px;
  color: #0f2922;
}

.login-form-panel,
.login-form-panel :deep(span),
.login-form-panel :deep(.n-form-item-feedback) {
  color: #0f2922;
}

.login-form-panel :deep(.n-button--text) {
  --n-text-color: #065F46 !important;
  --n-text-color-hover: #047857 !important;
  --n-text-color-pressed: #064E3B !important;
  --n-text-color-focus: #047857 !important;
  --n-color: transparent !important;
  --n-color-hover: transparent !important;
  --n-color-pressed: transparent !important;
  --n-color-focus: transparent !important;
  --n-ripple-color: transparent !important;
  background: transparent !important;
  box-shadow: none !important;
}

.login-form-panel :deep(.link-agreement),
.login-form-panel :deep(.link-privacy),
.login-form-panel :deep(.forget-link) {
  --n-text-color: #065F46 !important;
  --n-text-color-hover: #047857 !important;
  --n-text-color-pressed: #064E3B !important;
  --n-text-color-focus: #047857 !important;
  --n-color: transparent !important;
  --n-color-hover: transparent !important;
  --n-color-pressed: transparent !important;
  --n-color-focus: transparent !important;
  font-weight: 600 !important;
  padding: 0 2px !important;
  border-bottom: none;
  border-radius: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  transition: color 0.15s ease, opacity 0.15s ease;
}

.login-form-panel :deep(.link-agreement:hover),
.login-form-panel :deep(.link-privacy:hover),
.login-form-panel :deep(.forget-link:hover) {
  opacity: 0.82;
}

.login-form-panel :deep(.n-input) {
  --n-border-radius: 8px !important;
  --n-color: #ffffff !important;
  --n-color-focus: #ffffff !important;
  --n-border: 1px solid rgba(15, 41, 34, 0.12) !important;
  --n-border-hover: 1px solid rgba(6, 95, 70, 0.4) !important;
  --n-border-focus: 1px solid #065F46 !important;
  --n-text-color: #0f2922 !important;
  --n-placeholder-color: rgba(15, 41, 34, 0.4) !important;
  --n-box-shadow-focus: 0 0 0 3px rgba(6, 95, 70, 0.12) !important;
  transition: box-shadow 0.15s ease;
}

.login-form-panel :deep(.n-input .n-input__prefix) {
  color: #065F46;
  margin-right: 6px;
  font-size: 16px;
}

.login-form-panel :deep(.n-button) {
  --n-border-radius: 8px !important;
  font-weight: 500;
  letter-spacing: 0.2px;
}

.login-form-panel :deep(.n-form-item) {
  margin-bottom: 18px;
}

.login-form-panel :deep(.n-button--primary-type) {
  --n-color: #3B9B7F !important;
  --n-color-hover: #46A88B !important;
  --n-color-pressed: #328A70 !important;
  --n-color-focus: #46A88B !important;
  --n-text-color: #ffffff !important;
  --n-border: 1px solid #3B9B7F !important;
  --n-border-hover: 1px solid #46A88B !important;
  --n-border-focus: 1px solid #46A88B !important;
  --n-border-pressed: 1px solid #328A70 !important;
  box-shadow: 0 1px 2px rgba(59, 155, 127, 0.14);
  transition: background-color 0.15s ease, box-shadow 0.15s ease;
}

.login-form-panel :deep(.n-button--primary-type:hover) {
  box-shadow: 0 2px 6px rgba(59, 155, 127, 0.22);
}

.login-form-panel :deep(.n-button:not(.n-button--primary-type)) {
  --n-color: #EAF5F1 !important;
  --n-color-hover: #DDEFE8 !important;
  --n-color-pressed: #D0E9DF !important;
  --n-color-focus: #DDEFE8 !important;
  --n-text-color: #2F7F68 !important;
  --n-text-color-hover: #2A735D !important;
  --n-border: 1px solid #B7DCCD !important;
  --n-border-hover: 1px solid #9FD1BF !important;
  --n-border-focus: 1px solid #9FD1BF !important;
  --n-border-pressed: 1px solid #8AC4AF !important;
}

.login-form-panel :deep(.n-divider) {
  --n-color: rgba(15, 41, 34, 0.08) !important;
}

.login-form-panel :deep(.n-divider__title) {
  color: #6b7a75 !important;
  font-size: 12px;
}

@media (max-width: 1024px) {
  .login-shell {
    padding: 24px 24px 28px;
  }

  .login-layout {
    grid-template-columns: minmax(240px, 1fr) minmax(360px, 440px);
    gap: 24px;
    padding: 36px 12px 0 8px;
  }

  .login-card-wrap {
    width: min(100%, 440px);
    transform: translateY(-12px);
  }

  .login-card-wrap::before {
    left: -48px;
    bottom: -30px;
    width: 188px;
    height: 72px;
  }

  .login-slogan {
    font-size: clamp(48px, 6vw, 74px);
  }

  .login-description {
    max-width: 400px;
    font-size: 16px;
  }
}

@media (max-width: 768px) {
  .login-shell {
    padding: 18px 14px 22px;
  }

  .login-brand-row {
    align-items: flex-start;
  }

  .login-brand-name {
    font-size: 20px;
  }

  .login-layout {
    grid-template-columns: 1fr;
    justify-items: center;
    min-height: auto;
    gap: 28px;
    padding: 18px 0 0;
  }

  .login-card-wrap {
    width: min(100%, 420px);
    justify-self: stretch;
    transform: none;
  }

  .login-card-wrap::before {
    display: none;
  }

  .login-card {
    width: 100%;
    min-width: 0;
    border-radius: 20px;
  }

  .login-card::after {
    display: none;
  }

  .login-hero {
    width: 100%;
  }

  .login-hero-copy {
    max-width: 100%;
    padding: 0;
  }

  .login-eyebrow {
    margin-bottom: 12px;
    font-size: 11px;
    letter-spacing: 0.14em;
  }

  .login-slogan {
    font-size: clamp(38px, 14vw, 56px);
    line-height: 1.02;
  }

  .login-description {
    max-width: 100%;
    margin-top: 16px;
    font-size: 15px;
    line-height: 1.7;
  }

  .login-form-panel {
    padding: 28px 22px;
  }
}
</style>

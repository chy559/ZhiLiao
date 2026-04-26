<script setup lang="ts">
import { computed, reactive, ref, onBeforeUnmount } from 'vue';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { fetchBindEmail, fetchSendEmailCode } from '@/service/api/auth';

defineOptions({ name: 'ForceBindEmail' });

// 老用户（admin / 67677 这类历史账号）email 为空时必须绑定后才能继续使用系统。
// 展示为一个不可关闭的全局模态框，由 /users/me 返回的 requireEmailBind 控制。

const authStore = useAuthStore();
const { userInfo } = storeToRefs(authStore);

const visible = computed(() => Boolean(userInfo.value.requireEmailBind));

const EMAIL_RE = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

const form = reactive({ email: '', code: '' });

const sending = ref(false);
const countdown = ref(0);
let timer: ReturnType<typeof setInterval> | null = null;

const codeBtnLabel = computed(() => {
  if (countdown.value > 0) return `${countdown.value}s`;
  if (sending.value) return '发送中';
  return '获取验证码';
});

async function handleSendCode() {
  if (countdown.value > 0) return;
  if (!EMAIL_RE.test(form.email)) {
    window.$message?.error('请先输入正确的邮箱');
    return;
  }
  sending.value = true;
  const { error } = await fetchSendEmailCode(form.email, 'bind');
  sending.value = false;
  if (!error) {
    window.$message?.success('验证码已发送');
    countdown.value = 60;
    timer = setInterval(() => {
      countdown.value -= 1;
      if (countdown.value <= 0 && timer) {
        clearInterval(timer);
        timer = null;
      }
    }, 1000);
  }
}

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});

const submitting = ref(false);
async function handleSubmit() {
  if (!EMAIL_RE.test(form.email)) {
    window.$message?.error('邮箱格式不正确');
    return;
  }
  if (!/^\d{6}$/.test(form.code)) {
    window.$message?.error('请输入 6 位数字验证码');
    return;
  }
  submitting.value = true;
  const { error } = await fetchBindEmail(form.email, form.code);
  submitting.value = false;
  if (!error) {
    window.$message?.success('邮箱绑定成功');
    await authStore.getUserInfo();
  }
}

async function handleLogout() {
  await authStore.logout();
}
</script>

<template>
  <NModal
    :show="visible"
    preset="card"
    title="请先绑定邮箱"
    style="width: 440px"
    :mask-closable="false"
    :closable="false"
  >
    <div class="pb-3 text-13px color-#6b7280 leading-relaxed">
      为了账号安全（找回密码、重要通知等），您需要绑定一个邮箱后才能继续使用本系统。
    </div>
    <NForm label-placement="top">
      <NFormItem label="邮箱">
        <NInput v-model:value="form.email" placeholder="请输入常用邮箱" />
      </NFormItem>
      <NFormItem label="邮箱验证码">
        <div class="flex w-full gap-8px">
          <NInput v-model:value="form.code" placeholder="6 位验证码" maxlength="6" />
          <NButton
            :disabled="countdown > 0 || sending"
            :loading="sending"
            style="flex-shrink: 0; min-width: 110px;"
            @click="handleSendCode"
          >
            {{ codeBtnLabel }}
          </NButton>
        </div>
      </NFormItem>
    </NForm>
    <template #footer>
      <div class="flex justify-between">
        <NButton quaternary type="warning" @click="handleLogout">退出登录</NButton>
        <NButton type="primary" :loading="submitting" @click="handleSubmit">绑定邮箱</NButton>
      </div>
    </template>
  </NModal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onBeforeUnmount } from 'vue';
import { useRouterPush } from '@/hooks/common/router';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { $t } from '@/locales';
import { fetchResetPassword, fetchSendEmailCode } from '@/service/api/auth';

defineOptions({
  name: 'ResetPwd'
});

const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();

interface FormModel {
  email: string;
  code: string;
  password: string;
  confirmPassword: string;
}

const model: FormModel = reactive({
  email: '',
  code: '',
  password: '',
  confirmPassword: ''
});

const EMAIL_RE = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

type RuleRecord = Partial<Record<keyof FormModel, App.Global.FormRule[]>>;

const rules = computed<RuleRecord>(() => {
  const { formRules, createConfirmPwdRule } = useFormRules();

  return {
    email: [
      { required: true, message: '请输入邮箱', trigger: 'blur' },
      {
        validator: (_r, v: string) => !v || EMAIL_RE.test(v),
        message: '邮箱格式不正确',
        trigger: 'blur'
      }
    ],
    code: [
      { required: true, message: '请输入邮箱验证码', trigger: 'blur' },
      { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' }
    ],
    password: formRules.pwd,
    confirmPassword: createConfirmPwdRule(model.password)
  };
});

// ====== 验证码发送 + 倒计时 ======
const sending = ref(false);
const countdown = ref(0);
let timer: ReturnType<typeof setInterval> | null = null;

const codeBtnLabel = computed(() => {
  if (countdown.value > 0) return `${countdown.value}s 后重试`;
  if (sending.value) return '发送中...';
  return '获取验证码';
});

async function handleSendCode() {
  if (countdown.value > 0) return;
  if (!model.email || !EMAIL_RE.test(model.email)) {
    window.$message?.error('请先输入正确的邮箱');
    return;
  }
  sending.value = true;
  const { error } = await fetchSendEmailCode(model.email, 'reset');
  sending.value = false;
  if (!error) {
    window.$message?.success('验证码已发送，请查收邮箱');
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
  await validate();
  submitting.value = true;
  const { error } = await fetchResetPassword(model.email, model.code, model.password);
  submitting.value = false;
  if (!error) {
    window.$message?.success('密码重置成功，请用新密码登录');
    toggleLoginModule('pwd-login');
  }
}
</script>

<template>
  <NForm ref="formRef" :model="model" :rules="rules" size="large" :show-label="false" @keyup.enter="handleSubmit">
    <NFormItem path="email">
      <NInput v-model:value="model.email" placeholder="请输入注册时使用的邮箱">
        <template #prefix>
          <icon-ant-design:mail-outlined />
        </template>
      </NInput>
    </NFormItem>
    <NFormItem path="code">
      <div class="flex w-full gap-8px">
        <NInput v-model:value="model.code" placeholder="6位验证码" maxlength="6">
          <template #prefix>
            <icon-ant-design:safety-certificate-outlined />
          </template>
        </NInput>
        <NButton
          :disabled="countdown > 0 || sending"
          :loading="sending"
          style="flex-shrink: 0; min-width: 128px;"
          @click="handleSendCode"
        >
          {{ codeBtnLabel }}
        </NButton>
      </div>
    </NFormItem>
    <NFormItem path="password">
      <NInput
        v-model:value="model.password"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.passwordPlaceholder')"
      >
        <template #prefix>
          <icon-ant-design:key-outlined />
        </template>
      </NInput>
    </NFormItem>
    <NFormItem path="confirmPassword">
      <NInput
        v-model:value="model.confirmPassword"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.confirmPasswordPlaceholder')"
      >
        <template #prefix>
          <icon-ant-design:key-outlined />
        </template>
      </NInput>
    </NFormItem>
    <NSpace vertical :size="18" class="w-full">
      <NButton type="primary" size="large" round block :loading="submitting" @click="handleSubmit">
        {{ $t('common.confirm') }}
      </NButton>
      <NButton size="large" round block @click="toggleLoginModule('pwd-login')">
        {{ $t('page.login.common.back') }}
      </NButton>
    </NSpace>
  </NForm>
</template>

<style scoped></style>

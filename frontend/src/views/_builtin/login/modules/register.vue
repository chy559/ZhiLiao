<script setup lang="ts">
import { ref, reactive, computed, onBeforeUnmount } from 'vue';
import { $t } from '@/locales';
import { fetchRegister, fetchSendEmailCode } from '@/service/api/auth';

defineOptions({
  name: 'Register'
});

const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();
const agreementModalVisible = ref(false);
const privacyModalVisible = ref(false);

interface FormModel {
  username: string;
  email: string;
  emailCode: string;
  password: string;
  confirmPassword: string;
}

const model: FormModel = reactive({
  username: '',
  email: '',
  emailCode: '',
  password: '',
  confirmPassword: ''
});

const EMAIL_RE = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

const rules = computed<Record<keyof FormModel, App.Global.FormRule[]>>(() => {
  const { formRules, createConfirmPwdRule } = useFormRules();

  return {
    username: formRules.userName,
    email: [
      { required: true, message: '请输入邮箱', trigger: 'blur' },
      {
        validator: (_r, v: string) => !v || EMAIL_RE.test(v),
        message: '邮箱格式不正确',
        trigger: 'blur'
      }
    ],
    emailCode: [
      { required: true, message: '请输入邮箱验证码', trigger: 'blur' },
      { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' }
    ],
    password: formRules.pwd,
    confirmPassword: createConfirmPwdRule(model.password)
  };
});

// ====== 发送验证码 + 倒计时 ======
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
  const { error } = await fetchSendEmailCode(model.email, 'register');
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

const loading = ref(false);
async function handleSubmit() {
  await validate();
  loading.value = true;
  const { error } = await fetchRegister(model.username, model.password, model.email, model.emailCode);
  if (!error) {
    window.$message?.success('注册成功');
    toggleLoginModule('pwd-login');
  }
  loading.value = false;
}

function openAgreementModal() {
  agreementModalVisible.value = true;
}

function openPrivacyModal() {
  privacyModalVisible.value = true;
}
</script>

<template>
  <div class="flex-col gap-0">
    <NForm ref="formRef" :model="model" :rules="rules" size="large" :show-label="false" @keyup.enter="handleSubmit">
      <NFormItem path="username">
        <NInput v-model:value="model.username" :placeholder="$t('page.login.common.userNamePlaceholder')">
          <template #prefix>
            <icon-ant-design:user-outlined />
          </template>
        </NInput>
      </NFormItem>
      <NFormItem path="email">
        <NInput v-model:value="model.email" placeholder="请输入常用邮箱">
          <template #prefix>
            <icon-ant-design:mail-outlined />
          </template>
        </NInput>
      </NFormItem>
      <NFormItem path="emailCode">
        <div class="flex w-full gap-8px">
          <NInput v-model:value="model.emailCode" placeholder="6位验证码" maxlength="6">
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
        <NButton type="primary" size="large" round block :loading="loading" @click="handleSubmit">
          {{ $t('page.login.common.register') }}
        </NButton>
        <NButton block @click="toggleLoginModule('pwd-login')">
          {{ $t('page.login.common.back') }}
        </NButton>
      </NSpace>

      <div class="mt-4 text-center">
        注册即代表已阅读并同意我们的
        <NButton text type="primary" class="link-agreement" @click="openAgreementModal">用户协议</NButton>
        和
        <NButton text type="primary" class="link-privacy" @click="openPrivacyModal">隐私政策</NButton>
      </div>
    </NForm>

    <NModal v-model:show="agreementModalVisible" preset="card" title="用户协议" class="max-w-640px">
      <div class="flex-col gap-12px leading-7 text-14px color-#334155">
        <p>欢迎使用考辅智聊。在使用本系统前，请确认您已理解并同意以下内容。</p>
        <p>1. 本系统提供基于知识库检索与模型推理的学习辅助服务，生成内容仅供参考，不构成任何保证。</p>
        <p>2. 您应当妥善保管账号信息，不得以任何方式干扰、破坏平台的正常运行。</p>
        <p>3. 您上传、输入或生成的内容应符合法律法规及平台规范，不得包含违法或侵权信息。</p>
        <p>4. 平台可根据系统维护、功能调整或合规要求更新服务内容与协议条款。</p>
      </div>
    </NModal>

    <NModal v-model:show="privacyModalVisible" preset="card" title="隐私政策" class="max-w-640px">
      <div class="flex-col gap-12px leading-7 text-14px color-#334155">
        <p>我们重视您的个人信息与数据安全，并将按最小必要原则处理相关信息。</p>
        <p>1. 登录、对话、上传文件等数据仅用于身份校验、功能实现、系统优化与安全审计。</p>
        <p>2. 未经您的授权，我们不会将您的个人信息用于与本系统无关的用途。</p>
        <p>3. 对话内容、知识文件及操作记录可能用于故障排查、权限控制与服务质量改进。</p>
        <p>4. 如您对个人信息处理方式有疑问，可联系系统管理员进一步确认。</p>
      </div>
    </NModal>
  </div>
</template>

<style scoped></style>

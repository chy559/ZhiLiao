<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { loginModuleRecord } from '@/constants/app';
import { useAuthStore } from '@/store/modules/auth';
import { useRouterPush } from '@/hooks/common/router';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { $t } from '@/locales';

defineOptions({
  name: 'PwdLogin'
});

const authStore = useAuthStore();
const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();
const agreementModalVisible = ref(false);
const privacyModalVisible = ref(false);

interface FormModel {
  userName: string;
  password: string;
}

const model: FormModel = reactive({
  userName: '',
  password: ''
});

const rules = computed<Record<keyof FormModel, App.Global.FormRule[]>>(() => {
  // inside computed to make locale reactive, if not apply i18n, you can define it without computed
  const { formRules } = useFormRules();

  return {
    // 允许邮箱或昵称：不强制走用户名的长度规则，仅校验非空即可，后端会再校验格式
    userName: [{ required: true, message: '请输入账号或邮箱', trigger: 'blur' }],
    password: formRules.pwd
  };
});

async function handleSubmit() {
  await validate();
  await authStore.login(model.userName, model.password);
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
      <NFormItem path="userName">
        <NInput v-model:value="model.userName" placeholder="请输入账号或邮箱">
          <template #prefix>
            <icon-ant-design:user-outlined />
          </template>
        </NInput>
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
      <div class="flex-col gap-6">
        <div class="flex justify-end">
          <NButton text type="primary" size="small" class="forget-link" @click="toggleLoginModule('reset-pwd')">
            忘记密码？
          </NButton>
        </div>
        <NButton type="primary" size="large" round block :loading="authStore.loginLoading" @click="handleSubmit">
          {{ $t('page.login.common.login') }}
        </NButton>
        <NButton block @click="toggleLoginModule('register')">
          {{ $t(loginModuleRecord.register) }}
        </NButton>

        <span class="text-center">
          登录即代表已阅读并同意我们的
          <NButton text type="primary" class="link-agreement" @click="openAgreementModal">用户协议</NButton>
          和
          <NButton text type="primary" class="link-privacy" @click="openPrivacyModal">隐私政策</NButton>
        </span>


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

<script setup lang="ts">
import {
  fetchDeleteAccount,
  fetchSchoolsAndColleges,
  fetchUpdatePassword,
  fetchUpdateSchoolCollege,
  fetchUpdateUsername,
  fetchUploadAvatar
} from '@/service/api/auth';
import { fetchGetMajors, fetchUpdateMajor } from '@/service/api/posts';
import { useThemeStore } from '@/store/modules/theme';
import { localStg } from '@/utils/storage';

const authStore = useAuthStore();
const themeStore = useThemeStore();
const { userInfo } = storeToRefs(authStore);
const isDark = computed(() => themeStore.darkMode);

const uploading = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);

function triggerPick() {
  if (uploading.value) return;
  fileInput.value?.click();
}

async function onFilePicked(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  // 重置 input，方便用户连续选同一个文件
  input.value = '';
  if (!file) return;
  if (!/^image\/(jpeg|png|webp|gif)$/.test(file.type)) {
    window.$message?.error('仅支持 jpg / png / webp / gif 格式');
    return;
  }
  if (file.size > 2 * 1024 * 1024) {
    window.$message?.error('头像不能超过 2MB');
    return;
  }
  uploading.value = true;
  const { error } = await fetchUploadAvatar(file);
  if (!error) {
    await authStore.getUserInfo();
    window.$message?.success('头像更新成功');
  }
  uploading.value = false;
}

const tags = ref<Api.OrgTag.Mine>({
  orgTags: [],
  primaryOrg: '',
  orgTagDetails: []
});

const loading = ref(false);
const getOrgTags = async () => {
  loading.value = true;
  const { error, data } = await request<Api.OrgTag.Mine>({
    url: '/users/org-tags'
  });
  if (!error) {
    tags.value = data;
  }
  loading.value = false;
};

// ====== 学校 / 学院 ======
type SchoolItem = { tagId: string; name: string; colleges: { tagId: string; name: string }[] };
const schoolList = ref<SchoolItem[]>([]);
const selectedSchoolTag = ref<string | null>(null);
const selectedCollegeTag = ref<string | null>(null);
const schoolCollegeSubmitting = ref(false);
const schoolCollegeModalVisible = ref(false);

const currentSchoolName = computed(() => {
  const tag = userInfo.value.schoolTag;
  if (!tag) return '';
  return schoolList.value.find(s => s.tagId === tag)?.name || '';
});
const currentCollegeName = computed(() => {
  const sTag = userInfo.value.schoolTag;
  const cTag = userInfo.value.collegeTag;
  if (!sTag || !cTag) return '';
  const school = schoolList.value.find(s => s.tagId === sTag);
  return school?.colleges.find(c => c.tagId === cTag)?.name || '';
});

function openSchoolCollegeModal() {
  syncSchoolCollegeFromUserInfo();
  schoolCollegeModalVisible.value = true;
}

// ====== 专业 ======
type MajorItem = { tagId: string; name: string; description: string | null };
const majorList = ref<MajorItem[]>([]);
const selectedMajorTag = ref<string | null>(null);
const majorSubmitting = ref(false);
const majorModalVisible = ref(false);

const majorOptions = computed(() =>
  majorList.value.map(m => ({ label: m.name, value: m.tagId }))
);

const currentMajorName = computed(() => {
  const tag = userInfo.value.majorTag;
  if (!tag) return '';
  return majorList.value.find(m => m.tagId === tag)?.name || '';
});

async function loadMajors() {
  const { error, data } = await fetchGetMajors();
  if (!error && data) majorList.value = data;
}

function openMajorModal() {
  selectedMajorTag.value = userInfo.value.majorTag || null;
  majorModalVisible.value = true;
}

async function submitMajor() {
  majorSubmitting.value = true;
  const { error } = await fetchUpdateMajor(selectedMajorTag.value || null);
  if (!error) {
    await authStore.getUserInfo();
    await getOrgTags();
    window.$message?.success('已保存');
    majorModalVisible.value = false;
  }
  majorSubmitting.value = false;
}

const schoolOptions = computed(() =>
  schoolList.value.map(s => ({ label: s.name, value: s.tagId }))
);
const collegeOptions = computed(() => {
  const school = schoolList.value.find(s => s.tagId === selectedSchoolTag.value);
  return (school?.colleges ?? []).map(c => ({ label: c.name, value: c.tagId }));
});

function syncSchoolCollegeFromUserInfo() {
  selectedSchoolTag.value = userInfo.value.schoolTag || null;
  selectedCollegeTag.value = userInfo.value.collegeTag || null;
}

async function loadSchoolsAndColleges() {
  const { error, data } = await fetchSchoolsAndColleges();
  if (!error && data) {
    schoolList.value = data;
  }
}

function onSchoolChange(val: string | null) {
  selectedSchoolTag.value = val;
  const school = schoolList.value.find(s => s.tagId === val);
  if (!school || !school.colleges.some(c => c.tagId === selectedCollegeTag.value)) {
    selectedCollegeTag.value = null;
  }
}

async function submitSchoolCollege() {
  schoolCollegeSubmitting.value = true;
  const { error } = await fetchUpdateSchoolCollege(
    selectedSchoolTag.value || null,
    selectedCollegeTag.value || null
  );
  if (!error) {
    await authStore.getUserInfo();
    await getOrgTags();
    window.$message?.success('已保存');
    schoolCollegeModalVisible.value = false;
  }
  schoolCollegeSubmitting.value = false;
}

onMounted(() => {
  getOrgTags();
  loadSchoolsAndColleges();
  loadMajors();
  syncSchoolCollegeFromUserInfo();
});

watch(
  () => [userInfo.value.schoolTag, userInfo.value.collegeTag],
  () => syncSchoolCollegeFromUserInfo()
);

watch(
  () => authStore.userInfo.id,
  (newId, oldId) => {
    if (newId && newId !== oldId) {
      getOrgTags();
    }
  }
);

const visible = ref(false);
const currentTagId = ref('');
const submitLoading = ref(false);
const setPrimaryOrg = async () => {
  submitLoading.value = true;
  const { error } = await request({
    url: '/users/primary-org',
    method: 'PUT',
    data: { primaryOrg: currentTagId.value, userId: userInfo.value.id }
  });
  if (!error) {
    visible.value = false;
    getOrgTags();
  }
  submitLoading.value = false;
};

// ====== 修改昵称 ======
const usernameModalVisible = ref(false);
const usernameForm = reactive({ newUsername: '' });
const usernameSubmitting = ref(false);

function openUsernameModal() {
  usernameForm.newUsername = userInfo.value.username;
  usernameModalVisible.value = true;
}

async function submitUsername() {
  const next = usernameForm.newUsername.trim();
  if (!next) {
    window.$message?.error('昵称不能为空');
    return;
  }
  if (next === userInfo.value.username) {
    window.$message?.warning('新昵称与当前昵称相同');
    return;
  }
  usernameSubmitting.value = true;
  const { error, data } = await fetchUpdateUsername(next);
  if (!error && data) {
    // 服务端已让旧 token 失效；这里替换本地的 token / refreshToken，并刷新用户信息和标签
    localStg.set('token', data.token);
    localStg.set('refreshToken', data.refreshToken);
    authStore.setToken(data.token);
    await authStore.getUserInfo();
    await getOrgTags();
    window.$message?.success('昵称修改成功');
    usernameModalVisible.value = false;
  }
  usernameSubmitting.value = false;
}

// ====== 修改密码 ======
const passwordModalVisible = ref(false);
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });
const passwordSubmitting = ref(false);

function openPasswordModal() {
  passwordForm.oldPassword = '';
  passwordForm.newPassword = '';
  passwordForm.confirmPassword = '';
  passwordModalVisible.value = true;
}

// ====== 注销账号 ======
// 管理员账号不允许注销，页面上直接不渲染该按钮；服务端也会再次拒绝。
const isAdmin = computed(() => userInfo.value.role === 'ADMIN');
const deleteModalVisible = ref(false);
const deleteConfirmText = ref('');
const deleteSubmitting = ref(false);
const DELETE_KEYWORD = '注销';

function openDeleteModal() {
  deleteConfirmText.value = '';
  deleteModalVisible.value = true;
}

async function submitDeleteAccount() {
  if (deleteConfirmText.value.trim() !== DELETE_KEYWORD) {
    window.$message?.error(`请输入"${DELETE_KEYWORD}"以确认`);
    return;
  }
  deleteSubmitting.value = true;
  const { error } = await fetchDeleteAccount();
  deleteSubmitting.value = false;
  if (error) return;
  window.$message?.success('账号已注销');
  deleteModalVisible.value = false;
  // 清本地 token 并跳回登录页
  await authStore.resetStore();
}

async function submitPassword() {
  if (!passwordForm.oldPassword || !passwordForm.newPassword) {
    window.$message?.error('请填写完整');
    return;
  }
  if (passwordForm.newPassword.length < 6) {
    window.$message?.error('新密码至少 6 位');
    return;
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    window.$message?.error('两次输入的新密码不一致');
    return;
  }
  passwordSubmitting.value = true;
  const { error } = await fetchUpdatePassword(passwordForm.oldPassword, passwordForm.newPassword);
  if (!error) {
    window.$message?.success('密码修改成功');
    passwordModalVisible.value = false;
  }
  passwordSubmitting.value = false;
}
</script>

<template>
  <NSpin :show="loading">
    <div class="flex-cc personal-center-page" :class="{ 'is-dark': isDark }">
      <NCard class="min-h-400px min-w-600px w-50vw card-wrapper" :segmented="{ content: true, footer: 'soft' }">
        <template #header>
          <div class="user-header">
            <div class="avatar-trigger" :class="{ 'is-uploading': uploading }" @click="triggerPick">
              <NAvatar v-if="userInfo.avatarUrl" :size="72" :src="userInfo.avatarUrl" round />
              <NAvatar v-else :size="72" round>
                <icon-solar:user-circle-linear class="text-icon-large" />
              </NAvatar>
              <div class="avatar-mask">
                <icon-solar:camera-linear />
                <span>{{ uploading ? '上传中' : '更换头像' }}</span>
              </div>
              <input
                ref="fileInput"
                type="file"
                accept="image/png,image/jpeg,image/webp,image/gif"
                class="hidden"
                @change="onFilePicked"
              />
            </div>
            <div class="user-meta">
              <div class="flex items-center gap-2">
                <span class="username">{{ userInfo.username }}</span>
                <NButton text size="tiny" type="primary" @click="openUsernameModal">
                  <template #icon>
                    <icon-solar:pen-linear />
                  </template>
                  修改
                </NButton>
              </div>
              <span class="subtitle">个人中心</span>
            </div>
            <div class="header-actions">
              <NButton size="small" @click="openPasswordModal">
                <template #icon>
                  <icon-solar:lock-password-linear />
                </template>
                修改密码
              </NButton>
              <NButton v-if="!isAdmin" size="small" type="error" ghost @click="openDeleteModal">
                <template #icon>
                  <icon-solar:trash-bin-trash-linear />
                </template>
                注销账号
              </NButton>
            </div>
          </div>
        </template>
        <NScrollbar class="max-h-60vh">
          <div class="p-4">
            <NCard size="small" embedded :segmented="{ content: true }">
              <template #header>
                <div class="flex items-center justify-between">
                  <div class="text-sm font-600">我的学校 / 学院</div>
                  <NButton size="small" type="primary" ghost @click="openSchoolCollegeModal">
                    <template #icon>
                      <icon-solar:pen-linear />
                    </template>
                    {{ userInfo.schoolTag ? '修改' : '添加' }}
                  </NButton>
                </div>
              </template>
              <div class="info-row">
                <span class="info-label">学校</span>
                <span v-if="currentSchoolName" class="info-value">{{ currentSchoolName }}</span>
                <span v-else class="info-empty">未设置</span>
              </div>
              <div class="info-row">
                <span class="info-label">学院</span>
                <span v-if="currentCollegeName" class="info-value">{{ currentCollegeName }}</span>
                <span v-else class="info-empty">未设置</span>
              </div>
            </NCard>
            <NCard size="small" embedded :segmented="{ content: true }" class="mt-3">
              <template #header>
                <div class="flex items-center justify-between">
                  <div class="text-sm font-600">我的专业</div>
                  <NButton size="small" type="primary" ghost @click="openMajorModal">
                    <template #icon>
                      <icon-solar:pen-linear />
                    </template>
                    {{ userInfo.majorTag ? '修改' : '添加' }}
                  </NButton>
                </div>
              </template>
              <div class="info-row">
                <span class="info-label">专业</span>
                <span v-if="currentMajorName" class="info-value">{{ currentMajorName }}</span>
                <span v-else class="info-empty">未设置</span>
              </div>
            </NCard>
          </div>
        </NScrollbar>
      </NCard>

      <NModal
        v-model:show="visible"
        :loading="submitLoading"
        preset="dialog"
        title="设置主标签"
        content="确定将当前标签设置为主标签吗？"
        positive-text="确认"
        negative-text="取消"
        @positive-click="setPrimaryOrg"
        @negative-click="visible = false"
      />

      <NModal
        v-model:show="schoolCollegeModalVisible"
        preset="card"
        title="修改学校 / 学院"
        style="width: 460px"
        :mask-closable="!schoolCollegeSubmitting"
      >
        <NAlert type="info" :show-icon="true" class="mb-3">
          有关学校及学院正在陆续扩展，欢迎反馈补充。
        </NAlert>
        <NForm label-placement="left" label-width="56" size="small">
          <NFormItem label="学校">
            <NSelect
              :value="selectedSchoolTag"
              :options="schoolOptions"
              placeholder="请选择学校"
              filterable
              clearable
              @update:value="onSchoolChange"
            />
          </NFormItem>
          <NFormItem label="学院">
            <NSelect
              v-model:value="selectedCollegeTag"
              :options="collegeOptions"
              :disabled="!selectedSchoolTag"
              :placeholder="selectedSchoolTag ? '请选择学院' : '请先选择学校'"
              filterable
              clearable
            />
          </NFormItem>
        </NForm>
        <template #footer>
          <div class="flex justify-end gap-2">
            <NButton :disabled="schoolCollegeSubmitting" @click="schoolCollegeModalVisible = false">取消</NButton>
            <NButton type="primary" :loading="schoolCollegeSubmitting" @click="submitSchoolCollege">保存</NButton>
          </div>
        </template>
      </NModal>

      <NModal
        v-model:show="majorModalVisible"
        preset="card"
        title="修改专业"
        style="width: 420px"
        :mask-closable="!majorSubmitting"
      >
        <NAlert type="info" :show-icon="true" class="mb-3">
          专业用于"同专业"话题筛选，跨校通用。
        </NAlert>
        <NForm label-placement="left" label-width="56" size="small">
          <NFormItem label="专业">
            <NSelect
              v-model:value="selectedMajorTag"
              :options="majorOptions"
              placeholder="请选择专业"
              filterable
              clearable
            />
          </NFormItem>
        </NForm>
        <template #footer>
          <div class="flex justify-end gap-2">
            <NButton :disabled="majorSubmitting" @click="majorModalVisible = false">取消</NButton>
            <NButton type="primary" :loading="majorSubmitting" @click="submitMajor">保存</NButton>
          </div>
        </template>
      </NModal>

      <NModal
        v-model:show="usernameModalVisible"
        preset="card"
        title="修改昵称"
        style="width: 420px"
        :mask-closable="!usernameSubmitting"
      >
        <NForm label-placement="top">
          <NFormItem label="新昵称">
            <NInput
              v-model:value="usernameForm.newUsername"
              placeholder="请输入新昵称"
              maxlength="32"
              show-count
              clearable
            />
          </NFormItem>
          <div class="modal-tip">修改后，你的私人空间标签名也会同步更新为「新昵称的私人空间」。</div>
        </NForm>
        <template #footer>
          <div class="flex justify-end gap-2">
            <NButton :disabled="usernameSubmitting" @click="usernameModalVisible = false">取消</NButton>
            <NButton type="primary" :loading="usernameSubmitting" @click="submitUsername">确认修改</NButton>
          </div>
        </template>
      </NModal>

      <NModal
        v-model:show="deleteModalVisible"
        preset="card"
        title="注销账号"
        style="width: 440px"
        :mask-closable="!deleteSubmitting"
      >
        <NAlert type="error" :show-icon="true" class="mb-3">
          此操作不可恢复。你上传的所有文件、对话记录、头像、私人空间都会被永久删除；
          删除完成后该邮箱可被重新注册。
        </NAlert>
        <NForm label-placement="top">
          <NFormItem :label="`请输入&quot;${DELETE_KEYWORD}&quot;以确认`">
            <NInput v-model:value="deleteConfirmText" :placeholder="DELETE_KEYWORD" />
          </NFormItem>
        </NForm>
        <template #footer>
          <div class="flex justify-end gap-2">
            <NButton :disabled="deleteSubmitting" @click="deleteModalVisible = false">取消</NButton>
            <NButton
              type="error"
              :loading="deleteSubmitting"
              :disabled="deleteConfirmText.trim() !== DELETE_KEYWORD"
              @click="submitDeleteAccount"
            >
              永久注销
            </NButton>
          </div>
        </template>
      </NModal>

      <NModal
        v-model:show="passwordModalVisible"
        preset="card"
        title="修改密码"
        style="width: 420px"
        :mask-closable="!passwordSubmitting"
      >
        <NForm label-placement="top">
          <NFormItem label="当前密码">
            <NInput
              v-model:value="passwordForm.oldPassword"
              type="password"
              show-password-on="click"
              placeholder="请输入当前密码"
            />
          </NFormItem>
          <NFormItem label="新密码">
            <NInput
              v-model:value="passwordForm.newPassword"
              type="password"
              show-password-on="click"
              placeholder="至少 6 位"
            />
          </NFormItem>
          <NFormItem label="确认新密码">
            <NInput
              v-model:value="passwordForm.confirmPassword"
              type="password"
              show-password-on="click"
              placeholder="再次输入新密码"
            />
          </NFormItem>
        </NForm>
        <template #footer>
          <div class="flex justify-end gap-2">
            <NButton :disabled="passwordSubmitting" @click="passwordModalVisible = false">取消</NButton>
            <NButton type="primary" :loading="passwordSubmitting" @click="submitPassword">确认修改</NButton>
          </div>
        </template>
      </NModal>
    </div>
  </NSpin>
</template>

<style scoped lang="scss">
:deep(.n-card__content) {
  flex: none m !important;
  height: fit-content;
}

.user-header {
  display: flex;
  align-items: center;
  gap: 14px;
}

.header-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.modal-tip {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: #9ca3af;
}

.info-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
  font-size: 14px;

  .info-label {
    width: 48px;
    color: #9ca3af;
    flex-shrink: 0;
  }

  .info-value {
    color: #1f2937;
    font-weight: 500;
  }

  .info-empty {
    color: #c0c4cc;
  }
}

.user-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;

  .username {
    font-size: 16px;
    font-weight: 600;
    line-height: 1.2;
    color: #1f2937;
  }

  .subtitle {
    font-size: 12px;
    color: #9ca3af;
    line-height: 1.2;
  }
}

.avatar-trigger {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 9999px;
  overflow: hidden;
  cursor: pointer;
  flex-shrink: 0;
  box-shadow: 0 0 0 2px #fff, 0 2px 8px rgb(0 0 0 / 8%);

  .avatar-mask {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    border-radius: 9999px;
    background: rgb(0 0 0 / 50%);
    color: #fff;
    font-size: 11px;
    line-height: 1.2;
    opacity: 0;
    transition: opacity 0.2s ease;
    pointer-events: none;
  }

  &:hover .avatar-mask,
  &.is-uploading .avatar-mask {
    opacity: 1;
  }
}

.personal-center-page.is-dark {
  .info-row {
    .info-label {
      color: #94a3b8;
    }

    .info-value {
      color: #e2e8f0;
    }

    .info-empty {
      color: #64748b;
    }
  }

  .user-meta {
    .username {
      color: #f8fafc;
    }

    .subtitle {
      color: #94a3b8;
    }
  }
}
</style>

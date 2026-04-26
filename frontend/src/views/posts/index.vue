<script setup lang="ts">
import type { DropdownOption } from 'naive-ui';
import type { PostFilter, PostItem } from '@/service/api/posts';
import {
  fetchCreatePost,
  fetchDeletePost,
  fetchListPosts,
  fetchTogglePostLike,
  fetchUpdatePost
} from '@/service/api/posts';
import { useThemeStore } from '@/store/modules/theme';

const authStore = useAuthStore();
const themeStore = useThemeStore();
const { userInfo } = storeToRefs(authStore);
const isDark = computed(() => themeStore.darkMode);

const filter = ref<PostFilter>('all');
const page = ref(0);
const pageSize = 20;
const total = ref(0);
const items = ref<PostItem[]>([]);
const loading = ref(false);
const detailVisible = ref(false);
const currentDetail = ref<PostItem | null>(null);
const currentDetailId = computed(() => currentDetail.value?.id ?? null);

const canFilterSchool = computed(() => Boolean(userInfo.value.schoolTag));
const canFilterMajor = computed(() => Boolean(userInfo.value.majorTag));

const filterLabelMap: Record<PostFilter, string> = {
  all: '全部帖子',
  mine: '我的帖子',
  school: '我的学校',
  major: '同专业'
};

const activeFilterLabel = computed(() => filterLabelMap[filter.value]);

const filterOptions = computed<DropdownOption[]>(() => [
  {
    key: 'all',
    label: '取消筛选'
  },
  {
    key: 'school',
    label: canFilterSchool.value ? '我的学校' : '我的学校（请先设置学校）',
    disabled: !canFilterSchool.value
  },
  {
    key: 'major',
    label: canFilterMajor.value ? '同专业' : '同专业（请先设置专业）',
    disabled: !canFilterMajor.value
  }
]);

async function load() {
  loading.value = true;
  const { error, data } = await fetchListPosts(filter.value, page.value, pageSize);
  if (!error && data) {
    items.value = data.items;
    total.value = data.total;
    // 如果当前正在查看详情，用最新数据同步一次
    if (detailVisible.value && currentDetailId.value !== null) {
      const fresh = data.items.find(i => i.id === currentDetailId.value);
      if (fresh) currentDetail.value = fresh;
    }
  }
  loading.value = false;
}

function switchFilter(f: PostFilter) {
  if (filter.value === f) return;
  if (f === 'school' && !canFilterSchool.value) {
    window.$message?.warning('请先在个人中心设置学校');
    return;
  }
  if (f === 'major' && !canFilterMajor.value) {
    window.$message?.warning('请先在个人中心设置专业');
    return;
  }
  filter.value = f;
  page.value = 0;
  load();
}

function handleSelectFilter(key: string) {
  switchFilter(key as PostFilter);
}

function onPageChange(p: number) {
  page.value = p - 1;
  load();
}

// ====== 详情弹窗 ======
function openDetail(item: PostItem) {
  currentDetail.value = item;
  detailVisible.value = true;
}

function closeDetail() {
  detailVisible.value = false;
  currentDetail.value = null;
}

// ====== 发布 / 编辑 ======
const editorVisible = ref(false);
const editorMode = ref<'create' | 'edit'>('create');
const editingId = ref<number | null>(null);
const form = reactive({ title: '', content: '' });
const submitting = ref(false);

function openCreate() {
  editorMode.value = 'create';
  editingId.value = null;
  form.title = '';
  form.content = '';
  editorVisible.value = true;
}

function openEdit(item: PostItem) {
  editorMode.value = 'edit';
  editingId.value = item.id;
  form.title = item.title;
  form.content = item.content;
  editorVisible.value = true;
}

async function submitEditor() {
  const title = form.title.trim();
  const content = form.content.trim();
  if (!title) {
    window.$message?.error('标题不能为空');
    return;
  }
  if (!content) {
    window.$message?.error('内容不能为空');
    return;
  }
  submitting.value = true;
  if (editorMode.value === 'create') {
    const { error } = await fetchCreatePost(title, content);
    if (!error) {
      window.$message?.success('已发布');
      editorVisible.value = false;
      page.value = 0;
      await load();
    }
  } else if (editingId.value !== null) {
    const { error } = await fetchUpdatePost(editingId.value, title, content);
    if (!error) {
      window.$message?.success('已更新');
      editorVisible.value = false;
      await load();
    }
  }
  submitting.value = false;
}

// ====== 删除 ======
async function onDelete(item: PostItem) {
  const { error } = await fetchDeletePost(item.id);
  if (!error) {
    window.$message?.success('已删除');
    if (detailVisible.value && currentDetail.value?.id === item.id) {
      closeDetail();
    }
    await load();
  }
}

// ====== 点赞 ======
const likeBusy = reactive<Record<number, boolean>>({});

async function onToggleLike(item: PostItem) {
  if (likeBusy[item.id]) return;
  likeBusy[item.id] = true;
  // 乐观更新
  const prevLiked = item.liked;
  const prevCount = item.likeCount;
  item.liked = !prevLiked;
  item.likeCount = prevLiked ? Math.max(0, prevCount - 1) : prevCount + 1;
  syncToList(item);

  const { error, data } = await fetchTogglePostLike(item.id);
  if (error) {
    item.liked = prevLiked;
    item.likeCount = prevCount;
    syncToList(item);
  } else if (data) {
    item.liked = Boolean(data.liked);
    item.likeCount = typeof data.likeCount === 'number' ? data.likeCount : item.likeCount;
    syncToList(item);
  }
  likeBusy[item.id] = false;
}

function syncToList(updated: PostItem) {
  const idx = items.value.findIndex(i => i.id === updated.id);
  if (idx !== -1) {
    items.value[idx].liked = updated.liked;
    items.value[idx].likeCount = updated.likeCount;
  }
}

function formatTime(s: string) {
  if (!s) return '';
  const d = new Date(s);
  return d.toLocaleString('zh-CN', { hour12: false });
}

function previewContent(s: string, max = 80) {
  const str = (s || '').replace(/\s+/g, ' ').trim();
  return str.length > max ? `${str.slice(0, max)}…` : str;
}

onMounted(load);
</script>

<template>
  <div class="posts-page" :class="{ 'is-dark': isDark }">
    <NCard :bordered="false" size="small" class="header-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <NDropdown trigger="click" placement="bottom-start" :options="filterOptions" @select="handleSelectFilter">
            <NButton class="toolbar-btn filter-btn" size="small">
              <template #icon>
                <icon-solar:filter-line-duotone />
              </template>
              筛选
              <span class="filter-pill">{{ activeFilterLabel }}</span>
              <icon-solar:alt-arrow-down-linear class="text-14px color-#94a3b8" />
            </NButton>
          </NDropdown>
          <div v-if="filter !== 'all'" class="filter-hint">
            当前筛选：
            <span>{{ activeFilterLabel }}</span>
          </div>
        </div>
        <div class="toolbar-actions">
          <NButton
            class="toolbar-btn mine-btn"
            :type="filter === 'mine' ? 'primary' : 'default'"
            size="small"
            @click="switchFilter('mine')"
          >
            <template #icon>
              <icon-solar:document-text-linear />
            </template>
            我的帖子
          </NButton>
          <NButton class="toolbar-btn publish-btn" type="primary" size="small" @click="openCreate">
            <template #icon>
              <icon-solar:pen-new-square-linear />
            </template>
            发布新帖
          </NButton>
        </div>
      </div>
    </NCard>

    <NSpin :show="loading">
      <div v-if="items.length === 0 && !loading" class="empty">暂无内容</div>
      <div v-else class="post-list">
        <NCard
          v-for="item in items"
          :key="item.id"
          size="small"
          class="post-card"
          :bordered="false"
          hoverable
          @click="openDetail(item)"
        >
          <div class="row">
            <div class="author">
              <NAvatar v-if="item.author.avatarUrl" :size="40" :src="item.author.avatarUrl" round />
              <NAvatar v-else :size="40" round>
                <icon-solar:user-circle-linear />
              </NAvatar>
              <div class="author-meta">
                <div class="author-name">{{ item.author.username }}</div>
                <div class="author-tags">
                  <NTooltip v-if="item.author.schoolName" trigger="hover" placement="bottom-start">
                    <template #trigger>
                      <NTag size="small" type="info" :bordered="false">{{ item.author.schoolName }}</NTag>
                    </template>
                    <div class="max-w-260px">{{ item.author.schoolDescription || '暂无描述' }}</div>
                  </NTooltip>
                  <NTag v-if="item.author.collegeName" size="small" :bordered="false">
                    {{ item.author.collegeName }}
                  </NTag>
                  <NTag v-if="item.author.majorName" size="small" type="success" :bordered="false">
                    {{ item.author.majorName }}
                  </NTag>
                </div>
              </div>
            </div>
            <div class="time">{{ formatTime(item.createdAt) }}</div>
          </div>
          <div class="post-title">{{ item.title }}</div>
          <div class="post-preview">{{ previewContent(item.content) }}</div>
          <div class="post-foot" @click.stop>
            <NButton
              text
              size="small"
              :type="item.liked ? 'primary' : 'default'"
              :loading="!!likeBusy[item.id]"
              @click="onToggleLike(item)"
            >
              <template #icon>
                <icon-solar:heart-bold v-if="item.liked" />
                <icon-solar:heart-linear v-else />
              </template>
              {{ item.likeCount }}
            </NButton>
            <span class="view-hint">点击查看详情</span>
          </div>
        </NCard>
      </div>
    </NSpin>

    <div v-if="total > pageSize" class="pager">
      <NPagination
        :page="page + 1"
        :page-count="Math.ceil(total / pageSize)"
        :page-size="pageSize"
        :item-count="total"
        @update:page="onPageChange"
      />
    </div>

    <!-- 详情弹窗 -->
    <NModal v-model:show="detailVisible" preset="card" class="detail-modal" :title="currentDetail?.title || ''">
      <template v-if="currentDetail">
        <div class="detail-head" :class="{ 'is-dark': isDark }">
          <div class="author">
            <NAvatar v-if="currentDetail.author.avatarUrl" :size="44" :src="currentDetail.author.avatarUrl" round />
            <NAvatar v-else :size="44" round>
              <icon-solar:user-circle-linear />
            </NAvatar>
            <div class="author-meta">
              <div class="author-name">{{ currentDetail.author.username }}</div>
              <div class="author-tags">
                <NTooltip v-if="currentDetail.author.schoolName" trigger="hover" placement="bottom-start">
                  <template #trigger>
                    <NTag size="small" type="info" :bordered="false">{{ currentDetail.author.schoolName }}</NTag>
                  </template>
                  <div class="max-w-260px">{{ currentDetail.author.schoolDescription || '暂无描述' }}</div>
                </NTooltip>
                <NTag v-if="currentDetail.author.collegeName" size="small" :bordered="false">
                  {{ currentDetail.author.collegeName }}
                </NTag>
                <NTag v-if="currentDetail.author.majorName" size="small" type="success" :bordered="false">
                  {{ currentDetail.author.majorName }}
                </NTag>
              </div>
            </div>
          </div>
          <div class="time">{{ formatTime(currentDetail.createdAt) }}</div>
        </div>
        <div class="detail-content" :class="{ 'is-dark': isDark }">{{ currentDetail.content }}</div>
      </template>
      <template #footer>
        <div v-if="currentDetail" class="detail-foot">
          <NButton
            text
            :type="currentDetail.liked ? 'primary' : 'default'"
            :loading="!!likeBusy[currentDetail.id]"
            @click="onToggleLike(currentDetail)"
          >
            <template #icon>
              <icon-solar:heart-bold v-if="currentDetail.liked" />
              <icon-solar:heart-linear v-else />
            </template>
            {{ currentDetail.likeCount }}
          </NButton>
          <div class="flex-1" />
          <template v-if="currentDetail.isOwn">
            <NButton size="small" @click="openEdit(currentDetail)">
              <template #icon>
                <icon-solar:pen-linear />
              </template>
              编辑
            </NButton>
            <NPopconfirm @positive-click="onDelete(currentDetail)">
              <template #trigger>
                <NButton size="small" type="error">
                  <template #icon>
                    <icon-solar:trash-bin-trash-linear />
                  </template>
                  删除
                </NButton>
              </template>
              确定删除这条帖子吗？
            </NPopconfirm>
          </template>
          <NButton size="small" @click="closeDetail">关闭</NButton>
        </div>
      </template>
    </NModal>

    <!-- 编辑/发布弹窗 -->
    <NModal
      v-model:show="editorVisible"
      preset="card"
      :title="editorMode === 'create' ? '发布新帖' : '编辑帖子'"
      class="editor-modal"
      :mask-closable="!submitting"
    >
      <NForm label-placement="top">
        <NFormItem label="标题">
          <NInput v-model:value="form.title" placeholder="例如：对于梯度下降的理解" maxlength="200" show-count />
        </NFormItem>
        <NFormItem label="内容">
          <NInput
            v-model:value="form.content"
            type="textarea"
            placeholder="在此写下你的看法、疑惑或解答…（纯文本）"
            :autosize="{ minRows: 6, maxRows: 14 }"
            maxlength="5000"
            show-count
          />
        </NFormItem>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-2">
          <NButton :disabled="submitting" @click="editorVisible = false">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="submitEditor">
            {{ editorMode === 'create' ? '发布' : '保存' }}
          </NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.posts-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
}

.header-card {
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(248, 250, 252, 0.96)),
    linear-gradient(135deg, rgba(4, 120, 87, 0.04), rgba(34, 197, 94, 0.03));
  border: 1px solid rgba(226, 232, 240, 0.72);

  .toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
  }

  .toolbar-left,
  .toolbar-actions {
    display: flex;
    gap: 6px;
    align-items: center;
    flex-wrap: wrap;
  }

  .toolbar-btn {
    height: 36px;
    padding: 0 14px;
    border-radius: 999px;
    font-weight: 600;
    box-shadow: none;
  }

  .filter-btn {
    border: 1px solid rgba(148, 163, 184, 0.2);
    background: rgba(255, 255, 255, 0.92);
    color: #334155;
  }

  .filter-pill {
    margin-left: 2px;
    padding: 2px 8px;
    border-radius: 999px;
    background: rgba(6, 95, 70, 0.08);
    color: #065F46;
    font-size: 12px;
    font-weight: 600;
    line-height: 1.4;
  }

  .filter-hint {
    display: inline-flex;
    align-items: center;
    padding: 0 12px;
    height: 32px;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.78);
    color: #64748b;
    font-size: 12px;

    span {
      margin-left: 4px;
      color: #0f172a;
      font-weight: 600;
    }
  }

  .mine-btn {
    border: 1px solid rgba(148, 163, 184, 0.2);
    background: rgba(255, 255, 255, 0.92);
    color: #334155;
  }

  .publish-btn {
    min-width: 116px;
    background: linear-gradient(135deg, #065F46, #047857);
    box-shadow: 0 10px 24px rgba(6, 95, 70, 0.18);
  }
}

.posts-page.is-dark {
  .header-card {
    background:
      linear-gradient(180deg, rgba(17, 24, 39, 0.92), rgba(15, 23, 42, 0.92)),
      linear-gradient(135deg, rgba(4, 120, 87, 0.12), rgba(34, 197, 94, 0.08));
    border-color: rgba(51, 65, 85, 0.78);

    .filter-btn,
    .mine-btn {
      border-color: rgba(71, 85, 105, 0.72);
      background: rgba(15, 23, 42, 0.88);
      color: #e2e8f0;
    }

    .filter-pill {
      background: rgba(4, 120, 87, 0.18);
      color: #6EE7B7;
    }

    .filter-hint {
      background: rgba(15, 23, 42, 0.7);
      color: #94a3b8;

      span {
        color: #f8fafc;
      }
    }

    .publish-btn {
      box-shadow: 0 12px 28px rgba(6, 95, 70, 0.28);
    }
  }

  .empty,
  .post-card .time,
  .post-card .post-foot .view-hint,
  .detail-head .time {
    color: #94a3b8;
  }

  .post-card .author-name,
  .post-card .post-title,
  .detail-head .author-name,
  .detail-content {
    color: #f8fafc;
  }

  .post-card .post-preview {
    color: #cbd5e1;
  }
}

.empty {
  padding: 48px 0;
  text-align: center;
  color: #9ca3af;
  font-size: 13px;
}

.post-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.post-card {
  cursor: pointer;
  transition: box-shadow 0.15s ease;

  .row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
  }

  .author {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .author-meta {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .author-name {
    font-weight: 600;
    font-size: 14px;
    color: #1f2937;
  }

  .author-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  .time {
    font-size: 12px;
    color: #9ca3af;
    white-space: nowrap;
  }

  .post-title {
    margin-top: 10px;
    font-size: 15px;
    font-weight: 600;
    color: #111827;
  }

  .post-preview {
    margin-top: 4px;
    font-size: 12px;
    color: #6b7280;
    line-height: 1.5;
  }

  .post-foot {
    margin-top: 8px;
    display: flex;
    align-items: center;
    gap: 10px;

    .view-hint {
      font-size: 12px;
      color: #9ca3af;
    }
  }
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

  .author {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .author-meta {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .author-name {
    font-weight: 600;
    font-size: 15px;
    color: #1f2937;
  }

  .author-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  .time {
    font-size: 12px;
    color: #9ca3af;
    white-space: nowrap;
  }
}

.detail-head.is-dark {
  .author-name {
    color: #f8fafc;
  }

  .time {
    color: #94a3b8;
  }
}

.detail-content {
  margin-top: 16px;
  font-size: 14px;
  color: #1f2937;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-content.is-dark {
  color: #e2e8f0;
}

.detail-foot {
  display: flex;
  align-items: center;
  gap: 8px;

  .flex-1 {
    flex: 1;
  }
}

.pager {
  display: flex;
  justify-content: center;
  padding: 8px 0 24px;
}

:deep(.detail-modal) {
  width: min(720px, calc(100vw - 32px));
}

:deep(.editor-modal) {
  width: min(640px, calc(100vw - 32px));
}

@media (max-width: 768px) {
  .header-card {
    .toolbar {
      align-items: stretch;
    }

    .toolbar-left,
    .toolbar-actions {
      width: 100%;
      justify-content: space-between;
    }

    .toolbar-btn {
      flex: 1;
      justify-content: center;
    }

    .filter-hint {
      width: 100%;
      justify-content: center;
    }
  }
}
</style>

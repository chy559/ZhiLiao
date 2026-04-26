<script setup lang="tsx">
import type { DropdownOption, UploadFileInfo } from 'naive-ui';
import { NButton, NDropdown, NEllipsis, NModal, NPopconfirm, NProgress, NTag, NUpload } from 'naive-ui';
import { uploadAccept } from '@/constants/common';
import { fakePaginationRequest } from '@/service/request';
import { UploadStatus } from '@/enum';
import SvgIcon from '@/components/custom/svg-icon.vue';
import FilePreview from '@/components/custom/file-preview.vue';
import UploadDialog from './modules/upload-dialog.vue';
import SearchDialog from './modules/search-dialog.vue';

const appStore = useAppStore();
type KnowledgeBaseFilter = 'all' | 'mine' | 'others';

// 文件预览相关状态
const previewVisible = ref(false);
const previewFileName = ref('');
const previewFileMd5 = ref('');
const filter = ref<KnowledgeBaseFilter>('all');

function apiFn() {
  return fakePaginationRequest<Api.KnowledgeBase.List>({ url: '/documents/uploads' });
}

function copyTextCompat(text: string) {
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).catch(() => fallbackCopy(text));
    return;
  }
  fallbackCopy(text);
}

function fallbackCopy(text: string) {
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.top = '0';
  ta.style.left = '0';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.focus();
  ta.select();
  try {
    document.execCommand('copy');
  } finally {
    document.body.removeChild(ta);
  }
}

function renderIcon(fileName: string) {
  const ext = getFileExt(fileName);
  if (ext) {
    if (uploadAccept.split(',').includes(`.${ext}`)) return <SvgIcon localIcon={ext} class="mx-4 text-12" />;
    return <SvgIcon localIcon="dflt" class="mx-4 text-12" />;
  }
  return null;
}

// 处理文件预览
function handleFilePreview(fileName: string, fileMd5: string) {
  console.log('[知识库] 点击预览按钮:', {
    fileName,
    fileMd5,
    完整信息: { fileName, fileMd5 }
  });

  previewFileName.value = fileName;
  previewFileMd5.value = fileMd5;
  previewVisible.value = true;
}

// 关闭文件预览
function closeFilePreview() {
  console.log('[知识库] 关闭文件预览');
  previewVisible.value = false;
  previewFileName.value = '';
  previewFileMd5.value = '';
}

const { columns, columnChecks, data, getData, loading } = useTable({
  apiFn,
  immediate: false,
  columns: () => [
    {
      key: 'fileName',
      title: '文件名',
      minWidth: 300,
      render: row => (
        <div class="flex items-center">
          {renderIcon(row.fileName)}
          <NEllipsis lineClamp={2} tooltip>
            <span
              class="cursor-pointer transition-colors hover:text-primary"
              onClick={() => handleFilePreview(row.fileName, row.fileMd5)}
            >
              {row.fileName}
            </span>
          </NEllipsis>
        </div>
      )
    },
    {
      key: 'fileMd5',
      title: 'MD5',
      width: 120,
      render: row => (
        <NEllipsis tooltip>
          <span
            class="cursor-pointer text-3 font-mono transition-colors hover:text-primary"
            onClick={() => {
              copyTextCompat(row.fileMd5);
              window.$message?.success('MD5已复制');
            }}
            title="点击复制MD5"
          >
            {row.fileMd5.substring(0, 8)}...
          </span>
        </NEllipsis>
      )
    },
    {
      key: 'totalSize',
      title: '文件大小',
      width: 100,
      render: row => fileSize(row.totalSize)
    },
    {
      key: 'status',
      title: '上传状态',
      width: 100,
      render: row => renderStatus(row.status, row.progress)
    },
    {
      key: 'orgTagName',
      title: '组织标签',
      width: 150,
      ellipsis: { tooltip: true, lineClamp: 2 }
    },
    {
      key: 'isPublic',
      title: '可见范围',
      width: 100,
      render: row =>
        row.public || row.isPublic ? <NTag type="success">组织内公开</NTag> : <NTag type="warning">仅自己可见</NTag>
    },
    {
      key: 'createdAt',
      title: '上传时间',
      width: 100,
      render: row => dayjs(row.createdAt).format('YYYY-MM-DD')
    },
    {
      key: 'operate',
      title: '操作',
      width: 260,
      render: row => (
        <div class="flex flex-wrap gap-4">
          {renderResumeUploadButton(row)}
          <NButton type="primary" ghost size="small" onClick={() => handleFilePreview(row.fileName, row.fileMd5)}>
            预览
          </NButton>
          {isCurrentUserFile(row) ? renderVisibilityToggleButton(row) : null}
          {isCurrentUserFile(row) ? (
            <NPopconfirm onPositiveClick={() => handleDelete(row.fileMd5)}>
              {{
                default: () => '确认删除当前文件吗？',
                trigger: () => (
                  <NButton type="error" ghost size="small">
                    删除
                  </NButton>
                )
              }}
            </NPopconfirm>
          ) : null}
        </div>
      )
    }
  ]
});

const store = useKnowledgeBaseStore();
const { tasks } = storeToRefs(store);
const authStore = useAuthStore();

const filterLabelMap: Record<KnowledgeBaseFilter, string> = {
  all: '全部文件',
  mine: '个人',
  others: '学院/学校公开'
};

const activeFilterLabel = computed(() => filterLabelMap[filter.value]);

const filterOptions = computed<DropdownOption[]>(() => [
  { key: 'all', label: '全部文件' },
  { key: 'mine', label: '个人' },
  { key: 'others', label: '学院/学校公开' }
]);

function isCurrentUserFile(row: Api.KnowledgeBase.UploadTask) {
  if (row.userId == null) {
    return Boolean(row.file);
  }
  return String(row.userId) === String(authStore.userInfo.id);
}

const filteredTasks = computed(() => {
  if (filter.value === 'mine') {
    return tasks.value.filter(task => isCurrentUserFile(task));
  }
  if (filter.value === 'others') {
    return tasks.value.filter(task => !isCurrentUserFile(task));
  }
  return tasks.value;
});

function switchFilter(next: KnowledgeBaseFilter) {
  if (filter.value === next) return;
  filter.value = next;
}

function handleSelectFilter(key: string) {
  switchFilter(key as KnowledgeBaseFilter);
}

watch(
  () => authStore.userInfo.id,
  (newId, oldId) => {
    if (newId !== oldId) {
      // 切换账号：先把上个账号遗留的已完成任务清掉，再按当前账号的数据重建
      tasks.value = [];
      if (newId) {
        getList();
      }
    }
  }
);

// 学校或学院变动后，后端会把公开文件迁到新组织标签，这里跟着重拉
watch(
  () => [authStore.userInfo.schoolTag, authStore.userInfo.collegeTag].join('|'),
  (cur, prev) => {
    if (cur !== prev) {
      getList();
    }
  }
);

onMounted(async () => {
  await getList();
});

/** 异步获取列表函数 该函数主要用于更新或初始化上传任务列表 它首先调用getData函数获取数据，然后根据获取到的数据状态更新任务列表 */
async function getList() {
  console.log('[知识库] 开始获取文件列表');

  // 等待获取最新数据
  await getData();

  console.log('[知识库] 获取到原始数据，数量:', data.value.length);
  data.value.forEach((item, index) => {
    console.log(`[知识库] 原始数据[${index}]:`, {
      fileName: item.fileName,
      fileMd5: item.fileMd5,
      status: item.status
    });
  });

  if (data.value.length === 0) {
    tasks.value = [];
    return;
  }

  // 本地进行中的任务保留（上传中 / 中断），其它字段一律以接口返回为准，
  // 避免切换账号或改学校后还看到旧的 orgTagName / 可见范围。
  const remoteMd5 = new Set(data.value.map(item => item.fileMd5));
  const localActive = tasks.value.filter(
    task =>
      task.status !== UploadStatus.Completed &&
      task.status !== UploadStatus.Break &&
      !remoteMd5.has(task.fileMd5)
  );

  const remoteTasks = data.value.map(item => {
    if (item.status === UploadStatus.Completed) {
      return { ...item, status: UploadStatus.Completed };
    }
    return { ...item, status: UploadStatus.Break };
  });

  tasks.value = [...localActive, ...remoteTasks];

  console.log('[知识库] 任务列表处理完成，总数:', tasks.value.length);
  tasks.value.forEach((task, index) => {
    console.log(`[知识库] 最终任务[${index}]:`, {
      fileName: task.fileName,
      fileMd5: task.fileMd5,
      status: task.status
    });
  });
}

function renderVisibilityToggleButton(row: Api.KnowledgeBase.UploadTask) {
  if (row.status !== UploadStatus.Completed) return null;
  const isPublic = Boolean(row.public ?? row.isPublic);
  const label = isPublic ? '设为私有' : '设为公开';
  return (
    <NButton size="small" ghost onClick={() => handleToggleVisibility(row, !isPublic)}>
      {label}
    </NButton>
  );
}

async function handleToggleVisibility(row: Api.KnowledgeBase.UploadTask, nextIsPublic: boolean) {
  const { error } = await request({
    url: `/documents/${row.fileMd5}/visibility`,
    method: 'PATCH',
    data: { isPublic: nextIsPublic }
  });
  if (error) return;
  window.$message?.success('可见范围已更新');
  await getList();
}

async function handleDelete(fileMd5: string) {
  const index = tasks.value.findIndex(task => task.fileMd5 === fileMd5);

  if (index !== -1) {
    tasks.value[index].requestIds?.forEach(requestId => {
      request.cancelRequest(requestId);
    });
  }

  // 如果文件一个分片也没有上传完成，则直接删除
  if (tasks.value[index].uploadedChunks && tasks.value[index].uploadedChunks.length === 0) {
    tasks.value.splice(index, 1);
    return;
  }

  const { error } = await request({ url: `/documents/${fileMd5}`, method: 'DELETE' });
  if (!error) {
    tasks.value.splice(index, 1);
    window.$message?.success('删除成功');
    await getData();
  }
}

// #region 文件上传
const uploadVisible = ref(false);
function handleUpload() {
  uploadVisible.value = true;
}
// #endregion

// #region 检索知识库
const searchVisible = ref(false);
function handleSearch() {
  searchVisible.value = true;
}
// #endregion

// 渲染上传状态
function renderStatus(status: UploadStatus, percentage: number) {
  if (status === UploadStatus.Completed) return <NTag type="success">已完成</NTag>;
  else if (status === UploadStatus.Break) return <NTag type="error">上传中断</NTag>;
  return <NProgress percentage={percentage} processing />;
}

// #region 文件续传
function renderResumeUploadButton(row: Api.KnowledgeBase.UploadTask) {
  if (row.status === UploadStatus.Break) {
    if (row.file)
      return (
        <NButton type="primary" size="small" ghost onClick={() => resumeUpload(row)}>
          续传
        </NButton>
      );
    return (
      <NUpload
        show-file-list={false}
        default-upload={false}
        accept={uploadAccept}
        onBeforeUpload={options => onBeforeUpload(options, row)}
        class="w-fit"
      >
        <NButton type="primary" size="small" ghost>
          续传
        </NButton>
      </NUpload>
    );
  }
  return null;
}

// 任务列表存在文件，直接续传
function resumeUpload(row: Api.KnowledgeBase.UploadTask) {
  row.status = UploadStatus.Pending;
  store.startUpload();
}

async function onBeforeUpload(
  options: { file: UploadFileInfo; fileList: UploadFileInfo[] },
  row: Api.KnowledgeBase.UploadTask
) {
  const md5 = await calculateMD5(options.file.file!);
  if (md5 !== row.fileMd5) {
    window.$message?.error('两次上传的文件不一致');
    return false;
  }
  loading.value = true;
  const { error, data: progress } = await request<Api.KnowledgeBase.Progress>({
    url: '/upload/status',
    params: { file_md5: row.fileMd5 }
  });
  if (!error) {
    row.file = options.file.file!;
    row.status = UploadStatus.Pending;
    row.progress = progress.progress;
    row.uploadedChunks = progress.uploaded;
    store.startUpload();
    loading.value = false;
    return true;
  }
  loading.value = false;
  return false;
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="文件列表" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" @add="handleUpload" @refresh="getList">
          <template #prefix>
            <div class="kb-toolbar">
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
              <NButton class="toolbar-btn search-btn" size="small" ghost type="primary" @click="handleSearch">
                <template #icon>
                  <icon-ic-round-search class="text-icon" />
                </template>
                检索知识库
              </NButton>
            </div>
          </template>
        </TableHeaderOperation>
      </template>
      <NDataTable
        striped
        :columns="columns"
        :data="filteredTasks"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="962"
        :loading="loading"
        remote
        :row-key="row => row.id"
        :pagination="false"
        class="sm:h-full"
      />
    </NCard>
    <UploadDialog v-model:visible="uploadVisible" />
    <SearchDialog v-model:visible="searchVisible" />

    <!-- 文件预览弹窗 -->
    <NModal
      v-model:show="previewVisible"
      preset="card"
      title="文件预览"
      class="file-preview-modal"
      style="width: 90vw; max-width: 1400px; height: 92vh"
    >
      <div class="file-preview-modal-body">
        <FilePreview
          :file-name="previewFileName"
          :file-md5="previewFileMd5"
          :visible="previewVisible"
          @close="closeFilePreview"
        />
      </div>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.file-list-container {
  transition: width 0.3s ease;
}

.kb-toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
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

.search-btn {
  min-width: 108px;
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

:deep() {
  .n-progress-icon.n-progress-icon--as-text {
    white-space: nowrap;
  }

  .file-preview-modal.n-card {
    height: 92vh !important;
    display: flex;
    flex-direction: column;
  }

  .file-preview-modal .n-card__content {
    padding: 0 !important;
    flex: 1 1 auto;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  .file-preview-modal-body {
    flex: 1 1 auto;
    min-height: 0;
    height: 100%;
  }
}
</style>

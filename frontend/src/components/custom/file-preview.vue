<template>
  <div class="file-preview-container">
    <!-- 预览头部 -->
    <div class="preview-header">
      <div class="flex items-center gap-2">
        <SvgIcon :local-icon="getFileIcon(fileName)" class="text-16" />
        <span class="font-medium">{{ fileName }}</span>
      </div>
      <div class="flex items-center gap-2">
        <NButton v-if="previewUrl" size="small" @click="openInNewTab">
          <template #icon>
            <icon-mdi-open-in-new />
          </template>
          新标签打开
        </NButton>
        <NButton size="small" :loading="downloading" @click="downloadFile">
          <template #icon>
            <icon-mdi-download />
          </template>
          下载
        </NButton>
        <NButton size="small" @click="closePreview">
          <template #icon>
            <icon-mdi-close />
          </template>
        </NButton>
      </div>
    </div>

    <!-- 预览内容 -->
    <div class="preview-content">
      <template v-if="loading">
        <div class="flex items-center justify-center h-full">
          <NSpin size="large" />
        </div>
      </template>
      <template v-else-if="error">
        <div class="flex flex-col items-center justify-center h-full text-gray-500">
          <icon-mdi-alert-circle class="text-48 mb-4" />
          <p>{{ error }}</p>
        </div>
      </template>
      <template v-else-if="previewMode === 'iframe' && previewUrl">
        <iframe :src="previewUrl" class="preview-iframe" />
      </template>
      <template v-else-if="previewMode === 'image' && previewUrl">
        <div class="content-wrapper flex items-center justify-center">
          <img :src="previewUrl" :alt="fileName" class="preview-image" />
        </div>
      </template>
      <template v-else>
        <div class="content-wrapper">
          <pre class="preview-text">{{ content }}</pre>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { NButton, NSpin } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { request } from '@/service/request';
import { getFileExt } from '@/utils/common';
import { localStg } from '@/utils/storage';

interface Props {
  fileName: string;
  fileMd5?: string;
  visible: boolean;
}

interface Emits {
  (e: 'close'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const loading = ref(false);
const downloading = ref(false);
const content = ref('');
const error = ref('');
const previewUrl = ref('');
const previewMode = ref<'text' | 'iframe' | 'image'>('text');

const IFRAME_EXTS = ['pdf'];
const IMAGE_EXTS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'];

function getFileIcon(fileName: string) {
  const ext = getFileExt(fileName);
  if (ext) {
    const supportedIcons = ['pdf', 'doc', 'docx', 'txt', 'md', 'jpg', 'jpeg', 'png', 'gif'];
    return supportedIcons.includes(ext.toLowerCase()) ? ext : 'dflt';
  }
  return 'dflt';
}

function resolveMode(fileName: string): 'text' | 'iframe' | 'image' {
  const ext = (getFileExt(fileName) || '').toLowerCase();
  if (IFRAME_EXTS.includes(ext)) return 'iframe';
  if (IMAGE_EXTS.includes(ext)) return 'image';
  return 'text';
}

watch(
  () => props.fileName,
  async newFileName => {
    if (newFileName && props.visible) {
      await loadPreviewContent();
    }
  },
  { immediate: true }
);

watch(
  () => props.visible,
  async visible => {
    if (visible && props.fileName) {
      await loadPreviewContent();
    }
  }
);

async function loadPreviewContent() {
  if (!props.fileName) return;

  loading.value = true;
  error.value = '';
  content.value = '';
  previewUrl.value = '';
  previewMode.value = resolveMode(props.fileName);

  try {
    const token = localStg.get('token') || '';

    if (previewMode.value === 'iframe' || previewMode.value === 'image') {
      const { error: requestError, data } = await request<{
        fileName: string;
        fileMd5: string;
        previewUrl: string;
        fileSize: number;
      }>({
        url: '/documents/preview-url',
        params: {
          fileName: props.fileName,
          fileMd5: props.fileMd5 || undefined,
          token: token || undefined
        }
      });

      if (requestError) {
        error.value = '预览失败：' + (requestError.message || '未知错误');
      } else if (data) {
        previewUrl.value = data.previewUrl;
      }
      return;
    }

    // 文本预览（txt/md/doc/docx 等，走 Tika）
    const { error: requestError, data } = await request<{
      fileName: string;
      content: string;
      fileSize: number;
    }>({
      url: '/documents/preview',
      params: {
        fileName: props.fileName,
        fileMd5: props.fileMd5 || undefined,
        token: token || undefined
      }
    });

    if (requestError) {
      error.value = '预览失败：' + (requestError.message || '未知错误');
    } else if (data) {
      content.value = data.content;
    }
  } catch (err: any) {
    error.value = '预览失败：' + (err.message || '网络错误');
  } finally {
    loading.value = false;
  }
}

function openInNewTab() {
  if (previewUrl.value) {
    window.open(previewUrl.value, '_blank');
  }
}

async function downloadFile() {
  if (!props.fileName) return;

  downloading.value = true;

  try {
    const token = localStg.get('token') || '';

    if (props.fileMd5) {
      const { error: requestError, data } = await request<{
        fileName: string;
        downloadUrl: string;
        fileSize: number;
        fileMd5?: string;
      }>({
        url: '/documents/download-by-md5',
        params: {
          fileMd5: props.fileMd5,
          token: token || undefined
        }
      });

      if (requestError) {
        window.$message?.error('下载失败：' + (requestError.message || '未知错误'));
      } else if (data) {
        const link = document.createElement('a');
        link.href = data.downloadUrl;
        link.download = data.fileName;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.$message?.success('开始下载文件');
      }
    } else {
      const { error: requestError, data } = await request<{
        fileName: string;
        downloadUrl: string;
        fileSize: number;
      }>({
        url: '/documents/download',
        params: {
          fileName: props.fileName,
          token: token || undefined
        }
      });

      if (requestError) {
        window.$message?.error('下载失败：' + (requestError.message || '未知错误'));
      } else if (data) {
        const link = document.createElement('a');
        link.href = data.downloadUrl;
        link.download = data.fileName;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.$message?.success('开始下载文件');
      }
    }
  } catch (err: any) {
    window.$message?.error('下载失败：' + (err.message || '网络错误'));
  } finally {
    downloading.value = false;
  }
}

function closePreview() {
  emit('close');
}
</script>

<style scoped lang="scss">
.file-preview-container {
  @apply h-full min-h-0 flex flex-col bg-white;

  .preview-header {
    @apply flex items-center justify-between p-4 border-b border-gray-200 bg-gray-50;
  }

  .preview-content {
    @apply min-h-0 flex-1 overflow-hidden;
    min-height: 70vh; // 兜底：父级 flex 链一旦断裂仍能保证可视高度

    .content-wrapper {
      @apply h-full overflow-auto p-4;
    }

    .preview-text {
      @apply text-sm font-mono whitespace-pre-wrap break-words;
      font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
      line-height: 1.5;
      margin: 0;
    }

    .preview-iframe {
      @apply w-full border-0;
      height: 100%;
      min-height: 70vh;
    }

    .preview-image {
      @apply max-w-full max-h-full object-contain;
    }
  }
}
</style>

<script setup lang="ts">
// eslint-disable-next-line @typescript-eslint/no-unused-vars
import { nextTick } from 'vue';
import { NModal } from 'naive-ui';
import { VueMarkdownIt } from 'vue-markdown-shiki';
import { formatDate } from '@/utils/common';
import FilePreview from '@/components/custom/file-preview.vue';
defineOptions({ name: 'ChatMessage' });

// 来源文件预览状态（点击来源链接时，在弹窗里调用 FilePreview 组件）
const previewVisible = ref(false);
const previewFileName = ref('');
const previewFileMd5 = ref('');

function closeFilePreview() {
  previewVisible.value = false;
  previewFileName.value = '';
  previewFileMd5.value = '';
}

const props = defineProps<{
  msg: Api.Chat.Message,
  sessionId?: string
}>();

const authStore = useAuthStore();

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

function handleCopy(content: string) {
  copyTextCompat(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();

// 存储文件名和对应的事件处理
const sourceFiles = ref<Array<{fileName: string, id: string, referenceNumber: number, fileMd5?: string}>>([]);

// 处理来源文件链接的函数
function processSourceLinks(text: string): string {
  // 重置来源文件列表，避免重复
  sourceFiles.value = [];

  // 新格式：匹配 (来源#数字: 文件名 | MD5:xxx) 的正则表达式，兼容全角括号
  // 格式示例：(来源#1: test.txt | MD5:abc123) 或 (来源#1: test.txt|MD5:abc123)
  const newSourcePattern = /([\(（])来源#(\d+):\s*([^|\n\r（）]+?)\s*\|\s*MD5:\s*([a-fA-F0-9]+)([\)）])/g;

  // 先处理新格式（包含MD5）
  let processedText = text.replace(newSourcePattern, (_match, leftParen, sourceNum, fileName, fileMd5, rightParen) => {
    const linkClass = 'source-file-link';
    const trimmedFileName = fileName.trim();
    const trimmedMd5 = fileMd5.trim();
    const fileId = `source-file-${sourceFiles.value.length}`;
    const referenceNumber = parseInt(sourceNum, 10);

    // 存储文件信息（包含文件名和MD5）
    sourceFiles.value.push({
      fileName: trimmedFileName,
      id: fileId,
      referenceNumber,
      fileMd5: trimmedMd5
    });

    const lp = leftParen === '(' ? '(' : '（';
    const rp = rightParen === ')' ? ')' : '）';

    // 显示格式：来源#1: test.txt | MD5:abc...
    return `${lp}来源#${sourceNum}: <span class="${linkClass}" data-file-id="${fileId}">${trimmedFileName} | MD5:${trimmedMd5.substring(0, 8)}...</span>${rp}`;
  });

  // 旧格式：匹配 (来源#数字: 文件名) 的正则表达式，兼容全角括号和无括号格式
  // 用于向后兼容旧的引用格式
  const oldSourcePattern = /([\(（])来源#(\d+):\s*([^\n\r（）]+?)([\)）])/g;

  processedText = processedText.replace(oldSourcePattern, (_match, leftParen, sourceNum, fileName, rightParen) => {
    const linkClass = 'source-file-link';
    const trimmedFileName = fileName.trim();
    const fileId = `source-file-${sourceFiles.value.length}`;
    const referenceNumber = parseInt(sourceNum, 10);

    // 存储文件信息（旧格式，没有MD5）
    sourceFiles.value.push({
      fileName: trimmedFileName,
      id: fileId,
      referenceNumber
    });

    const lp = leftParen || '';
    const rp = rightParen || '';

    return `${lp}来源#${sourceNum}: <span class="${linkClass}" data-file-id="${fileId}">${trimmedFileName}</span>${rp}`;
  });

  return processedText;
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return processSourceLinks(rawContent);
  }

  return rawContent;
});

// 处理内容点击事件（事件委托）
function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;

  // 检查点击的是否是文件链接
  if (target.classList.contains('source-file-link')) {
    const fileId = target.getAttribute('data-file-id');
    if (fileId) {
      const file = sourceFiles.value.find(f => f.id === fileId);
      if (file) {
        handleSourceFileClick({
          fileName: file.fileName,
          referenceNumber: file.referenceNumber,
          fileMd5: file.fileMd5
        });
      }
    }
  }
}

// 点击来源链接：直接以知识库页面的方式在弹窗中预览文件
function handleSourceFileClick(fileInfo: { fileName: string, referenceNumber: number, fileMd5?: string }) {
  const { fileName, fileMd5: extractedMd5 } = fileInfo;
  previewFileName.value = fileName;
  previewFileMd5.value = extractedMd5 || '';
  previewVisible.value = true;
}
</script>

<template>
  <div class="message-card mb-8 flex-col gap-2">
    <div v-if="msg.role === 'user'" class="flex items-center gap-4">
      <NAvatar v-if="authStore.userInfo.avatarUrl" class="bg-success" :src="authStore.userInfo.avatarUrl" />
      <NAvatar v-else class="bg-success">
        <SvgIcon icon="ph:user-circle" class="text-icon-large color-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">{{ authStore.userInfo.username }}</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <div v-else class="flex items-center gap-4">
      <NAvatar class="bg-primary">
        <SystemLogo class="text-6 text-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">考辅智聊</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <NText v-if="msg.status === 'pending'">
      <icon-eos-icons:three-dots-loading class="ml-12 mt-2 text-8" />
    </NText>
    <NText v-else-if="msg.status === 'error'" class="ml-12 mt-2 italic">服务器繁忙，请稍后再试</NText>
    <div v-else-if="msg.role === 'assistant'" class="assistant-bubble mt-2 pl-12" @click="handleContentClick">
      <VueMarkdownIt :content="content" />
    </div>
    <NText v-else-if="msg.role === 'user'" class="user-bubble ml-12 mt-2 text-4">{{ content }}</NText>
    <NDivider class="ml-12 w-[calc(100%-3rem)] mb-0! mt-2!" />
    <div class="ml-12 flex gap-4">
      <NButton quaternary @click="handleCopy(msg.content)">
        <template #icon>
          <icon-mynaui:copy />
        </template>
      </NButton>
    </div>

    <!-- 来源文件预览弹窗（复用知识库页面的 FilePreview） -->
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
.message-card {
  border-radius: 14px;
  padding: 8px 10px;
}

.assistant-bubble {
  border-left: 3px solid rgba(6, 95, 70, 0.7);
  background: rgba(239, 246, 255, 0.58);
  border-radius: 10px;
  padding: 8px 12px;
}

.user-bubble {
  background: rgba(236, 253, 245, 0.8);
  border-radius: 10px;
  padding: 8px 12px;
}

:deep() {
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
  }

  .file-preview-modal-body {
    flex: 1 1 auto;
    min-height: 0;
    display: flex;
    height: 100%;
    width: 100%;
  }
}

.dark {
  .assistant-bubble {
    background: rgba(6, 78, 59, 0.2);
    border-left-color: rgba(16, 185, 129, 0.86);
  }

  .user-bubble {
    background: rgba(15, 23, 42, 0.7);
  }
}

:deep(.source-file-link) {
  color: #065F46;
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;

  &:hover {
    color: #10B981;
    text-decoration: none;
  }

  &:active {
    color: #064E3B;
  }
}
</style>

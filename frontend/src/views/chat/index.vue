<script setup lang="ts">
import { NButton, NPopconfirm } from 'naive-ui';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';

const chatStore = useChatStore();
const { list, streaming } = storeToRefs(chatStore);
const newConversationLoading = ref(false);

async function handleNewConversation() {
  if (streaming.value) {
    window.$message?.warning('请等待当前回答完成后再开启新会话');
    return;
  }
  newConversationLoading.value = true;
  const { error } = await request({ url: 'users/conversation/new', method: 'POST' });
  newConversationLoading.value = false;
  if (error) return;
  list.value = [];
  window.$message?.success('已开启新会话');
}
</script>

<template>
  <div class="chat-page flex-col gap-4">
    <section class="exam-hero card-wrapper flex items-start justify-between gap-4">
      <div class="flex-1">
        <h2 class="text-22px font-700 color-#0f172a dark:color-#e2e8f0">考辅智聊</h2>
        <p class="mt-2 text-14px color-#334155 dark:color-#94a3b8">
          面向考试复习的 RAG + LLM 聊天助手，支持知识点梳理、真题解析、错题复盘与冲刺提纲。
        </p>
      </div>
      <NPopconfirm @positive-click="handleNewConversation">
        开启新会话会清空当前对话上下文，确定继续吗？
        <template #trigger>
          <NButton :loading="newConversationLoading" size="small" type="primary" ghost>
            <template #icon>
              <icon-material-symbols:add-comment-outline />
            </template>
            新建会话
          </NButton>
        </template>
      </NPopconfirm>
    </section>
    <ChatList />
    <InputBox />
  </div>
</template>

<style scoped>
.chat-page {
  min-height: calc(100vh - 140px);
  padding: 8px 10px 2px;
  background: radial-gradient(circle at 0% 0%, rgba(6, 95, 70, 0.08), transparent 42%);
}

.exam-hero {
  border: 1px solid rgba(15, 118, 110, 0.24);
  background:
    linear-gradient(120deg, rgba(239, 246, 255, 0.92), rgba(224, 242, 254, 0.78)),
    #fff;
  padding: 14px 18px;
}

.dark .chat-page {
  background: radial-gradient(circle at 0% 0%, rgba(6, 95, 70, 0.2), transparent 42%);
}

.dark .exam-hero {
  border-color: rgba(4, 120, 87, 0.34);
  background:
    linear-gradient(120deg, rgba(30, 41, 59, 0.88), rgba(15, 23, 42, 0.84)),
    #111827;
}
</style>

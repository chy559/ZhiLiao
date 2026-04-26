<script setup lang="ts">
const chatStore = useChatStore();
const { input, list, wsStatus } = storeToRefs(chatStore);

const latestMessage = computed(() => {
  return list.value[list.value.length - 1] ?? {};
});

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});

const sendable = computed(
  () => (!input.value.message && !isSending) || ['CLOSED', 'CONNECTING'].includes(wsStatus.value)
);

const handleSend = async () => {
  //  判断是否正在发送, 如果发送中，则停止ai继续响应
  if (isSending.value) {
    const { error, data } = await request<Api.Chat.Token>({ url: 'chat/websocket-token' });
    if (error) return;

    chatStore.wsSend(JSON.stringify({ type: 'stop', _internal_cmd_token: data.cmdToken }));

    list.value[list.value.length - 1].status = 'finished';
    if (!latestMessage.value.content) list.value.pop();
    return;
  }

  list.value.push({
    content: input.value.message,
    role: 'user'
  });
  chatStore.wsSend(input.value.message);
  list.value.push({
    content: '',
    role: 'assistant',
    status: 'pending'
  });
  input.value.message = '';
};

const inputRef = ref();
// 手动插入换行符（确保所有浏览器兼容）
const insertNewline = () => {
  const textarea = inputRef.value;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  // 在光标位置插入换行符
  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  // 更新光标位置（在插入的换行符之后）
  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus(); // 确保保持焦点
  });
};

// ctrl + enter 换行
// enter 发送
const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};
</script>

<template>
  <div class="exam-input-panel relative w-full p-4 card-wrapper">
    <textarea
      ref="inputRef"
      v-model.trim="input.message"
      placeholder="向 考辅智聊 提问：知识点、真题、错题复盘、考前冲刺..."
      class="min-h-10 w-full cursor-text resize-none b-none bg-transparent color-#333 caret-[rgb(var(--primary-color))] outline-none dark:color-#f1f1f1"
      @keydown="handShortcut"
    />
    <div class="pt-2">
      <NText class="query-hint text-12px">
        提示：按照文件内容检索，建议直接描述知识点、题目内容或关键词；只输入文件名时，可能无法准确命中对应资料。
      </NText>
    </div>
    <div class="flex items-center justify-end pt-2">
      <div class="flex items-center gap-3">
        <NText class="text-12px color-#6b7280 dark:color-#9ca3af">Enter 发送，Ctrl/Shift+Enter 换行</NText>
        <NButton :disabled="sendable" strong circle type="primary" @click="handleSend">
          <template #icon>
            <icon-material-symbols:stop-rounded v-if="isSending" />
            <icon-guidance:send v-else />
          </template>
        </NButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
.exam-input-panel {
  border: 1px solid rgba(6, 95, 70, 0.25);
  background:
    linear-gradient(135deg, rgba(239, 246, 255, 0.9), rgba(236, 253, 245, 0.72)),
    #fff;
}

.dark .exam-input-panel {
  border-color: rgba(16, 185, 129, 0.28);
  background:
    linear-gradient(135deg, rgba(30, 41, 59, 0.78), rgba(17, 24, 39, 0.82)),
    #1c1c1c;
}

.query-hint {
  color: #64748b;
}

.dark .query-hint {
  color: #94a3b8;
}
</style>

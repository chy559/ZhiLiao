import { useWebSocket } from '@vueuse/core';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const conversationId = ref<string>('');
  const input = ref<Api.Chat.Input>({ message: '' });

  const list = ref<Api.Chat.Message[]>([]);

  const store = useAuthStore();

  const sessionId = ref<string>('');

  /** 是否正在流式接收AI响应 */
  const streaming = ref(false);

  const wsUrl = computed(() => `/proxy-ws/chat/${store.token}`);

  const {
    status: wsStatus,
    data: wsData,
    send: wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(wsUrl, {
    autoReconnect: true
  });

  // 统一在 store 中处理所有 WebSocket 消息
  // 这样即使用户切换页面，组件卸载后 watcher 仍然存活，不会丢失流式数据
  watch(wsData, val => {
    if (!val) return;
    try {
      const data = JSON.parse(val);

      // 处理连接消息
      if (data.type === 'connection' && data.sessionId) {
        sessionId.value = data.sessionId;
        return;
      }

      // 以下处理需要列表中存在待填充的 assistant 消息
      const assistant = list.value[list.value.length - 1];
      if (!assistant || assistant.role !== 'assistant') return;

      if (data.type === 'completion' && data.status === 'finished') {
        if (assistant.status !== 'error') {
          assistant.status = 'finished';
        }
        streaming.value = false;
      } else if (data.type === 'stop') {
        assistant.status = 'finished';
        streaming.value = false;
      } else if (data.error) {
        assistant.status = 'error';
        streaming.value = false;
      } else if (data.chunk) {
        assistant.status = 'loading';
        assistant.content += data.chunk;
        streaming.value = true;
      }
    } catch {
      // Ignore JSON parse errors for non-JSON messages
    }
  });

  const scrollToBottom = ref<null | (() => void)>(null);

  return {
    input,
    conversationId,
    list,
    streaming,
    wsStatus,
    wsData,
    wsSend,
    wsOpen,
    wsClose,
    sessionId,
    scrollToBottom
  };
});

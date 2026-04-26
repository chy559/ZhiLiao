<script setup lang="ts">
import { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const { list, sessionId } = storeToRefs(chatStore);
const authStore = useAuthStore();

const loading = ref(false);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

// 用户是否希望跟随底部：贴近底部时为 true；用户手动上滑查看历史时置为 false
const stickToBottom = ref(true);
let prevListLength = 0;

function handleScroll(e: Event) {
  const target = e.target as HTMLElement | null;
  if (!target) return;
  const distanceToBottom = target.scrollHeight - target.scrollTop - target.clientHeight;
  stickToBottom.value = distanceToBottom < 80;
}

// 仅在“新消息追加”时强制滚到底部；流式 token 更新期间遵循用户的滚动位置
watch(
  () => list.value.length,
  newLen => {
    if (newLen > prevListLength) {
      stickToBottom.value = true;
      scrollToBottom(true);
    }
    prevListLength = newLen;
  }
);

function scrollToBottom(force = false) {
  setTimeout(() => {
    // 关键：在执行时（而非调度时）再次校验 —— 用户可能在这 100ms 内已上滑
    if (!force && !stickToBottom.value) return;
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

const range = ref<[number, number]>([dayjs().subtract(7, 'day').valueOf(), dayjs().add(1, 'day').valueOf()]);

const params = computed(() => {
  return {
    start_date: dayjs(range.value[0]).format('YYYY-MM-DD'),
    end_date: dayjs(range.value[1]).format('YYYY-MM-DD')
  };
});

watch(
  [() => authStore.userInfo.id, params],
  ([id]) => {
    if (id) getList();
  },
  { immediate: true }
);

async function getList() {
  // 如果AI正在流式回复或列表中有未完成的消息，跳过从服务器重新加载
  // 避免覆盖正在接收的实时数据（后端在回复完成后才会持久化对话历史）
  if (chatStore.streaming) {
    return;
  }
  const lastMsg = list.value[list.value.length - 1];
  if (lastMsg?.role === 'assistant' && ['loading', 'pending'].includes(lastMsg.status || '')) {
    return;
  }

  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'users/conversation',
    params: params.value
  });
  if (!error) {
    list.value = data;
  }
  loading.value = false;
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
});
</script>

<template>
  <Suspense>
    <NScrollbar ref="scrollbarRef" class="h-0 flex-auto" @scroll="handleScroll">
      <Teleport defer to="#header-extra">
        <div class="review-filter px-10">
          <NForm :model="params" label-placement="left" :show-feedback="false" inline>
            <NFormItem label="复习范围">
              <NDatePicker v-model:value="range" type="daterange" />
            </NFormItem>
          </NForm>
        </div>
      </Teleport>
      <NSpin :show="loading">
        <VueMarkdownItProvider>
          <ChatMessage v-for="(item, index) in list" :key="index" :msg="item" :session-id="sessionId" />
        </VueMarkdownItProvider>
      </NSpin>
    </NScrollbar>
  </Suspense>
</template>

<style scoped lang="scss">
.review-filter {
  :deep(.n-form-item-label__text) {
    font-weight: 600;
    color: #064E3B;
  }
}
</style>

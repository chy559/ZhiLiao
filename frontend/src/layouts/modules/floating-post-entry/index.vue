<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';

defineOptions({
  name: 'FloatingPostEntry'
});

interface FloatingPosition {
  x: number;
  y: number;
}

const STORAGE_KEY = 'floating-post-entry-position';
const STORAGE_TOKEN_KEY = 'floating-post-entry-token';
const DESKTOP_BUTTON_SIZE = 72;
const MOBILE_BUTTON_SIZE = 62;
const VIEWPORT_PADDING = 16;
const DRAG_THRESHOLD = 6;

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const visible = computed(() => route.name !== 'posts');
const isDragging = ref(false);
const isPointerDown = ref(false);
const hasDragged = ref(false);
const position = ref<FloatingPosition>({ x: 0, y: 0 });

const pointerState = {
  pointerId: -1,
  startX: 0,
  startY: 0,
  originX: 0,
  originY: 0
};

const buttonSize = computed(() => (window.innerWidth <= 768 ? MOBILE_BUTTON_SIZE : DESKTOP_BUTTON_SIZE));

const wrapperStyle = computed(() => ({
  transform: `translate3d(${position.value.x}px, ${position.value.y}px, 0)`,
  transition: isDragging.value ? 'none' : 'transform 0.22s ease, opacity 0.2s ease'
}));

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

function getViewportBounds() {
  const size = buttonSize.value;

  return {
    minX: VIEWPORT_PADDING,
    minY: VIEWPORT_PADDING,
    maxX: Math.max(VIEWPORT_PADDING, window.innerWidth - size - VIEWPORT_PADDING),
    maxY: Math.max(VIEWPORT_PADDING, window.innerHeight - size - VIEWPORT_PADDING)
  };
}

function getDefaultPosition(): FloatingPosition {
  const { maxX, maxY } = getViewportBounds();

  return {
    x: maxX,
    y: maxY
  };
}

function normalizePosition(pos: FloatingPosition) {
  const bounds = getViewportBounds();

  return {
    x: clamp(pos.x, bounds.minX, bounds.maxX),
    y: clamp(pos.y, bounds.minY, bounds.maxY)
  };
}

function savePosition() {
  window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(position.value));
  window.sessionStorage.setItem(STORAGE_TOKEN_KEY, authStore.token);
}

function restorePosition() {
  const cachedToken = window.sessionStorage.getItem(STORAGE_TOKEN_KEY);
  const cached = window.sessionStorage.getItem(STORAGE_KEY);

  if (!cached || cachedToken !== authStore.token) {
    position.value = getDefaultPosition();
    savePosition();
    return;
  }

  try {
    const parsed = JSON.parse(cached) as Partial<FloatingPosition>;

    if (typeof parsed.x === 'number' && typeof parsed.y === 'number') {
      position.value = normalizePosition({ x: parsed.x, y: parsed.y });
      return;
    }
  } catch {}

  position.value = getDefaultPosition();
}

function syncPositionToViewport() {
  position.value = normalizePosition(position.value.x || position.value.y ? position.value : getDefaultPosition());
}

function handlePointerDown(event: PointerEvent) {
  if (event.button !== 0) return;

  const currentTarget = event.currentTarget as HTMLElement | null;

  currentTarget?.setPointerCapture?.(event.pointerId);

  isPointerDown.value = true;
  isDragging.value = false;
  hasDragged.value = false;
  pointerState.pointerId = event.pointerId;
  pointerState.startX = event.clientX;
  pointerState.startY = event.clientY;
  pointerState.originX = position.value.x;
  pointerState.originY = position.value.y;
}

function handlePointerMove(event: PointerEvent) {
  if (!isPointerDown.value || event.pointerId !== pointerState.pointerId) return;

  const deltaX = event.clientX - pointerState.startX;
  const deltaY = event.clientY - pointerState.startY;
  const moved = Math.hypot(deltaX, deltaY);

  if (moved > DRAG_THRESHOLD) {
    isDragging.value = true;
    hasDragged.value = true;
  }

  if (!isDragging.value) return;

  event.preventDefault();

  position.value = normalizePosition({
    x: pointerState.originX + deltaX,
    y: pointerState.originY + deltaY
  });
}

function finishPointer(event?: PointerEvent) {
  if (event && event.pointerId !== pointerState.pointerId) return;

  if (isDragging.value) {
    savePosition();
  }

  isPointerDown.value = false;
  isDragging.value = false;
  pointerState.pointerId = -1;

  window.setTimeout(() => {
    hasDragged.value = false;
  }, 0);
}

function handleClick() {
  if (!visible.value || hasDragged.value) return;

  router.push({ name: 'posts' });
}

function handleResize() {
  syncPositionToViewport();
  savePosition();
}

onMounted(() => {
  restorePosition();
  syncPositionToViewport();

  window.addEventListener('pointermove', handlePointerMove, { passive: false });
  window.addEventListener('pointerup', finishPointer);
  window.addEventListener('pointercancel', finishPointer);
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', handlePointerMove);
  window.removeEventListener('pointerup', finishPointer);
  window.removeEventListener('pointercancel', finishPointer);
  window.removeEventListener('resize', handleResize);
});

watch(
  visible,
  async current => {
    if (!current) return;

    await nextTick();
    syncPositionToViewport();
  },
  { flush: 'post' }
);

watch(
  () => authStore.token,
  (currentToken, previousToken) => {
    if (!currentToken || currentToken === previousToken) return;

    position.value = getDefaultPosition();
    savePosition();
  }
);
</script>

<template>
  <Transition name="floating-post-entry">
    <div v-if="visible" class="floating-post-entry" :style="wrapperStyle">
      <div class="entry-orbit" :class="{ 'is-dragging': isDragging }">
        <div class="entry-card" :class="{ 'is-dragging': isDragging }">
          <span class="eyebrow">Campus Hub</span>
          <strong>交流广场</strong>
          <small>{{ isDragging ? '松手即可固定位置' : '点击进入，按住拖动位置' }}</small>
        </div>

        <button
          class="entry-button"
          :class="{ 'is-dragging': isDragging }"
          type="button"
          aria-label="进入交流广场"
          @pointerdown="handlePointerDown"
          @click="handleClick"
        >
          <span class="button-halo"></span>
          <span class="button-sheen"></span>
          <span class="button-core">
            <icon-solar:users-group-rounded-linear class="text-30px" />
          </span>
          <span class="drag-dot"></span>
        </button>
      </div>
    </div>
  </Transition>
</template>

<style scoped lang="scss">
.floating-post-entry {
  position: fixed;
  top: 0;
  left: 0;
  z-index: 90;
  touch-action: none;
}

.entry-orbit {
  position: relative;
  width: 72px;
  height: 72px;
}

.entry-card {
  position: absolute;
  top: 50%;
  right: calc(100% + 14px);
  display: flex;
  flex-direction: column;
  min-width: 158px;
  padding: 14px 16px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.8);
  box-shadow:
    0 18px 45px rgba(15, 23, 42, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.65);
  backdrop-filter: blur(18px);
  opacity: 0;
  pointer-events: none;
  transform: translateY(-50%) translateX(12px) scale(0.96);
  transform-origin: right center;
  transition:
    opacity 0.22s ease,
    transform 0.22s ease;
}

.entry-card .eyebrow {
  margin-bottom: 6px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #065F46;
}

.entry-card strong {
  font-size: 16px;
  font-weight: 700;
  line-height: 1.15;
  color: #0f172a;
}

.entry-card small {
  margin-top: 5px;
  font-size: 12px;
  line-height: 1.35;
  color: #64748b;
}

.entry-button {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  border: 0;
  border-radius: 50%;
  background: linear-gradient(145deg, #eff6ff, #f8fafc);
  box-shadow:
    0 20px 45px rgba(6, 95, 70, 0.24),
    0 10px 24px rgba(15, 23, 42, 0.12),
    inset 0 2px 0 rgba(255, 255, 255, 0.7);
  cursor: grab;
  isolation: isolate;
  overflow: hidden;
  transition:
    transform 0.22s ease,
    box-shadow 0.22s ease,
    filter 0.22s ease;
}

.entry-button:hover {
  transform: translateY(-3px) scale(1.03);
  box-shadow:
    0 24px 52px rgba(6, 95, 70, 0.3),
    0 12px 28px rgba(15, 23, 42, 0.16),
    inset 0 2px 0 rgba(255, 255, 255, 0.72);
}

.entry-button:active,
.entry-button.is-dragging {
  cursor: grabbing;
  transform: scale(1.02);
}

.button-halo {
  position: absolute;
  inset: -8px;
  border-radius: 50%;
  background:
    radial-gradient(circle, rgba(4, 120, 87, 0.28), transparent 58%),
    radial-gradient(circle at 70% 75%, rgba(34, 197, 94, 0.22), transparent 32%);
  opacity: 0.9;
  animation: halo-pulse 3.2s ease-in-out infinite;
}

.button-sheen {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.9), transparent 38%),
    linear-gradient(315deg, rgba(255, 255, 255, 0.08), rgba(255, 255, 255, 0));
}

.button-core {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 54px;
  height: 54px;
  border-radius: 50%;
  color: #fff;
  background: linear-gradient(135deg, #065F46 0%, #14B8A6 45%, #22c55e 100%);
  box-shadow:
    0 10px 24px rgba(6, 95, 70, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.28);
}

.drag-dot {
  position: absolute;
  right: 10px;
  bottom: 10px;
  z-index: 2;
  width: 10px;
  height: 10px;
  border: 2px solid rgba(255, 255, 255, 0.9);
  border-radius: 50%;
  background: #0F766E;
  box-shadow: 0 0 0 4px rgba(15, 118, 110, 0.14);
}

.entry-orbit:hover .entry-card,
.entry-orbit:focus-within .entry-card,
.entry-card.is-dragging,
.entry-orbit.is-dragging .entry-card {
  opacity: 1;
  transform: translateY(-50%) translateX(0) scale(1);
}

.floating-post-entry-enter-active,
.floating-post-entry-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}

.floating-post-entry-enter-from,
.floating-post-entry-leave-to {
  opacity: 0;
}

@keyframes halo-pulse {
  0%,
  100% {
    transform: scale(0.96);
    opacity: 0.72;
  }

  50% {
    transform: scale(1.06);
    opacity: 1;
  }
}

@media (max-width: 768px) {
  .entry-orbit {
    width: 62px;
    height: 62px;
  }

  .entry-card {
    right: auto;
    left: 50%;
    top: calc(100% + 12px);
    min-width: 148px;
    padding: 12px 14px;
    transform: translateX(-50%) translateY(-8px) scale(0.96);
    transform-origin: top center;
  }

  .entry-orbit:hover .entry-card,
  .entry-orbit.is-dragging .entry-card {
    transform: translateX(-50%) translateY(0) scale(1);
  }

  .entry-button {
    width: 62px;
    height: 62px;
  }

  .button-core {
    width: 48px;
    height: 48px;
  }
}
</style>

<script setup>
import { computed } from 'vue'
import { taskName, taskProgress, formatSize, formatSpeed, STATUS_META } from '@/api/client'

const props = defineProps({
  task: { type: Object, required: true }
})

const emit = defineEmits(['pause', 'unpause', 'remove', 'clear', 'peers', 'finish'])

const meta = computed(() => {
  // 做种任务(bt-seeder=true)显示"做种中"状态
  if (props.task['bt-seeder'] === true) return STATUS_META.seed
  return STATUS_META[props.task.status] || STATUS_META.waiting
})
const name = computed(() => taskName(props.task))
const progress = computed(() => taskProgress(props.task))

function fileCount() {
  const files = props.task.files
  return Array.isArray(files) ? files.length : 0
}
</script>

<template>
  <div class="task-card ink-halo" :class="'tone-' + meta.tone">
    <div class="task-main">
      <div class="task-name font-body-楷">
        <span class="brush-dot" :style="{ background: 'var(--ink-清)' }"></span>
        {{ name }}
      </div>

      <div class="task-meta">
        <span class="status-badge" :class="task.status">{{ meta.label }}</span>
        <span v-if="task.status === 'active' && Number(task.downloadSpeed) > 0" class="speed">
          墨流 {{ formatSpeed(task.downloadSpeed) }}
        </span>
        <span v-if="task.status === 'active' && Number(task.uploadSpeed) > 0" class="speed" style="color: var(--seal)">
          上传 {{ formatSpeed(task.uploadSpeed) }}
        </span>
        <span v-if="task.numSeeders !== undefined" class="size">Seeder {{ task.numSeeders }}</span>
        <span class="size">{{ formatSize(task.completedLength) }} / {{ formatSize(task.totalLength) }}</span>
        <span v-if="fileCount() > 1">共 {{ fileCount() }} 个文件</span>
        <span v-if="task.errorMessage" class="size" style="color: var(--seal)">{{ task.errorMessage }}</span>
      </div>

      <div class="brush-progress">
        <div class="fill" :style="{ width: progress + '%' }"></div>
      </div>
    </div>

    <div class="task-actions">
      <template v-if="task.status === 'active'">
        <button class="ink-btn ghost small" @click="emit('pause', task.gid)">暂停</button>
      </template>
      <template v-if="task.status === 'paused'">
        <button class="ink-btn ghost small" @click="emit('unpause', task.gid)">续行</button>
      </template>
      <template v-if="task.status === 'active' || task.status === 'waiting' || task.status === 'paused'">
        <button class="ink-btn ghost small" @click="emit('remove', task.gid)">移除</button>
      </template>
      <template v-if="task['bt-seeder'] === true || (task.numSeeders !== undefined && task.status === 'complete')">
        <button class="ink-btn ghost small" @click="emit('finish', task.gid)">结束</button>
      </template>
      <template v-if="task.numSeeders !== undefined">
        <button class="ink-btn ghost small" @click="emit('peers', task)">详情</button>
      </template>
      <template v-if="task.status === 'complete' || task.status === 'error' || task.status === 'removed'">
        <button class="ink-btn ghost small" @click="emit('clear', task.gid)">清墨</button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.brush-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>

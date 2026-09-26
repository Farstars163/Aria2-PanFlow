<script setup>
import { toasts } from '@/api/toast'
</script>

<template>
  <div class="ink-toasts">
    <transition-group name="toast">
      <div v-for="t in toasts.list" :key="t.id" class="ink-toast" :class="t.type">
        <span class="t-dot"></span>
        <span class="font-body-楷">{{ t.text }}</span>
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.toast-enter-active, .toast-leave-active { transition: all 0.3s ease; }
.toast-enter-from { opacity: 0; transform: translateX(30px); }
.toast-leave-to { opacity: 0; transform: translateY(-10px); }
</style>

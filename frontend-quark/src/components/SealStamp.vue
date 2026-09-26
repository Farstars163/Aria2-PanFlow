<script setup>
defineProps({
  text: { type: String, default: '墨' },
  size: { type: Number, default: 64 },
  shape: { type: String, default: 'square' } // square | round
})
</script>

<template>
  <div class="seal-stamp" :class="shape" :style="{ width: size + 'px', height: size + 'px' }">
    <span class="seal-text" :style="{ fontSize: Math.round(size * 0.38) + 'px' }">{{ text }}</span>
  </div>
</template>

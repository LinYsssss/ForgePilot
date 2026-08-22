<script setup lang="ts">
import AppShell from "./components/AppShell.vue";
import CyberParticleField from "./components/motion/CyberParticleField.vue";
</script>

<template>
  <div class="app-root">
    <div class="motion-atmosphere" aria-hidden="true">
      <div class="motion-grid"></div>
      <div class="motion-orb motion-orb-cyan"></div>
      <div class="motion-orb motion-orb-purple"></div>
      <div class="motion-orb motion-orb-emerald"></div>
      <div class="motion-scanlines"></div>
      <div class="motion-laser"></div>
    </div>
    <CyberParticleField />
    <AppShell />
  </div>
</template>

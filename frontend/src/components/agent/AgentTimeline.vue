<template><section class="panel"><h3>Agent Timeline</h3><ol><li v-for="step in steps" :key="step.id || step.sequenceNo"><b>{{ step.status }}</b> {{ step.stepType }} <small>{{ step.outputSummary }}</small></li></ol></section></template>
<script setup>defineProps({ steps: { type: Array, default: () => [] } })</script>

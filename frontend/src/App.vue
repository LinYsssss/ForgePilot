<template><router-view /></template>
<script setup>
import { onMounted, onUnmounted } from 'vue'
import { initCsrf, setUnauthorizedHandler } from './api/client.js'
import { useSession } from './composables/useSession.js'
import { useToast } from './composables/useToast.js'
import { useReviews } from './composables/useReviews.js'
import { useAgentWorkspace } from './composables/useAgentWorkspace.js'
import { useWorkspace } from './composables/useWorkspace.js'
const { authenticated, me } = useSession();const { toastMsg }=useToast();const {stopPolling}=useReviews();const {stopAgentPolling}=useAgentWorkspace();const {logout,loadMe,refreshAll}=useWorkspace()
setUnauthorizedHandler(()=>{if(!authenticated.value)return;logout(false);toastMsg('登录已过期，请重新登录','error')})
onMounted(async()=>{await initCsrf();try{await loadMe();authenticated.value=!!me.userId;if(authenticated.value)await refreshAll()}catch{authenticated.value=false}})
onUnmounted(()=>{stopPolling();stopAgentPolling()})
</script>

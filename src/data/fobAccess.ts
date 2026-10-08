import { computed } from 'vue'
import { currentAuthUser } from './authStore'

// Admin-only pilot. General quote/purchase/finance permissions do not grant FOB access.
export const canUseFob = computed(() => currentAuthUser.value.status === 'enabled' && currentAuthUser.value.role === 'super_admin')

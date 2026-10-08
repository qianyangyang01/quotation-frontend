import { computed } from 'vue'
import { currentAuthUser, hasPermission } from './authStore'

// Quote access and source maintenance are separate capabilities.
export const canMaintainFob = computed(() => currentAuthUser.value.status === 'enabled' && currentAuthUser.value.role === 'super_admin')
export const canUseFob = computed(() => canMaintainFob.value || hasPermission('quote'))

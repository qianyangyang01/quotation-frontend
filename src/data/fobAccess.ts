import { computed } from 'vue'
import { currentAuthUser, hasPermission } from './authStore'

// Quote access and source maintenance are separate capabilities.
export const canMaintainFob = computed(() => currentAuthUser.value.status === 'enabled' && ['super_admin', 'purchase'].includes(currentAuthUser.value.role))
export const canUseFob = computed(() => currentAuthUser.value.status === 'enabled' && (currentAuthUser.value.role === 'super_admin' || hasPermission('quote')))

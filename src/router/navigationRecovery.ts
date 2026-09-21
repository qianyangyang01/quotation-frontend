import { ref } from 'vue'
import type { Router } from 'vue-router'

export const failedNavigation = ref('')

export function isPageResourceError(error: unknown) {
  const message = error instanceof Error ? error.message : String(error)
  return /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Loading (?:CSS )?chunk .+ failed|Unable to preload CSS/i.test(message)
}

export function installNavigationRecovery(router: Router) {
  const removeError = router.onError((error, to) => {
    if (isPageResourceError(error)) failedNavigation.value = to.fullPath
  })
  const removeAfter = router.afterEach((_to, _from, failure) => {
    if (!failure) failedNavigation.value = ''
  })
  return () => { removeError(); removeAfter(); failedNavigation.value = '' }
}

export function reloadFailedPage() {
  const target = failedNavigation.value
  // Keep failed routes on this application; never reload automatically over unsaved work.
  if (target.startsWith('/') && !target.startsWith('//') && !target.includes('\\')) window.location.assign(target)
}

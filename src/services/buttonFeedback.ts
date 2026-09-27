/** One delegated listener covers routed pages, dialogs and keyboard activation. */
export function installButtonFeedback(root: Document = document) {
  const animations = new Map<HTMLElement, Animation>()
  const feedback = (event: MouseEvent) => {
    if (!(event.target instanceof Element)) return
    const button = event.target.closest<HTMLElement>('button, a[href], [role="button"]')
    if (!button || button.matches(':disabled, [aria-disabled="true"], [aria-busy="true"]') || button.closest('[inert]')) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches || typeof button.animate !== 'function') return
    animations.get(button)?.cancel()
    const animation = button.animate([{ scale: '0.97' }, { scale: '1' }], {
      duration: 240,
      easing: 'cubic-bezier(.2,.8,.2,1)',
    })
    animations.set(button, animation)
    const release = () => { if (animations.get(button) === animation) animations.delete(button) }
    animation.onfinish = release
    animation.oncancel = release
  }
  // Capture before an action disables its button or stops propagation.
  root.addEventListener('click', feedback, true)
  return () => {
    root.removeEventListener('click', feedback, true)
    animations.forEach(animation => animation.cancel())
    animations.clear()
  }
}

// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { installButtonFeedback } from './buttonFeedback'

let cleanup: (() => void) | undefined
afterEach(() => { cleanup?.(); document.body.innerHTML = ''; vi.restoreAllMocks() })

function setup(markup = '<button><span>查询</span></button>') {
  document.body.innerHTML = markup
  const button = document.body.firstElementChild as HTMLButtonElement
  const animation = { cancel: vi.fn(), onfinish: null, oncancel: null }
  const animate = vi.fn(() => animation)
  Object.defineProperty(button, 'animate', { value: animate })
  cleanup = installButtonFeedback()
  return { button, animate, animation }
}

it('animates nested clicks before the action disables the button without delaying the action', () => {
  const { button, animate } = setup()
  const action = vi.fn(() => { button.disabled = true })
  button.addEventListener('click', action)
  button.firstElementChild!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  expect(animate).toHaveBeenCalledOnce()
  expect(action).toHaveBeenCalledOnce()
  expect(button.disabled).toBe(true)
})

it('restarts feedback for repeated keyboard activation and cleans up on unmount', () => {
  const { button, animate, animation } = setup()
  button.click(); button.click()
  expect(animate).toHaveBeenCalledTimes(2)
  expect(animation.cancel).toHaveBeenCalledOnce()
  cleanup!()
  button.click()
  expect(animate).toHaveBeenCalledTimes(2)
  expect(animation.cancel).toHaveBeenCalledTimes(2)
})

it.each(['disabled', 'aria-disabled="true"', 'aria-busy="true"', 'inert'])('does not animate unavailable controls: %s', attribute => {
  const { button, animate } = setup(`<button ${attribute}>保存</button>`)
  button.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  expect(animate).not.toHaveBeenCalled()
})

it('honors reduced motion and still lets the action run', () => {
  vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList)
  const { button, animate } = setup()
  const action = vi.fn(); button.addEventListener('click', action); button.click()
  expect(animate).not.toHaveBeenCalled()
  expect(action).toHaveBeenCalledOnce()
})

it('includes navigation links used as actions', () => {
  const { button, animate } = setup('<a href="#quotation">再次发起</a>')
  button.click()
  expect(animate).toHaveBeenCalledOnce()
})

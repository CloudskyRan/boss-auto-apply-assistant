"use client"

import { useEffect, useRef } from "react"

/** Keep keyboard focus in the top dialog until the dialog series closes. */
export function useModalFocus(dialogId: string | null, onClose: () => void) {
  const onCloseRef = useRef(onClose)
  const stackRef = useRef<Array<{ id: string; origin: HTMLElement | null }>>([])
  const overflowRef = useRef<string | null>(null)
  onCloseRef.current = onClose

  const restore = () => {
    if (overflowRef.current !== null) {
      document.body.style.overflow = overflowRef.current
      overflowRef.current = null
    }
    const origin = stackRef.current[0]?.origin
    stackRef.current = []
    if (origin?.isConnected) origin.focus()
  }

  useEffect(() => {
    if (!dialogId) {
      restore()
      return
    }
    const dialog = document.getElementById(dialogId)
    if (!dialog) return
    if (overflowRef.current === null) {
      overflowRef.current = document.body.style.overflow
      document.body.style.overflow = "hidden"
    }
    const controls = () => Array.from(dialog.querySelectorAll<HTMLElement>(
      'button:not(:disabled), a[href], input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [tabindex="0"]'
    )).filter(element => element.getClientRects().length > 0)
    const focusFirst = () => (dialog.querySelector<HTMLElement>("[data-modal-initial-focus]") || controls()[0] || dialog).focus()
    const stack = stackRef.current
    const existingIndex = stack.findIndex(entry => entry.id === dialogId)
    if (existingIndex >= 0) {
      const previousOrigin = stack[existingIndex + 1]?.origin
      stack.splice(existingIndex + 1)
      if (previousOrigin?.isConnected && dialog.contains(previousOrigin)) previousOrigin.focus()
      else focusFirst()
    } else {
      stack.push({ id: dialogId, origin: document.activeElement as HTMLElement | null })
      focusFirst()
    }

    const handleKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault()
        event.stopPropagation()
        onCloseRef.current()
        return
      }
      if (event.key !== "Tab") return
      const targets = controls()
      const first = targets[0] || dialog
      const last = targets[targets.length - 1] || dialog
      if (!dialog.contains(document.activeElement)) {
        event.preventDefault()
        ;(event.shiftKey ? last : first).focus()
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    const handleFocus = (event: FocusEvent) => {
      if (!dialog.contains(event.target as Node)) focusFirst()
    }
    document.addEventListener("keydown", handleKey, true)
    document.addEventListener("focusin", handleFocus)
    return () => {
      document.removeEventListener("keydown", handleKey, true)
      document.removeEventListener("focusin", handleFocus)
    }
  }, [dialogId])

  useEffect(() => () => restore(), [])
}

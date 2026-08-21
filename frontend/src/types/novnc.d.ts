// Minimal typings for @novnc/novnc (ships no TypeScript declarations).
declare module '@novnc/novnc' {
  export interface RFBCredentials {
    username?: string
    password?: string
    target?: string
  }

  export interface RFBOptions {
    shared?: boolean
    credentials?: RFBCredentials
  }

  export default class RFB extends EventTarget {
    constructor(target: HTMLElement, url: string | WebSocket, options?: RFBOptions)
    disconnect(): void
    focus(options?: FocusOptions): void
    blur(): void
    sendKey(keysym: number, code: string | null, down?: boolean): void
    sendCtrlAltDel(): void
    clipboardPasteFrom(text: string): void
    scaleViewport: boolean
    resizeSession: boolean
    viewOnly: boolean
    focusOnClick: boolean
    background: string
  }
}

/**
 * Мост к оболочке — строго по apps/Splify2/BRIDGE.md.
 *
 *   страница → оболочка:  SplifyBridge.call(id, method, argsJson)
 *   оболочка → страница:  window.__splifyReply(id, replyJson)
 *   события:              window.__splifyEvent(name, payloadJson)
 *
 * Вызов не блокирует страницу: оболочка отвечает позже, со своего пула потоков, и по `id`
 * страница находит свой Promise. Когда `window.SplifyBridge` нет (браузер, `npm run dev`),
 * та же обёртка отвечает из заглушки mock.ts — экраны не знают, откуда пришёл ответ.
 */
import type { BridgeReply, EventName, Events, Method, Methods } from "./types"
import { handle as mockHandle } from "./mock"

interface NativeBridge {
  call(id: string, method: string, argsJson: string): void
}

declare global {
  interface Window {
    SplifyBridge?: NativeBridge
    __splifyReply?: (id: string, replyJson: string | BridgeReply) => void
    __splifyEvent?: (name: string, payloadJson: string | unknown) => void
  }
}

/** Отказ моста: код из договора и текст для человека, как его прислала оболочка. */
export class BridgeError extends Error {
  readonly code: string
  constructor(code: string, message?: string) {
    super(message || code)
    this.code = code
    this.name = "BridgeError"
  }
}

type Pending = { resolve: (v: unknown) => void; reject: (e: unknown) => void; timer: number }

const pending = new Map<string, Pending>()
let seq = 0

/** Оболочка отвечает всегда, но страница не должна висеть вечно, если ответ потерян
 *  (Activity пересоздалась посреди вызова). Обновление подписки и скачивание списков идут
 *  дольше прочего — их результат приходит событием, а сам вызов возвращается сразу. */
const TIMEOUT_MS = 60_000

/** Оболочка может передать JSON строкой (evaluateJavascript с литералом) или уже объектом. */
function parse<T>(v: string | T): T {
  return typeof v === "string" ? (JSON.parse(v) as T) : v
}

function onReply(id: string, replyJson: string | BridgeReply) {
  const p = pending.get(String(id))
  if (!p) return
  pending.delete(String(id))
  window.clearTimeout(p.timer)
  let reply: BridgeReply
  try {
    reply = parse<BridgeReply>(replyJson)
  } catch {
    p.reject(new BridgeError("internal", "Неразборчивый ответ приложения"))
    return
  }
  if (reply && reply.ok) p.resolve(reply.result)
  else p.reject(new BridgeError(reply?.error || "internal", reply && !reply.ok ? reply.message : undefined))
}

type Listener = (payload: unknown) => void
const listeners = new Map<string, Set<Listener>>()

function onEvent(name: string, payloadJson: string | unknown) {
  let payload: unknown
  try {
    payload = typeof payloadJson === "string" ? JSON.parse(payloadJson) : payloadJson
  } catch {
    return
  }
  listeners.get(name)?.forEach((fn) => {
    try {
      fn(payload)
    } catch (e) {
      console.error("splify event", name, e)
    }
  })
}

window.__splifyReply = onReply
window.__splifyEvent = onEvent

/** Есть ли настоящая оболочка. Без неё — заглушка. */
export const native = typeof window.SplifyBridge?.call === "function"

/**
 * Вызвать метод моста. Аргументы — объект (или ничего), результат — `result` ответа;
 * отказ — BridgeError с кодом из договора (`engine-down`, `unknown-method`…).
 */
export function call<M extends Method>(
  method: M,
  ...args: Methods[M][0] extends void ? [args?: Methods[M][0]] : [args: Methods[M][0]]
): Promise<Methods[M][1]> {
  const id = String(++seq)
  const argsJson = JSON.stringify(args[0] ?? {})
  return new Promise<Methods[M][1]>((resolve, reject) => {
    const timer = window.setTimeout(() => {
      if (pending.delete(id)) reject(new BridgeError("internal", "Приложение не ответило"))
    }, TIMEOUT_MS)
    pending.set(id, { resolve: resolve as (v: unknown) => void, reject, timer })
    if (native) {
      try {
        window.SplifyBridge!.call(id, method, argsJson)
      } catch (e) {
        pending.delete(id)
        window.clearTimeout(timer)
        reject(new BridgeError("internal", String(e)))
      }
      return
    }
    // Заглушка отвечает тем же путём, что оболочка: через window.__splifyReply.
    void mockHandle(id, method, argsJson, onReply, onEvent)
  })
}

/** Подписаться на событие оболочки; возвращает отписку. */
export function on<E extends EventName>(name: E, fn: (payload: Events[E]) => void): () => void {
  let set = listeners.get(name)
  if (!set) listeners.set(name, (set = new Set()))
  const l = fn as Listener
  set.add(l)
  return () => set!.delete(l)
}

/** Текст отказа для человека: `message` оболочки, а без него — по коду. */
export function errorText(e: unknown): string {
  if (e instanceof BridgeError) {
    if (e.message && e.message !== e.code) return e.message
    switch (e.code) {
      case "engine-down":
        return "Движок не отвечает"
      case "network":
        return "Нет связи с сервером"
      case "io":
        return "Не удалось прочитать или записать файл"
      case "unknown-method":
        return "Недоступно в этой версии приложения"
      default:
        return "Ошибка приложения"
    }
  }
  return e instanceof Error ? e.message : String(e)
}

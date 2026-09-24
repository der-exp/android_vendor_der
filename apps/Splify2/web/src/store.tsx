/**
 * Состояние экрана: что говорит движок, какая сеть, и модель настроек в двух снимках —
 * применённом и черновике.
 *
 * Правила и выходы правятся в черновике; в движок они уходят по пилюле «Применить»
 * (settings.put → spec.apply). Счётчик пилюли — разница черновика с ПРИМЕНЁННЫМ снимком,
 * а не число нажатий (контракт ApplyPill): вернули как было — пилюля исчезла. После
 * неудачного применения черновик уже сохранён, но снимок применённого не сдвигается, и
 * пилюля остаётся — с ней же и повтор.
 *
 * Состояние движка опрашивается, только пока страница видна: WebView живёт, пока открыт
 * экран, и фоновый опрос не нужен ни телефону, ни батарее.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { call, errorText, on } from "./bridge"
import type { EngineState, Model, NetworkInfo, PrivateDns, Status, CatalogEntry, Catalog } from "./types"

export type ApplyState = "idle" | "busy" | "done"

export interface ToastMsg {
  id: number
  tone: "ok" | "warn" | "bad"
  text: string
}

interface StoreValue {
  engine: EngineState | null
  engineError: string | null
  setEnabled: (on: boolean) => Promise<void>
  engineBusy: boolean
  status: Status | null
  statusError: string | null
  refreshStatus: () => void
  network: NetworkInfo | null
  pdns: PrivateDns | null
  draft: Model | null
  applied: Model | null
  modelError: string | null
  setDraft: (fn: (m: Model) => Model) => void
  replaceModel: (m: Model) => void
  reloadModel: () => Promise<void>
  changes: number
  apply: () => Promise<void>
  applyState: ApplyState
  applyError: { message: string; rolledBack: boolean } | null
  catalog: CatalogEntry[] | null
  catalogUpdated: number | undefined
  reloadCatalog: () => Promise<void>
  toasts: ToastMsg[]
  toast: (text: string, tone?: ToastMsg["tone"]) => void
}

const Ctx = createContext<StoreValue | null>(null)

export function useStore(): StoreValue {
  const v = useContext(Ctx)
  if (!v) throw new Error("useStore вне StoreProvider")
  return v
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)

/** Сколько правил и выходов отличаются от применённого. Правило сравнивается по месту:
 *  перестановка двух — два изменения, потому что у обоих сменился приоритет. */
export function countChanges(draft: Model | null, applied: Model | null): number {
  if (!draft || !applied) return 0
  let n = 0
  const dc = draft.channels, ac = applied.channels
  for (let i = 0; i < Math.max(dc.length, ac.length); i++) if (!same(dc[i], ac[i])) n++
  const keys = new Set([...Object.keys(draft.outputs), ...Object.keys(applied.outputs)])
  for (const k of keys) if (!same(draft.outputs[k], applied.outputs[k])) n++
  // Прочие поля модели (их знает логика, а не экран) — одним изменением.
  const rest = (m: Model) => ({ ...m, channels: undefined, outputs: undefined })
  if (!same(rest(draft), rest(applied))) n++
  return n
}

function normalizeCatalog(c: Catalog | CatalogEntry[]): Catalog {
  return Array.isArray(c) ? { lists: c } : { lists: c.lists ?? [], updated: c.updated }
}

const POLL_MS = 5000

export function StoreProvider({ children }: { children: ReactNode }) {
  const [engine, setEngine] = useState<EngineState | null>(null)
  const [engineError, setEngineError] = useState<string | null>(null)
  const [engineBusy, setEngineBusy] = useState(false)
  const [status, setStatus] = useState<Status | null>(null)
  const [statusError, setStatusError] = useState<string | null>(null)
  const [network, setNetwork] = useState<NetworkInfo | null>(null)
  const [pdns, setPdns] = useState<PrivateDns | null>(null)
  const [draft, setDraftState] = useState<Model | null>(null)
  const [applied, setApplied] = useState<Model | null>(null)
  const [modelError, setModelError] = useState<string | null>(null)
  const [applyState, setApplyState] = useState<ApplyState>("idle")
  const [applyError, setApplyError] = useState<{ message: string; rolledBack: boolean } | null>(null)
  const [catalog, setCatalog] = useState<CatalogEntry[] | null>(null)
  const [catalogUpdated, setCatalogUpdated] = useState<number | undefined>()
  const [toasts, setToasts] = useState<ToastMsg[]>([])
  const toastSeq = useRef(0)

  const toast = useCallback((text: string, tone: ToastMsg["tone"] = "ok") => {
    const id = ++toastSeq.current
    setToasts((t) => [...t, { id, tone, text }])
    window.setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3200)
  }, [])

  const refreshStatus = useCallback((fast = false) => {
    call("engine.status", fast ? { fast: true } : undefined)
      .then((s) => {
        setStatus(s)
        setStatusError(null)
      })
      .catch((e) => setStatusError(errorText(e)))
    call("system.privateDns").then(setPdns).catch(() => {})
  }, [])

  const reloadModel = useCallback(async () => {
    try {
      const m = await call("settings.get")
      setDraftState(m)
      setApplied(m)
      setModelError(null)
    } catch (e) {
      setModelError(errorText(e))
    }
  }, [])

  const reloadCatalog = useCallback(async () => {
    try {
      const c = normalizeCatalog(await call("lists.catalog"))
      setCatalog(c.lists)
      setCatalogUpdated(c.updated)
    } catch {
      setCatalog((c) => c ?? [])
    }
  }, [])

  // Первая загрузка и события оболочки.
  useEffect(() => {
    call("engine.state").then(setEngine).catch((e) => setEngineError(errorText(e)))
    call("system.network").then(setNetwork).catch(() => {})
    refreshStatus(true)
    void reloadModel()
    void reloadCatalog()
    const offs = [
      on("engine.changed", (s) => {
        setEngine(s)
        refreshStatus()
      }),
      on("network.changed", setNetwork),
      on("lists.updated", () => void reloadCatalog()),
    ]
    return () => offs.forEach((f) => f())
  }, [refreshStatus, reloadModel, reloadCatalog])

  // Опрос состояния, пока страница видна.
  useEffect(() => {
    let timer = 0
    const tick = () => {
      if (document.visibilityState === "visible") refreshStatus()
    }
    const start = () => {
      window.clearInterval(timer)
      timer = window.setInterval(tick, POLL_MS)
    }
    const onVis = () => {
      if (document.visibilityState === "visible") {
        tick()
        start()
      } else window.clearInterval(timer)
    }
    start()
    document.addEventListener("visibilitychange", onVis)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener("visibilitychange", onVis)
    }
  }, [refreshStatus])

  const setEnabled = useCallback(
    async (onoff: boolean) => {
      setEngineBusy(true)
      try {
        setEngine(await call("engine.setEnabled", { on: onoff }))
        setEngineError(null)
        refreshStatus()
      } catch (e) {
        toast(errorText(e), "bad")
      } finally {
        setEngineBusy(false)
      }
    },
    [refreshStatus, toast],
  )

  const setDraft = useCallback((fn: (m: Model) => Model) => {
    setDraftState((m) => (m ? fn(structuredClone(m)) : m))
  }, [])

  const replaceModel = useCallback((m: Model) => {
    setDraftState(m)
    setApplied(m)
  }, [])

  const apply = useCallback(async () => {
    if (!draft) return
    setApplyState("busy")
    setApplyError(null)
    try {
      await call("settings.put", draft)
      const r = await call("spec.apply")
      if (r.applied) {
        setApplied(draft)
        setApplyState("done")
        window.setTimeout(() => setApplyState("idle"), 1400)
        refreshStatus()
      } else {
        setApplyState("idle")
        setApplyError({ message: r.message || "Движок не принял правила", rolledBack: !!r.rolled_back })
        toast("Не применилось", "bad")
      }
    } catch (e) {
      setApplyState("idle")
      setApplyError({ message: errorText(e), rolledBack: false })
      toast("Не применилось", "bad")
    }
  }, [draft, refreshStatus, toast])

  const changes = useMemo(() => countChanges(draft, applied), [draft, applied])

  const value: StoreValue = {
    engine,
    engineError,
    setEnabled,
    engineBusy,
    status,
    statusError,
    refreshStatus: () => refreshStatus(),
    network,
    pdns,
    draft,
    applied,
    modelError,
    setDraft,
    replaceModel,
    reloadModel,
    changes,
    apply,
    applyState,
    applyError,
    catalog,
    catalogUpdated,
    reloadCatalog,
    toasts,
    toast,
  }
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

/** Имя и вид списка каталога по id — для подписей правил. */
export function useListInfo() {
  const { catalog } = useStore()
  return useMemo(() => {
    const byId = new Map((catalog ?? []).map((e) => [e.id, e]))
    return {
      name: (id: string) => byId.get(id)?.name ?? id,
      kind: (id: string) => byId.get(id)?.kind,
    }
  }, [catalog])
}

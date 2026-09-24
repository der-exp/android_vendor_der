/**
 * Состояние экрана: что говорит движок, какая сеть, и модель настроек в двух снимках —
 * применённом и черновике.
 *
 * Черновик — только правила и выходы: они уходят в движок по пилюле «Применить»
 * (settings.put → spec.apply), и в settings.put идут ТОЛЬКО они — логика принимает часть
 * модели. Всё прочее (свои списки, выбор каталога, настройка обновления, подписки)
 * сохраняется сразу своими методами; отправь экран модель целиком из снимка черновика — он
 * затёр бы то, что человек сохранил после снимка (свой список, созданный из редактора
 * правила). Счётчик пилюли — разница черновика с ПРИМЕНЁННЫМ снимком, а не число нажатий
 * (контракт ApplyPill): вернули как было — пилюля исчезла. После неудачного применения
 * черновик уже сохранён, но снимок применённого не сдвигается, и пилюля остаётся — с ней же
 * и повтор.
 *
 * Состояние движка опрашивается, только пока страница видна: WebView живёт, пока открыт
 * экран, и фоновый опрос не нужен ни телефону, ни батарее.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { call, errorText, on } from "./bridge"
import type { Catalog, CatalogItem, CustomList, EngineState, ListKind, Model, NetworkInfo, PrivateDns, Status } from "./types"

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
  /** Черновик из загруженной копии: применённый снимок не трогается — пилюля покажет разницу. */
  loadDraft: (m: Model) => void
  reloadModel: () => Promise<void>
  /** Поле модели вне черновика — сохраняется сразу (settings.put частью). */
  saveSetting: (patch: Pick<Partial<Model>, "update" | "tether" | "catalog_url">) => Promise<boolean>
  changes: number
  apply: () => Promise<void>
  applyState: ApplyState
  applyError: { message: string; rolledBack: boolean } | null
  /** Что логика пропустила при последнем применении (список не скачан и т. п.). */
  applyNotes: string[]
  catalog: CatalogItem[] | null
  catalogInfo: Pick<Catalog, "updated" | "last_update"> | null
  catalogError: string | null
  reloadCatalog: () => Promise<void>
  custom: CustomList[] | null
  reloadCustom: () => Promise<void>
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
 *  перестановка двух — два изменения, потому что у обоих сменился приоритет. Выход — по
 *  имени: порядок выходов на поведение не влияет. */
export function countChanges(draft: Model | null, applied: Model | null): number {
  if (!draft || !applied) return 0
  let n = 0
  const dc = draft.channels, ac = applied.channels
  for (let i = 0; i < Math.max(dc.length, ac.length); i++) if (!same(dc[i], ac[i])) n++
  const by = (m: Model) => new Map(m.outputs.map((o) => [o.name, o]))
  const d = by(draft), a = by(applied)
  for (const k of new Set([...d.keys(), ...a.keys()])) if (!same(d.get(k), a.get(k))) n++
  return n
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
  const [applyNotes, setApplyNotes] = useState<string[]>([])
  const [catalog, setCatalog] = useState<CatalogItem[] | null>(null)
  const [catalogInfo, setCatalogInfo] = useState<Pick<Catalog, "updated" | "last_update"> | null>(null)
  const [catalogError, setCatalogError] = useState<string | null>(null)
  const [custom, setCustom] = useState<CustomList[] | null>(null)
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
      const c = await call("lists.catalog")
      setCatalog(c.items ?? [])
      setCatalogInfo({ updated: c.updated, last_update: c.last_update })
      setCatalogError(null)
    } catch (e) {
      setCatalog((c) => c ?? [])
      setCatalogError(errorText(e))
    }
  }, [])

  const reloadCustom = useCallback(async () => {
    try {
      const r = await call("lists.custom")
      setCustom(Array.isArray(r) ? r : [])
    } catch {
      setCustom((c) => c ?? [])
    }
  }, [])

  // Первая загрузка и события оболочки.
  useEffect(() => {
    call("engine.state").then(setEngine).catch((e) => setEngineError(errorText(e)))
    call("system.network").then(setNetwork).catch(() => {})
    refreshStatus(true)
    void reloadModel()
    void reloadCatalog()
    void reloadCustom()
    const offs = [
      on("engine.changed", (s) => {
        setEngine(s)
        refreshStatus()
      }),
      on("network.changed", setNetwork),
      on("lists.updated", () => void reloadCatalog()),
    ]
    return () => offs.forEach((f) => f())
  }, [refreshStatus, reloadModel, reloadCatalog, reloadCustom])

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

  const loadDraft = useCallback((m: Model) => setDraftState(m), [])

  const saveSetting = useCallback(
    async (patch: Pick<Partial<Model>, "update" | "tether" | "catalog_url">) => {
      try {
        await call("settings.put", patch)
        // Поле вне черновика: в обоих снимках сразу, чтобы пилюля его не считала.
        setDraftState((m) => (m ? { ...m, ...patch } : m))
        setApplied((m) => (m ? { ...m, ...patch } : m))
        return true
      } catch (e) {
        toast(errorText(e), "bad")
        return false
      }
    },
    [toast],
  )

  const apply = useCallback(async () => {
    if (!draft) return
    setApplyState("busy")
    setApplyError(null)
    setApplyNotes([])
    try {
      await call("settings.put", { outputs: draft.outputs, channels: draft.channels })
      const r = await call("spec.apply")
      setApplyNotes(r.warnings ?? [])
      if (r.saved || r.applied) {
        setApplied(draft)
        setApplyState("done")
        window.setTimeout(() => setApplyState("idle"), 1400)
        // Сохранено, но не поставлено (движок выключен) — это состояние, а не отказ.
        if (!r.applied && r.message) toast(r.message, "warn")
        refreshStatus()
        void reloadCatalog()
      } else {
        setApplyState("idle")
        setApplyError({ message: r.message || "Правила не применились", rolledBack: !!r.rolled_back })
        toast("Не применилось", "bad")
      }
    } catch (e) {
      setApplyState("idle")
      setApplyError({ message: errorText(e), rolledBack: false })
      toast("Не применилось", "bad")
    }
  }, [draft, refreshStatus, reloadCatalog, toast])

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
    loadDraft,
    reloadModel,
    saveSetting,
    changes,
    apply,
    applyState,
    applyError,
    applyNotes,
    catalog,
    catalogInfo,
    catalogError,
    reloadCatalog,
    custom,
    reloadCustom,
    toasts,
    toast,
  }
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

/** Имя и виды службы каталога по id — для подписей правил. */
export function useListInfo() {
  const { catalog, custom } = useStore()
  return useMemo(() => {
    const byId = new Map((catalog ?? []).map((e) => [e.id, e]))
    const byName = new Map((custom ?? []).map((c) => [c.name, c]))
    return {
      name: (id: string) => byId.get(id)?.name ?? id,
      kinds: (id: string): ListKind[] | undefined => byId.get(id)?.kinds,
      customHasDomains: (name: string) => (byName.get(name)?.domains.length ?? 1) > 0,
    }
  }, [catalog, custom])
}

/** Порядок издателей каталога и подписи групп: как пришли, без издателя — в конце. */
export function groupBySource<T extends { source?: string }>(items: T[]): [string, T[]][] {
  const g = new Map<string, T[]>()
  for (const e of items) {
    const k = e.source || "Другие"
    g.set(k, [...(g.get(k) ?? []), e])
  }
  const other = g.get("Другие")
  g.delete("Другие")
  if (other) g.set("Другие", other)
  return [...g.entries()]
}

/**
 * Навигация: пять разделов нижней панели и подэкраны внутри раздела (правка правила,
 * список каталога, подписки…). Подэкран кладётся в историю страницы, чтобы системная
 * кнопка «Назад» закрывала его, а не приложение: оболочка отдаёт «Назад» в WebView
 * (goBack), пока у страницы есть куда вернуться.
 */
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react"
import type { ModelChannel } from "./types"

export type Tab = "home" | "outputs" | "rules" | "conns" | "more"

/** Заготовка нового правила (из «Соединений»): поля правила и, если нужно, строки для своего
 *  списка — в модели правило ссылается на списки, а не держит домены в себе (Model.kt). */
export type RulePrefill = Partial<ModelChannel> & { listText?: string }

export type Sub =
  | { kind: "rule"; index: number; prefill?: RulePrefill }
  | { kind: "lists" }
  | { kind: "custom" }
  | { kind: "subs" }
  | { kind: "backup" }
  | { kind: "engine" }

export interface Route {
  tab: Tab
  sub?: Sub
}

interface NavValue {
  route: Route
  go: (tab: Tab) => void
  open: (sub: Sub, tab?: Tab) => void
  back: () => void
}

const Ctx = createContext<NavValue | null>(null)

export function useNav(): NavValue {
  const v = useContext(Ctx)
  if (!v) throw new Error("useNav вне NavProvider")
  return v
}

function initial(): Route {
  const t = new URLSearchParams(location.search).get("tab") as Tab | null
  return { tab: t && ["home", "outputs", "rules", "conns", "more"].includes(t) ? t : "home" }
}

export function NavProvider({ children }: { children: ReactNode }) {
  const [route, setRoute] = useState<Route>(initial)

  useEffect(() => {
    history.replaceState({ route }, "")
    const onPop = (e: PopStateEvent) => {
      const r = (e.state as { route?: Route } | null)?.route
      setRoute((cur) => r ?? { tab: cur.tab })
    }
    window.addEventListener("popstate", onPop)
    return () => window.removeEventListener("popstate", onPop)
  }, [])

  const go = useCallback((tab: Tab) => {
    const r: Route = { tab }
    history.replaceState({ route: r }, "")
    setRoute(r)
    window.scrollTo(0, 0)
  }, [])

  const open = useCallback((sub: Sub, tab?: Tab) => {
    setRoute((cur) => {
      const r: Route = { tab: tab ?? cur.tab, sub }
      // Раздел, из которого открыли, остаётся в истории без подэкрана: «Назад» вернёт туда.
      history.replaceState({ route: { tab: cur.tab } }, "")
      history.pushState({ route: r }, "")
      return r
    })
    window.scrollTo(0, 0)
  }, [])

  const back = useCallback(() => {
    if (history.state?.route?.sub) history.back()
    else setRoute((cur) => ({ tab: cur.tab }))
  }, [])

  return <Ctx.Provider value={{ route, go, open, back }}>{children}</Ctx.Provider>
}

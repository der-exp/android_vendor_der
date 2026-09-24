import { useEffect, useState } from "react"
import { ApplyPill, Toast, ToastStack } from "@andromeda/ui"
import { Icon, type IconName } from "./icons"
import { NavProvider, useNav, type Tab } from "./nav"
import { StoreProvider, useStore } from "./store"
import { Home } from "./screens/Home"
import { Outputs } from "./screens/Outputs"
import { Rules } from "./screens/Rules"
import { Conns } from "./screens/Conns"
import { More } from "./screens/More"

/** Тема — системная: светлая или тёмная по prefers-color-scheme, с подпиской на смену
 *  (ночной режим телефона переключается сам). Атрибут data-theme ставится на <html>,
 *  чтобы тёмные токены действовали и на фон за пределами .an-root. */
function useSystemTheme(): boolean {
  const mq = window.matchMedia("(prefers-color-scheme: dark)")
  const [dark, setDark] = useState(mq.matches)
  useEffect(() => {
    const fn = (e: MediaQueryListEvent) => setDark(e.matches)
    mq.addEventListener("change", fn)
    return () => mq.removeEventListener("change", fn)
  }, [mq])
  useEffect(() => {
    const root = document.documentElement
    if (dark) root.setAttribute("data-theme", "dark")
    else root.removeAttribute("data-theme")
    root.style.colorScheme = dark ? "dark" : "light"
  }, [dark])
  return dark
}

const TABS: { tab: Tab; label: string; icon: IconName }[] = [
  { tab: "home", label: "Главная", icon: "home" },
  { tab: "outputs", label: "Выходы", icon: "outputs" },
  { tab: "rules", label: "Правила", icon: "rules" },
  { tab: "conns", label: "Соединения", icon: "conns" },
  { tab: "more", label: "Ещё", icon: "more" },
]

/** Нижняя панель — мобильная форма рельса Andromeda (шаблон Mobile дизайн-системы). */
function BottomNav() {
  const { route, go } = useNav()
  const { changes } = useStore()
  return (
    <nav
      style={{
        position: "fixed",
        left: 0,
        right: 0,
        bottom: 0,
        zIndex: 30,
        display: "flex",
        borderTop: "1px solid var(--an-border)",
        background: "var(--an-surface-card)",
        padding: "0 env(safe-area-inset-right, 0px) env(safe-area-inset-bottom, 0px) env(safe-area-inset-left, 0px)",
      }}
    >
      {TABS.map((t) => {
        const active = route.tab === t.tab
        const dirty = t.tab === "rules" && changes > 0
        return (
          <button
            key={t.tab}
            type="button"
            onClick={() => go(t.tab)}
            aria-current={active ? "page" : undefined}
            style={{
              flex: 1,
              minWidth: 0,
              height: "var(--sp-nav-h)",
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              gap: 3,
              border: 0,
              background: "transparent",
              font: "var(--an-text-micro)",
              fontWeight: "var(--an-weight-medium)",
              color: active ? "var(--an-accent)" : "var(--an-text-muted)",
              cursor: "pointer",
              position: "relative",
            }}
          >
            <span style={{ position: "relative" }}>
              <Icon name={t.icon} size={20} />
              {dirty ? (
                <span style={{ position: "absolute", top: -2, right: -4, width: 8, height: 8, borderRadius: "var(--an-radius-dot)", background: "var(--an-accent)", border: "2px solid var(--an-surface-card)" }} />
              ) : null}
            </span>
            <span style={{ maxWidth: "100%", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{t.label}</span>
          </button>
        )
      })}
    </nav>
  )
}

function Screens() {
  const { route } = useNav()
  switch (route.tab) {
    case "home":
      return <Home />
    case "outputs":
      return <Outputs />
    case "rules":
      return <Rules />
    case "conns":
      return <Conns />
    case "more":
      return <More />
  }
}

function Floating() {
  const { changes, applyState, apply, toasts, draft } = useStore()
  const lift = "calc(var(--sp-nav-h) + env(safe-area-inset-bottom, 0px) + 14px)"
  return (
    <>
      {draft ? <ApplyPill changes={changes} state={applyState} onApply={apply} style={{ bottom: lift }} /> : null}
      <ToastStack style={{ left: 16, right: 16, bottom: `calc(${lift} + 60px)`, alignItems: "stretch" }}>
        {toasts.map((t) => (
          <Toast key={t.id} tone={t.tone}>
            {t.text}
          </Toast>
        ))}
      </ToastStack>
    </>
  )
}

export function App() {
  useSystemTheme()
  return (
    <div className="an-root" style={{ minHeight: "100vh", background: "var(--an-surface-page)" }}>
      <NavProvider>
        <StoreProvider>
          <Screens />
          <Floating />
          <BottomNav />
        </StoreProvider>
      </NavProvider>
    </div>
  )
}

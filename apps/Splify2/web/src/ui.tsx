/**
 * Раскладка телефона поверх примитивов Andromeda: шапка экрана, строка-кнопка, пустое
 * состояние, значок приложения, сегменты на 44 px. Всё — пропсами и токенами, без своих
 * классов и своих цветов (идиома пакета).
 */
import { useState, type CSSProperties, type ReactNode } from "react"
import { StatusDot } from "@andromeda/ui"
import { Icon, type IconName } from "./icons"
import type { Tone } from "./format"

export const col = (gap: string = "var(--an-gap-block)"): CSSProperties => ({ display: "flex", flexDirection: "column", gap })
export const rowS = (gap: string = "var(--an-gap-inline)"): CSSProperties => ({ display: "flex", alignItems: "center", gap, minWidth: 0 })
export const muted: CSSProperties = { font: "var(--an-text-caption)", color: "var(--an-text-muted)" }
export const ellipsis: CSSProperties = { overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", minWidth: 0 }
export const mono: CSSProperties = { font: "var(--an-text-code)", color: "var(--an-text-secondary)", wordBreak: "break-all" }

/** Шапка экрана: залипает сверху, под системной строкой. `back` — стрелка назад. */
export function Header({ title, back, right }: { title: ReactNode; back?: () => void; right?: ReactNode }) {
  return (
    <header
      style={{
        position: "sticky",
        top: 0,
        zIndex: 20,
        display: "flex",
        alignItems: "center",
        gap: "var(--an-space-3)",
        minHeight: 56,
        padding: "calc(var(--sp-inset-top) + 6px) max(16px, var(--sp-inset-right)) 6px max(16px, var(--sp-inset-left))",
        background: "color-mix(in srgb, var(--an-surface-page) 86%, transparent)",
        backdropFilter: "blur(8px)",
        WebkitBackdropFilter: "blur(8px)",
        borderBottom: "1px solid var(--an-border)",
      }}
    >
      {back ? (
        <button
          type="button"
          onClick={back}
          aria-label="Назад"
          style={{ width: 44, height: 44, marginLeft: -12, display: "inline-flex", alignItems: "center", justifyContent: "center", border: 0, background: "transparent", color: "var(--an-text)", borderRadius: "var(--an-radius-inner)", cursor: "pointer" }}
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M19 12H5M12 19l-7-7 7-7" /></svg>
        </button>
      ) : null}
      <h1 style={{ flex: 1, minWidth: 0, font: "var(--an-text-title)", fontSize: 20, letterSpacing: "var(--an-tracking-title)", ...ellipsis }}>{title}</h1>
      {right}
    </header>
  )
}

/** Тело экрана: колонка карточек с отступом под нижнюю панель и пилюлю. */
export function Body({ children, bottom = true }: { children: ReactNode; bottom?: boolean }) {
  return (
    <main
      style={{
        ...col(),
        padding: `14px max(16px, var(--sp-inset-right)) ${bottom ? "calc(var(--sp-nav-h) + var(--sp-inset-bottom) + 84px)" : "calc(var(--sp-inset-bottom) + 24px)"} max(16px, var(--sp-inset-left))`,
      }}
    >
      {children}
    </main>
  )
}

/** Заголовок карточки со счётчиком или действием справа. */
export function CardHead({ title, meta, action }: { title: ReactNode; meta?: ReactNode; action?: ReactNode }) {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "var(--an-space-6)", minHeight: 24 }}>
      <h2 style={{ font: "var(--an-text-heading)", ...ellipsis }}>{title}</h2>
      {meta ? <span style={{ ...muted, flex: "0 0 auto" }}>{meta}</span> : null}
      {action}
    </div>
  )
}

/** Строка-кнопка на всю ширину: значок, заголовок, пояснение, правый край, шеврон. */
export function TapRow({
  icon,
  title,
  subtitle,
  right,
  onClick,
  chevron = true,
  dot,
}: {
  icon?: IconName
  title: ReactNode
  subtitle?: ReactNode
  right?: ReactNode
  onClick?: () => void
  chevron?: boolean
  dot?: Tone
}) {
  return (
    <button
      type="button"
      className="sp-press"
      onClick={onClick}
      style={{
        ...rowS("var(--an-space-6)"),
        width: "100%",
        minHeight: 52,
        padding: "8px 10px",
        margin: "0 -10px",
        boxSizing: "content-box",
        border: 0,
        borderRadius: "var(--an-radius-inner)",
        background: "transparent",
        color: "var(--an-text)",
        textAlign: "left",
        cursor: onClick ? "pointer" : "default",
      }}
    >
      {icon ? <span style={{ color: "var(--an-text-secondary)" }}><Icon name={icon} size={18} /></span> : null}
      {dot ? <StatusDot tone={dot} /> : null}
      <span style={{ ...col("2px"), flex: 1, minWidth: 0 }}>
        <span style={{ font: "var(--an-text-body)", fontWeight: "var(--an-weight-medium)", ...ellipsis }}>{title}</span>
        {subtitle ? <span style={{ font: "var(--an-text-body-sm)", color: "var(--an-text-secondary)", ...ellipsis }}>{subtitle}</span> : null}
      </span>
      {right ? <span style={{ ...muted, flex: "0 0 auto", textAlign: "right" }}>{right}</span> : null}
      {chevron && onClick ? <span style={{ color: "var(--an-text-muted)" }}><Icon name="chevron" /></span> : null}
    </button>
  )
}

/** Разделитель строк внутри карточки. */
export function Divider() {
  return <div style={{ height: 1, background: "var(--an-border-soft)" }} />
}

/** Пустое состояние: значок и одна строка. */
export function Empty({ icon, text, action }: { icon: IconName; text: ReactNode; action?: ReactNode }) {
  return (
    <div style={{ ...col("var(--an-space-5)"), alignItems: "center", padding: "28px 8px", textAlign: "center", color: "var(--an-text-muted)" }}>
      <Icon name={icon} size={22} />
      <div style={{ font: "var(--an-text-body-sm)", color: "var(--an-text-secondary)" }}>{text}</div>
      {action}
    </div>
  )
}

/** Два-четыре варианта в строку, 44 px по высоте. Вид — как у SegmentedControl пакета;
 *  своя реализация только потому, что у того высота кнопок зашита в 32 px. */
export function Segmented<T extends string>({
  items,
  value,
  onChange,
}: {
  items: { value: T; label: ReactNode }[]
  value: T
  onChange: (v: T) => void
}) {
  return (
    <div style={{ display: "flex", gap: "var(--an-space-1)", padding: 2, borderRadius: "var(--an-radius-control)", background: "var(--an-surface-field)" }}>
      {items.map((it) => {
        const on = it.value === value
        return (
          <button
            key={it.value}
            type="button"
            aria-pressed={on}
            onClick={() => onChange(it.value)}
            style={{
              flex: 1,
              minWidth: 0,
              height: 40,
              padding: "0 6px",
              display: "inline-flex",
              alignItems: "center",
              justifyContent: "center",
              gap: "var(--an-space-3)",
              border: 0,
              borderRadius: "var(--an-radius-inner)",
              background: on ? "var(--an-accent)" : "transparent",
              color: on ? "var(--an-text-on-accent)" : "var(--an-text-secondary)",
              font: "var(--an-text-body-sm)",
              fontWeight: on ? "var(--an-weight-medium)" : "var(--an-weight-regular)",
              cursor: "pointer",
              transition: "all var(--an-dur-control) var(--an-ease)",
              whiteSpace: "nowrap",
              overflow: "hidden",
            }}
          >
            {it.label}
          </button>
        )
      })}
    </div>
  )
}

/** Значок приложения: картинку отдаёт оболочка по адресу appassets; без неё (браузер,
 *  приложение без значка) — первая буква на плашке. */
export function AppIcon({ pkg, label, size = 32 }: { pkg?: string; label: string; size?: number }) {
  const [failed, setFailed] = useState(!pkg)
  const box: CSSProperties = { width: size, height: size, flex: "0 0 auto", borderRadius: "var(--an-radius-inner)" }
  if (!failed && pkg)
    return (
      <img
        src={`https://appassets.androidplatform.net/icon/${encodeURIComponent(pkg)}.png`}
        alt=""
        width={size}
        height={size}
        loading="lazy"
        onError={() => setFailed(true)}
        style={box}
      />
    )
  return (
    <span
      aria-hidden="true"
      style={{
        ...box,
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        background: "var(--an-accent-soft)",
        color: "var(--an-accent)",
        font: "var(--an-text-heading)",
        fontSize: Math.round(size * 0.45),
      }}
    >
      {label.trim().charAt(0).toUpperCase() || "?"}
    </span>
  )
}

/** Полоса «подпись — значение» в карточке. */
export function KV({ k, v }: { k: ReactNode; v: ReactNode }) {
  return (
    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: "var(--an-space-6)", minHeight: 24 }}>
      <span style={{ font: "var(--an-text-body-sm)", color: "var(--an-text-secondary)", flex: "0 0 auto" }}>{k}</span>
      <span style={{ font: "var(--an-text-body-sm)", color: "var(--an-text)", textAlign: "right", ...ellipsis }}>{v}</span>
    </div>
  )
}

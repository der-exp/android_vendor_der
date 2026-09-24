/**
 * Выбор приложений и списков для правила — полноэкранные страницы поверх редактора.
 * Выбор меняет правило сразу; «Назад» (стрелка или системная кнопка) закрывает страницу.
 */
import { useEffect, useMemo, useState, type ReactNode } from "react"
import { Button, Input, Skeleton, Switch } from "@andromeda/ui"
import { loadApps } from "../apps"
import { errorText } from "../bridge"
import { groupBySource, useStore } from "../store"
import { AppIcon, Body, Empty, Header, col, ellipsis, muted, rowS } from "../ui"
import { Icon } from "../icons"
import { fmtInt } from "../format"
import type { AppInfo, CatalogItem, CustomList } from "../types"

/** Полноэкранный слой поверх раздела, закрывает и нижнюю панель. */
export function Overlay({ title, onBack, children }: { title: string; onBack: () => void; children: ReactNode }) {
  return (
    <div style={{ position: "fixed", inset: 0, zIndex: 50, overflowY: "auto", background: "var(--an-surface-page)", animation: "an-fade 160ms var(--an-ease)" }}>
      <Header title={title} back={onBack} />
      <Body bottom={false}>{children}</Body>
    </div>
  )
}

export function PickRow({ on, onClick, lead, title, sub, meta }: { on: boolean; onClick: () => void; lead?: ReactNode; title: ReactNode; sub?: ReactNode; meta?: ReactNode }) {
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={on}
      onClick={onClick}
      className="sp-press"
      style={{ ...rowS("var(--an-space-6)"), width: "100%", minHeight: 56, padding: "6px 8px", border: 0, borderRadius: "var(--an-radius-inner)", background: "transparent", color: "var(--an-text)", textAlign: "left", cursor: "pointer" }}
    >
      {lead}
      <span style={{ ...col("1px"), flex: 1, minWidth: 0 }}>
        <span style={{ font: "var(--an-text-body)", ...ellipsis }}>{title}</span>
        {sub ? <span style={{ font: "var(--an-text-caption)", color: "var(--an-text-muted)", ...ellipsis }}>{sub}</span> : null}
      </span>
      {meta ? <span style={{ ...muted, flex: "0 0 auto" }}>{meta}</span> : null}
      <span
        style={{
          width: 22,
          height: 22,
          flex: "0 0 auto",
          borderRadius: 6,
          border: `1.5px solid ${on ? "var(--an-accent)" : "var(--an-control-line)"}`,
          background: on ? "var(--an-accent)" : "transparent",
          color: "var(--an-text-on-accent)",
          display: "inline-flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        {on ? <Icon name="check" size={14} /> : null}
      </span>
    </button>
  )
}

export function Search({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return (
    <Input
      icon={<Icon name="search" />}
      placeholder={placeholder}
      value={value}
      onInput={(e) => onChange(e.currentTarget.value)}
      type="search"
      enterKeyHint="search"
      style={{ background: "var(--an-surface-card)" }}
    />
  )
}

export function AppPicker({ uids, onChange, onBack }: { uids: number[]; onChange: (uids: number[]) => void; onBack: () => void }) {
  const [apps, setApps] = useState<AppInfo[] | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [system, setSystem] = useState(false)
  const [q, setQ] = useState("")
  // Выбранные — наверх, но по выбору на момент открытия: иначе строка уезжала бы из-под
  // пальца при каждом нажатии.
  const [first] = useState(() => new Set(uids))
  useEffect(() => {
    loadApps().then(setApps).catch((e) => setErr(errorText(e)))
  }, [])
  const shown = useMemo(() => {
    const s = q.trim().toLowerCase()
    return (apps ?? [])
      .filter((a) => system || !a.system || uids.includes(a.uid))
      .filter((a) => !s || a.label.toLowerCase().includes(s) || a.pkg.toLowerCase().includes(s))
      .sort((a, b) => Number(first.has(b.uid)) - Number(first.has(a.uid)) || a.label.localeCompare(b.label, "ru"))
  }, [apps, system, q, uids, first])
  const toggle = (uid: number) => onChange(uids.includes(uid) ? uids.filter((u) => u !== uid) : [...uids, uid])
  return (
    <Overlay title="Приложения" onBack={onBack}>
      <Search value={q} onChange={setQ} placeholder="Найти приложение" />
      <div style={{ ...rowS("var(--an-space-6)"), justifyContent: "space-between", minHeight: 44 }}>
        <span style={{ font: "var(--an-text-body-sm)" }}>Показывать системные</span>
        <Switch size="lg" label="Показывать системные" checked={system} onChange={() => setSystem((v) => !v)} />
      </div>
      <div style={muted}>выбрано: {uids.length}</div>
      {err ? <Empty icon="apps" text={err} /> : null}
      {!apps && !err ? <Skeleton height={56} count={6} /> : null}
      {apps && shown.length === 0 ? <Empty icon="search" text="Ничего не нашлось." /> : null}
      <div style={col("var(--an-space-1)")}>
        {shown.map((a) => (
          <PickRow
            key={a.uid}
            on={uids.includes(a.uid)}
            onClick={() => toggle(a.uid)}
            lead={<AppIcon pkg={a.pkg} label={a.label} size={36} />}
            title={a.label}
            sub={a.shared.length > 1 ? `вместе с ним: ${a.shared.length - 1} · ${a.pkg}` : a.pkg}
          />
        ))}
      </div>
    </Overlay>
  )
}

export function ListPicker({
  lists,
  custom,
  onChange,
  onNewList,
  onBack,
}: {
  lists: string[]
  custom: string[]
  onChange: (lists: string[], custom: string[]) => void
  onNewList: () => void
  onBack: () => void
}) {
  const { catalog, catalogError, custom: customLists } = useStore()
  const [q, setQ] = useState("")
  const s = q.trim().toLowerCase()
  const groups = useMemo(
    () => groupBySource((catalog ?? []).filter((e) => !s || e.name.toLowerCase().includes(s) || e.id.toLowerCase().includes(s))),
    [catalog, s],
  )
  const mine = (customLists ?? []).filter((c) => !s || c.name.toLowerCase().includes(s))
  return (
    <Overlay title="Списки" onBack={onBack}>
      <Search value={q} onChange={setQ} placeholder="Найти список" />
      <section style={col("var(--an-space-2)")}>
        <h2 style={{ ...muted, padding: "0 8px" }}>Свои списки</h2>
        {mine.map((c) => (
          <PickRow
            key={c.name}
            on={custom.includes(c.name)}
            onClick={() => onChange(lists, custom.includes(c.name) ? custom.filter((x) => x !== c.name) : [...custom, c.name])}
            title={c.name}
            sub={customSub(c)}
          />
        ))}
        <Button tone="ghost" icon={<Icon name="plus" />} onClick={onNewList} style={{ alignSelf: "flex-start" }}>
          Новый свой список
        </Button>
      </section>
      {!catalog ? <Skeleton height={56} count={6} /> : null}
      {catalog && catalog.length === 0 && catalogError ? <Empty icon="list" text={catalogError} /> : null}
      {groups.map(([src, items]) => (
        <section key={src} style={col("var(--an-space-2)")}>
          <h2 style={{ ...muted, padding: "0 8px" }}>{src}</h2>
          {items.map((e) => (
            <PickRow
              key={e.id}
              on={lists.includes(e.id)}
              onClick={() => onChange(lists.includes(e.id) ? lists.filter((x) => x !== e.id) : [...lists, e.id], custom)}
              title={e.name}
              sub={e.description}
              meta={catalogMeta(e)}
            />
          ))}
        </section>
      ))}
      {catalog && s && groups.length === 0 && mine.length === 0 ? <Empty icon="search" text="Ничего не нашлось." /> : null}
    </Overlay>
  )
}

/** «доменов: 12 · подсетей: 3» — без пустой половины. */
export function customSub(c: CustomList): string {
  return [c.domains.length ? `доменов: ${c.domains.length}` : null, c.prefixes.length ? `подсетей: ${c.prefixes.length}` : null].filter(Boolean).join(" · ") || "пусто"
}

/** Размер службы каталога: скачанное, а без него — что обещает каталог. */
export function catalogMeta(e: CatalogItem): string | undefined {
  const n = e.downloaded?.count ?? e.count
  if (n == null) return undefined
  const word = e.kinds.length === 1 && e.kinds[0] === "prefixes" ? "подсетей" : "записей"
  return `${word}: ${fmtInt(n)}`
}

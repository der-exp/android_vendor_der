/**
 * Правила — каналы движка сверху вниз: первое совпадение побеждает, поэтому порядок
 * виден номером и меняется стрелками в режиме «Порядок». Строка читается «кому → что →
 * куда». Правка идёт в черновик; в движок — пилюлей «Применить» (settings.put → spec.apply).
 */
import { useState } from "react"
import { Button, Callout, IconButton, Skeleton, Switch } from "@andromeda/ui"
import { useStore, useListInfo } from "../store"
import { useNav } from "../nav"
import { Body, Empty, Header, col, ellipsis, muted, rowS } from "../ui"
import { Icon } from "../icons"
import { WHO_TEXT, matchText } from "../format"
import type { Model, ModelChannel } from "../types"
import { RuleEditor } from "./RuleEditor"
import { useAppLabels } from "../apps"

function whoText(c: ModelChannel, appLabel: (uid: number) => string): string {
  if (c.who === "apps") {
    const u = c.uids ?? []
    if (u.length === 0) return "приложения не выбраны"
    if (u.length <= 2) return u.map(appLabel).join(", ")
    return `приложений: ${u.length}`
  }
  return WHO_TEXT[c.who] ?? c.who
}

function outText(model: Model, out: string): string {
  const o = model.outputs[out]
  if (!o) return `${out} (нет такого выхода)`
  return o.kind === "direct" ? "напрямую" : out
}

function RuleRow({
  c,
  i,
  n,
  model,
  ordering,
  appLabel,
}: {
  c: ModelChannel
  i: number
  n: number
  model: Model
  ordering: boolean
  appLabel: (uid: number) => string
}) {
  const { setDraft } = useStore()
  const { open } = useNav()
  const lists = useListInfo()
  const off = c.enabled === false
  const move = (d: -1 | 1) =>
    setDraft((m) => {
      const [x] = m.channels.splice(i, 1)
      m.channels.splice(i + d, 0, x)
      return m
    })
  const toggle = () =>
    setDraft((m) => {
      m.channels[i].enabled = off ? true : false
      return m
    })
  return (
    <div
      style={{
        ...rowS("var(--an-space-4)"),
        padding: "4px 6px 4px 4px",
        borderRadius: "var(--an-radius-block)",
        border: "1px solid var(--an-border)",
        background: "var(--an-surface-card)",
      }}
    >
      <button
        type="button"
        onClick={() => open({ kind: "rule", index: i })}
        style={{
          ...rowS("var(--an-space-5)"),
          flex: 1,
          minHeight: 60,
          padding: "6px 8px",
          border: 0,
          background: "transparent",
          color: "var(--an-text)",
          textAlign: "left",
          cursor: "pointer",
          opacity: off ? 0.5 : 1,
          transition: "opacity var(--an-dur-control) var(--an-ease)",
        }}
      >
        <span className="sp-tab" style={{ ...muted, width: 18, textAlign: "center", flex: "0 0 auto" }}>{i + 1}</span>
        <span style={{ ...col("2px"), minWidth: 0, flex: 1 }}>
          <span style={{ font: "var(--an-text-body)", fontWeight: "var(--an-weight-semibold)", ...ellipsis }}>{c.name}</span>
          <span style={{ font: "var(--an-text-body-sm)", color: "var(--an-text-secondary)", display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden", wordBreak: "break-word" }}>
            {whoText(c, appLabel)} → {matchText(c, lists.name)} → <span style={{ color: "var(--an-text)" }}>{outText(model, c.out)}</span>
          </span>
        </span>
      </button>
      {ordering ? (
        <span style={{ display: "flex", flex: "0 0 auto" }}>
          <IconButton label="Выше" disabled={i === 0} onClick={() => move(-1)}>
            <Icon name="up" size={18} />
          </IconButton>
          <IconButton label="Ниже" disabled={i === n - 1} onClick={() => move(1)}>
            <Icon name="down" size={18} />
          </IconButton>
        </span>
      ) : (
        <span style={{ display: "inline-flex", alignItems: "center", justifyContent: "center", minWidth: 56, minHeight: 44, flex: "0 0 auto" }}>
          <Switch size="lg" checked={!off} label={off ? "Включить правило" : "Выключить правило"} onChange={toggle} />
        </span>
      )}
    </div>
  )
}

export function Rules() {
  const { route, open } = useNav()
  const { draft, modelError, applyError } = useStore()
  const [ordering, setOrdering] = useState(false)
  const [labels] = useAppLabels()

  if (route.sub?.kind === "rule") return <RuleEditor key={route.sub.index} index={route.sub.index} prefill={route.sub.prefill} />

  const channels = draft?.channels ?? []
  return (
    <>
      <Header
        title="Правила"
        right={
          draft && channels.length > 1 ? (
            <Button tone={ordering ? "primary" : "ghost"} size="sm" onClick={() => setOrdering((v) => !v)}>
              {ordering ? "Готово" : "Порядок"}
            </Button>
          ) : null
        }
      />
      <Body>
        {applyError ? (
          <Callout tone="danger" title="Не применилось" verbatim={applyError.message}>
            {applyError.rolledBack ? <div style={muted}>работают прежние правила</div> : null}
          </Callout>
        ) : null}
        {modelError ? <Empty icon="rules" text={modelError} /> : null}
        {!draft && !modelError ? <Skeleton height={70} count={4} /> : null}
        {draft && channels.length === 0 ? (
          <Empty icon="rules" text="Правил нет — весь трафик идёт напрямую." />
        ) : null}
        {draft && channels.length > 0 ? (
          <div style={col("var(--an-gap-row)")}>
            <div style={muted}>Совпавшее забирает правило, которое выше.</div>
            {channels.map((c, i) => (
              <RuleRow key={i + c.name} c={c} i={i} n={channels.length} model={draft} ordering={ordering} appLabel={labels} />
            ))}
          </div>
        ) : null}
        {draft ? (
          <Button tone="secondary" full icon={<Icon name="plus" />} onClick={() => open({ kind: "rule", index: -1 })}>
            Новое правило
          </Button>
        ) : null}
      </Body>
    </>
  )
}

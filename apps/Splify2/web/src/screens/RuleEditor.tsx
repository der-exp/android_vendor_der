/**
 * Правка одного правила: кому → что → куда. Правка живёт здесь до «Готово» и уходит в
 * черновик модели; в движок — пилюлей «Применить», вместе с остальными изменениями.
 *
 * «Кому» — весь телефон (from:"self"), выбранные приложения (from:"uid:N") или раздача.
 * «Что» — весь трафик либо списки (каталог и свои), домены, подсети в любом сочетании.
 * «Куда» — выход по имени.
 */
import { useEffect, useState } from "react"
import { Button, Card, Chip, Dialog, Field, IconButton, Input, Radio, StatusDot, Switch, Textarea } from "@andromeda/ui"
import { useStore, useListInfo } from "../store"
import { useNav } from "../nav"
import { call } from "../bridge"
import { useAppLabels } from "../apps"
import { AppIcon, Body, CardHead, Header, Segmented, col, ellipsis, muted, rowS } from "../ui"
import { Icon } from "../icons"
import { KIND_TEXT, WHO_TEXT, outputLabel, outputState, touchesDomains, usedOutputs } from "../format"
import type { CustomList, ModelChannel, Who } from "../types"
import { AppPicker, ListPicker } from "./Pickers"

const DOMAIN_RE = /^(\*\.)?([a-z0-9-]+\.)+[a-z0-9-]{2,}$/i
const PREFIX_RE = /^(\d{1,3}(\.\d{1,3}){3}(\/\d{1,2})?|[0-9a-f:]+:[0-9a-f:]*(\/\d{1,3})?)$/i

const lines = (s: string) =>
  s
    .split(/[\s,]+/)
    .map((x) => x.trim())
    .filter(Boolean)

function blank(firstOut: string): ModelChannel {
  return { name: "", enabled: true, who: "phone", match: {}, out: firstOut }
}

type Picker = "apps" | "lists" | null

export function RuleEditor({ index, prefill }: { index: number; prefill?: Partial<ModelChannel> }) {
  const { draft, setDraft, status, engine, catalog } = useStore()
  const { back } = useNav()
  const lists = useListInfo()
  const [appLabel, apps] = useAppLabels()
  const isNew = index < 0 || !draft?.channels[index]
  const outs = draft ? Object.values(draft.outputs) : []
  const [c, setC] = useState<ModelChannel>(() => {
    const base = !isNew && draft ? structuredClone(draft.channels[index]) : blank(outs.find((o) => o.kind !== "direct")?.name ?? outs[0]?.name ?? "")
    return prefill ? { ...base, ...prefill, match: { ...base.match, ...prefill.match } } : base
  })
  const [domainsText, setDomainsText] = useState((c.match.domains ?? []).join("\n"))
  const [prefixesText, setPrefixesText] = useState((c.match.prefixes ?? []).join("\n"))
  const [customLists, setCustomLists] = useState<CustomList[] | null>(null)
  const [picker, setPicker] = useState<Picker>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [tried, setTried] = useState(false)

  useEffect(() => {
    call("lists.custom")
      .then((r) => setCustomLists(Array.isArray(r) ? r : []))
      .catch(() => setCustomLists([]))
  }, [])

  // Выбор приложений и списков — слой поверх редактора со своей записью в истории, чтобы
  // системная «Назад» закрывала слой, а не редактор.
  useEffect(() => {
    const onPop = () => setPicker(null)
    window.addEventListener("popstate", onPop)
    return () => window.removeEventListener("popstate", onPop)
  }, [])
  const openPicker = (p: Exclude<Picker, null>) => {
    history.pushState({ ...history.state, picker: p }, "")
    setPicker(p)
  }
  const closePicker = () => history.back()

  if (!draft) return null

  const domains = lines(domainsText)
  const prefixes = lines(prefixesText)
  const badDomains = domains.filter((d) => !DOMAIN_RE.test(d))
  const badPrefixes = prefixes.filter((p) => !PREFIX_RE.test(p))
  const m = c.match
  const hasWhat = !!m.any || !!m.lists?.length || !!m.custom?.length || domains.length > 0 || prefixes.length > 0
  const nameTaken = draft.channels.some((x, i) => i !== index && x.name.trim() === c.name.trim())

  const errors = {
    name: !c.name.trim() ? "Нужно название" : nameTaken ? "Такое название уже есть" : null,
    apps: c.who === "apps" && !(c.uids?.length) ? "Выберите хотя бы одно приложение" : null,
    what: !hasWhat ? "Выберите, что забирает правило" : null,
    domains: badDomains.length ? `Не похоже на домен: ${badDomains.slice(0, 3).join(", ")}` : null,
    prefixes: badPrefixes.length ? `Не похоже на подсеть: ${badPrefixes.slice(0, 3).join(", ")}` : null,
    out: !draft.outputs[c.out] ? "Выберите выход" : null,
  }
  const valid = Object.values(errors).every((e) => !e)
  const show = (e: string | null) => (tried ? e : null)

  const result = (): ModelChannel => {
    const match = m.any
      ? { any: true }
      : {
          ...(m.lists?.length ? { lists: m.lists } : {}),
          ...(m.custom?.length ? { custom: m.custom } : {}),
          ...(domains.length ? { domains } : {}),
          ...(prefixes.length ? { prefixes } : {}),
        }
    const out: ModelChannel = { ...c, name: c.name.trim(), match }
    if (c.who !== "apps") delete out.uids
    return out
  }

  const done = () => {
    setTried(true)
    if (!valid) return
    const r = result()
    setDraft((md) => {
      if (isNew) md.channels.unshift(r)
      else md.channels[index] = r
      return md
    })
    // Список из каталога, которого нет на телефоне, скачивается, как только его выбрали.
    for (const id of r.match.lists ?? []) {
      const e = catalog?.find((x) => x.id === id)
      if (e && !e.selected) void call("lists.select", { id, on: true })
    }
    back()
  }

  const remove = () => {
    setDraft((md) => {
      md.channels.splice(index, 1)
      return md
    })
    setConfirmDelete(false)
    back()
  }

  const set = (patch: Partial<ModelChannel>) => setC((x) => ({ ...x, ...patch }))
  const setMatch = (patch: Partial<ModelChannel["match"]>) => setC((x) => ({ ...x, match: { ...x.match, ...patch } }))
  const used = usedOutputs(draft)
  const dnsNote = touchesDomains({ ...c, match: { ...m, domains } }, lists.kind)

  if (picker === "apps")
    return <AppPicker uids={c.uids ?? []} onChange={(uids) => set({ uids })} onBack={closePicker} />
  if (picker === "lists")
    return (
      <ListPicker
        lists={m.lists ?? []}
        custom={m.custom ?? []}
        customLists={customLists}
        onChange={(l, cu) => setMatch({ lists: l, custom: cu })}
        onBack={closePicker}
      />
    )

  return (
    <>
      <Header title={isNew ? "Новое правило" : c.name || "Правило"} back={back} />
      <Body>
        <Card style={col("var(--an-space-4)")}>
          <Field label="Название" error={show(errors.name)}>
            <Input value={c.name} placeholder="Например, YouTube" onInput={(e) => set({ name: e.currentTarget.value })} invalid={!!show(errors.name)} />
          </Field>
          <div style={{ ...rowS("var(--an-space-6)"), justifyContent: "space-between", minHeight: 44 }}>
            <span style={{ font: "var(--an-text-body-sm)" }}>Включено</span>
            <Switch size="lg" label="Включено" checked={c.enabled !== false} onChange={() => set({ enabled: c.enabled === false })} />
          </div>
        </Card>

        <Card style={col("var(--an-space-6)")}>
          <CardHead title="Кому" />
          <Segmented<Who>
            items={(["phone", "apps", "tether"] as Who[]).map((w) => ({ value: w, label: WHO_TEXT[w] }))}
            value={c.who}
            onChange={(who) => set({ who })}
          />
          {c.who === "apps" ? (
            <div style={col("var(--an-space-3)")}>
              {(c.uids ?? []).map((uid) => {
                const a = apps?.find((x) => x.uid === uid)
                return (
                  <div key={uid} style={{ ...rowS("var(--an-space-6)"), minHeight: 48 }}>
                    <AppIcon pkg={a?.pkg} label={appLabel(uid)} size={32} />
                    <span style={{ ...col("1px"), flex: 1, minWidth: 0 }}>
                      <span style={{ font: "var(--an-text-body)", ...ellipsis }}>{appLabel(uid)}</span>
                      {a && a.shared.length > 1 ? <span style={{ ...muted, ...ellipsis }}>вместе с ним: {a.shared.length - 1}</span> : null}
                    </span>
                    <IconButton label="Убрать" onClick={() => set({ uids: (c.uids ?? []).filter((u) => u !== uid) })}>
                      <Icon name="x" size={18} />
                    </IconButton>
                  </div>
                )
              })}
              <Button tone="secondary" full icon={<Icon name="apps" />} onClick={() => openPicker("apps")}>
                {c.uids?.length ? "Изменить выбор" : "Выбрать приложения"}
              </Button>
              {show(errors.apps) ? <span style={{ font: "var(--an-text-caption)", color: "var(--an-danger)" }}>{errors.apps}</span> : null}
            </div>
          ) : null}
          {c.who === "tether" ? <div style={muted}>устройства, подключённые к точке доступа телефона</div> : null}
          {dnsNote && c.enabled !== false ? <div style={muted}>Частный DNS будет выключен, пока правило включено.</div> : null}
        </Card>

        <Card style={col("var(--an-space-6)")}>
          <CardHead title="Что" />
          <div style={{ ...rowS("var(--an-space-6)"), justifyContent: "space-between", minHeight: 44 }}>
            <span style={{ font: "var(--an-text-body-sm)" }}>Весь трафик</span>
            <Switch size="lg" label="Весь трафик" checked={!!m.any} onChange={() => setMatch({ any: !m.any })} />
          </div>
          {!m.any ? (
            <>
              <div style={col("var(--an-space-3)")}>
                <span style={{ font: "var(--an-text-caption)" }}>Списки</span>
                {m.lists?.length || m.custom?.length ? (
                  <div style={{ display: "flex", flexWrap: "wrap", gap: "var(--an-space-3)" }}>
                    {(m.lists ?? []).map((id) => (
                      <Chip key={id}>{lists.name(id)}</Chip>
                    ))}
                    {(m.custom ?? []).map((n) => (
                      <Chip key={"c:" + n} tone="neutral">{n}</Chip>
                    ))}
                  </div>
                ) : null}
                <Button tone="secondary" full icon={<Icon name="list" />} onClick={() => openPicker("lists")}>
                  {m.lists?.length || m.custom?.length ? "Изменить списки" : "Выбрать списки"}
                </Button>
              </div>
              <Field label="Домены" hint="по одному в строке" error={show(errors.domains)}>
                <Textarea rows={3} value={domainsText} placeholder="example.com" onInput={(e) => setDomainsText(e.currentTarget.value)} autoCapitalize="none" autoCorrect="off" spellCheck={false} />
              </Field>
              <Field label="Подсети" hint="по одной в строке" error={show(errors.prefixes)}>
                <Textarea rows={3} value={prefixesText} placeholder="203.0.113.0/24" onInput={(e) => setPrefixesText(e.currentTarget.value)} autoCapitalize="none" autoCorrect="off" spellCheck={false} />
              </Field>
            </>
          ) : null}
          {show(errors.what) ? <span style={{ font: "var(--an-text-caption)", color: "var(--an-danger)" }}>{errors.what}</span> : null}
        </Card>

        <Card style={col("var(--an-space-3)")}>
          <CardHead title="Куда" />
          {outs.map((o) => {
            const s = outputState(status?.outputs[o.name], !!engine?.enabled, used.has(o.name), !!status)
            return (
              <Radio
                key={o.name}
                checked={c.out === o.name}
                onChange={() => set({ out: o.name })}
                dot={<StatusDot tone={s.tone} />}
                label={outputLabel(o.name, o)}
                meta={o.kind === "direct" ? undefined : KIND_TEXT[o.kind]}
                style={{ minHeight: 44 }}
              />
            )
          })}
          {show(errors.out) ? <span style={{ font: "var(--an-text-caption)", color: "var(--an-danger)" }}>{errors.out}</span> : null}
        </Card>

        <div style={col("var(--an-space-4)")}>
          <Button tone="primary" full onClick={done}>
            {isNew ? "Добавить правило" : "Готово"}
          </Button>
          {!isNew ? (
            <Button tone="danger" full icon={<Icon name="trash" />} onClick={() => setConfirmDelete(true)}>
              Удалить правило
            </Button>
          ) : null}
        </div>
      </Body>
      <Dialog
        open={confirmDelete}
        title={`Удалить «${c.name}»?`}
        confirmLabel="Удалить правило"
        onConfirm={remove}
        onCancel={() => setConfirmDelete(false)}
      >
        Трафик, который забирало это правило, пойдёт по правилам ниже.
      </Dialog>
    </>
  )
}

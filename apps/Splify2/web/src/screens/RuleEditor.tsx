/**
 * Правка одного правила: кому → что → куда. Правка живёт здесь до «Готово» и уходит в
 * черновик модели; в движок — пилюлей «Применить», вместе с остальными изменениями.
 *
 * «Кому» — весь телефон (from:"self"), выбранные приложения (from:"uid:N") или раздача.
 * «Что» — весь трафик либо списки: службы каталога и свои. Доменов прямо в правиле модель
 * не держит (logic/Model.kt): свой домен — это свой список, и его можно завести отсюда же —
 * он сохраняется сразу и тут же встаёт в правило.
 * «Куда» — выход по имени или «Напрямую». Мост Telegram — только для раздачи: у трафика
 * самого телефона такого перехвата нет, и логика такое правило не примет.
 */
import { useEffect, useState } from "react"
import { Button, Card, Chip, Dialog, Field, IconButton, Input, Radio, StatusDot, Switch } from "@andromeda/ui"
import { useStore, useListInfo } from "../store"
import { useNav, type RulePrefill } from "../nav"
import { useAppLabels } from "../apps"
import { AppIcon, Body, CardHead, Header, Segmented, col, ellipsis, muted, rowS } from "../ui"
import { Icon } from "../icons"
import { WHO_TEXT, kindText, outputLabel, outputState, touchesDomains, usedOutputs } from "../format"
import type { ModelChannel, ModelOutput, Who, WhoKind } from "../types"
import { AppPicker, ListPicker } from "./Pickers"
import { CustomEdit, suggestName } from "./CustomEdit"

function blank(firstOut: string): ModelChannel {
  return { name: "", enabled: true, who: { kind: "phone" }, what: { lists: [], custom: [], all: false }, out: firstOut }
}

/** «Кому» при смене вида: приложения и адреса раздачи не теряются, если вернуться назад. */
function whoOf(kind: WhoKind, cur: Who, keep: { uids: number[]; from: string[] }): Who {
  if (kind === "apps") return { kind, uids: cur.kind === "apps" ? cur.uids : keep.uids }
  if (kind === "tether") return { kind, from: cur.kind === "tether" ? cur.from : keep.from }
  return { kind: "phone" }
}

type Layer = "apps" | "lists" | "newList" | null

export function RuleEditor({ index, prefill }: { index: number; prefill?: RulePrefill }) {
  const { draft, setDraft, status, engine, custom } = useStore()
  const { back } = useNav()
  const lists = useListInfo()
  const [appLabel, apps] = useAppLabels()
  const isNew = index < 0 || !draft?.channels[index]
  const outs: ModelOutput[] = [...(draft?.outputs ?? []), { name: "direct", kind: "direct" }]
  const [c, setC] = useState<ModelChannel>(() => {
    const base = !isNew && draft ? structuredClone(draft.channels[index]) : blank(draft?.outputs.find((o) => o.kind !== "tgws")?.name ?? "direct")
    if (!prefill) return base
    const { listText: _drop, ...p } = prefill
    return { ...base, ...p, what: { ...base.what, ...p.what } }
  })
  const [keep] = useState(() => ({ uids: c.who.kind === "apps" ? c.who.uids : [], from: c.who.kind === "tether" ? c.who.from : [] }))
  const [layer, setLayer] = useState<Layer>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [tried, setTried] = useState(false)

  // Слои (выбор приложений, списков, новый свой список) — со своей записью в истории, чтобы
  // системная «Назад» закрывала слой, а не редактор.
  useEffect(() => {
    const onPop = () => setLayer(null)
    window.addEventListener("popstate", onPop)
    return () => window.removeEventListener("popstate", onPop)
  }, [])
  const openLayer = (l: Exclude<Layer, null>) => {
    history.pushState({ ...history.state, layer: l }, "")
    setLayer(l)
  }
  const closeLayer = () => history.back()

  // Заготовка из «Соединений» с доменом или адресом — сразу форма своего списка с ним.
  useEffect(() => {
    if (prefill?.listText) openLayer("newList")
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (!draft) return null

  const w = c.what
  const uids = c.who.kind === "apps" ? c.who.uids : []
  const hasWhat = w.all || w.lists.length > 0 || w.custom.length > 0
  const nameTaken = draft.channels.some((x, i) => i !== index && x.name.trim() === c.name.trim())
  const outOk = (o: ModelOutput) => o.kind !== "tgws" || c.who.kind === "tether"
  const out = outs.find((o) => o.name === c.out)

  const errors = {
    name: !c.name.trim() ? "Нужно название" : nameTaken ? "Такое название уже есть" : null,
    apps: c.who.kind === "apps" && uids.length === 0 ? "Выберите хотя бы одно приложение" : null,
    what: !hasWhat ? "Выберите, что забирает правило" : null,
    out: !out ? "Выберите выход" : !outOk(out) ? "Мост Telegram работает только для раздачи" : null,
  }
  const valid = Object.values(errors).every((e) => !e)
  const show = (e: string | null) => (tried ? e : null)

  const done = () => {
    setTried(true)
    if (!valid) return
    const r: ModelChannel = {
      ...c,
      name: c.name.trim(),
      what: w.all ? { lists: [], custom: [], all: true } : { ...w, all: false },
    }
    setDraft((md) => {
      if (isNew) md.channels.unshift(r)
      else md.channels[index] = r
      return md
    })
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
  const setWhat = (patch: Partial<ModelChannel["what"]>) => setC((x) => ({ ...x, what: { ...x.what, ...patch } }))
  const used = usedOutputs(draft)
  const dnsNote = touchesDomains(c, lists.kinds, lists.customHasDomains)

  if (layer === "apps")
    return <AppPicker uids={uids} onChange={(u) => set({ who: { kind: "apps", uids: u } })} onBack={closeLayer} />
  if (layer === "lists")
    return (
      <ListPicker
        lists={w.lists}
        custom={w.custom}
        onChange={(l, cu) => setWhat({ lists: l, custom: cu })}
        onNewList={() => {
          // Слой списков меняется на слой нового списка без лишней записи в истории.
          history.replaceState({ ...history.state, layer: "newList" }, "")
          setLayer("newList")
        }}
        onBack={closeLayer}
      />
    )
  if (layer === "newList")
    return (
      <CustomEdit
        overlay
        initText={prefill?.listText ?? ""}
        initName={prefill?.listText ? suggestName(prefill.listText, (n) => !!custom?.some((x) => x.name === n)) : ""}
        onSaved={(name) => {
          setWhat({ custom: [...new Set([...w.custom, name])], all: false })
          closeLayer()
        }}
        onBack={closeLayer}
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
            <Switch size="lg" label="Включено" checked={c.enabled} onChange={() => set({ enabled: !c.enabled })} />
          </div>
        </Card>

        <Card style={col("var(--an-space-6)")}>
          <CardHead title="Кому" />
          <Segmented<WhoKind>
            items={(["phone", "apps", "tether"] as WhoKind[]).map((k) => ({ value: k, label: WHO_TEXT[k] }))}
            value={c.who.kind}
            onChange={(k) => set({ who: whoOf(k, c.who, keep) })}
          />
          {c.who.kind === "apps" ? (
            <div style={col("var(--an-space-3)")}>
              {uids.map((uid) => {
                const a = apps?.find((x) => x.uid === uid)
                return (
                  <div key={uid} style={{ ...rowS("var(--an-space-6)"), minHeight: 48 }}>
                    <AppIcon pkg={a?.pkg} label={appLabel(uid)} size={32} />
                    <span style={{ ...col("1px"), flex: 1, minWidth: 0 }}>
                      <span style={{ font: "var(--an-text-body)", ...ellipsis }}>{appLabel(uid)}</span>
                      {a && a.shared.length > 1 ? <span style={{ ...muted, ...ellipsis }}>вместе с ним: {a.shared.length - 1}</span> : null}
                    </span>
                    <IconButton label="Убрать" onClick={() => set({ who: { kind: "apps", uids: uids.filter((u) => u !== uid) } })}>
                      <Icon name="x" size={18} />
                    </IconButton>
                  </div>
                )
              })}
              <Button tone="secondary" full icon={<Icon name="apps" />} onClick={() => openLayer("apps")}>
                {uids.length ? "Изменить выбор" : "Выбрать приложения"}
              </Button>
              {show(errors.apps) ? <span style={{ font: "var(--an-text-caption)", color: "var(--an-danger)" }}>{errors.apps}</span> : null}
            </div>
          ) : null}
          {c.who.kind === "tether" ? (
            <div style={muted}>
              {c.who.from.length ? `устройства раздачи: ${c.who.from.join(", ")}` : "все устройства, подключённые к точке доступа телефона"}
            </div>
          ) : null}
          {dnsNote && c.enabled ? <div style={muted}>Частный DNS будет выключен, пока правило включено.</div> : null}
        </Card>

        <Card style={col("var(--an-space-6)")}>
          <CardHead title="Что" />
          <div style={{ ...rowS("var(--an-space-6)"), justifyContent: "space-between", minHeight: 44 }}>
            <span style={{ font: "var(--an-text-body-sm)" }}>Весь трафик</span>
            <Switch size="lg" label="Весь трафик" checked={w.all} onChange={() => setWhat({ all: !w.all })} />
          </div>
          {!w.all ? (
            <div style={col("var(--an-space-4)")}>
              {w.lists.length || w.custom.length ? (
                <div style={{ display: "flex", flexWrap: "wrap", gap: "var(--an-space-3)" }}>
                  {w.lists.map((id) => (
                    <Chip key={id}>{lists.name(id)}</Chip>
                  ))}
                  {w.custom.map((n) => (
                    <Chip key={"c:" + n} tone="neutral">
                      {n}
                    </Chip>
                  ))}
                </div>
              ) : null}
              <Button tone="secondary" full icon={<Icon name="list" />} onClick={() => openLayer("lists")}>
                {w.lists.length || w.custom.length ? "Изменить списки" : "Выбрать списки"}
              </Button>
              <Button tone="ghost" full icon={<Icon name="plus" />} onClick={() => openLayer("newList")}>
                Свои домены и подсети
              </Button>
            </div>
          ) : null}
          {show(errors.what) ? <span style={{ font: "var(--an-text-caption)", color: "var(--an-danger)" }}>{errors.what}</span> : null}
        </Card>

        <Card style={col("var(--an-space-3)")}>
          <CardHead title="Куда" />
          {outs
            .filter((o) => outOk(o) || o.name === c.out)
            .map((o) => {
              const s = outputState(status?.outputs[o.name], !!engine?.enabled, used.has(o.name), !!status)
              return (
                <Radio
                  key={o.name}
                  checked={c.out === o.name}
                  onChange={() => set({ out: o.name })}
                  dot={<StatusDot tone={s.tone} />}
                  label={outputLabel(o.name)}
                  meta={o.kind === "direct" ? "без туннеля" : kindText(o)}
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

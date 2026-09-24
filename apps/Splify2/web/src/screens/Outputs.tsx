/**
 * Выходы: куда правила ведут трафик. У выхода VLESS — узлы подписки, выбранные по
 * предпочтению (первый ответивший забирает трафик), и замер задержки; у WireGuard —
 * устройства по порядку. Выбор узлов и «если всё упало» правят черновик модели и уходят
 * в движок пилюлей «Применить», как и правила.
 */
import { useEffect, useMemo, useState, type ReactNode } from "react"
import { Badge, Button, Card, Dialog, Field, Input, Select, Skeleton, StatusDot } from "@andromeda/ui"
import { useStore } from "../store"
import { useNav } from "../nav"
import { call, errorText } from "../bridge"
import { Body, CardHead, Divider, Empty, Header, KV, Segmented, col, ellipsis, muted, rowS } from "../ui"
import { Icon } from "../icons"
import { KIND_TEXT, ON_FAIL_TEXT, outputState, usedOutputs, type Tone } from "../format"
import type { ModelOutput, OnFail, OutputStatus, Sub, VlessNodesReply, VlessProbe } from "../types"

const TONE_BADGE: Record<Tone, "success" | "warn" | "danger" | "neutral"> = { ok: "success", warn: "warn", bad: "danger", off: "neutral" }

function delayTone(p: VlessProbe | undefined): Tone | undefined {
  if (!p) return undefined
  if (!p.ok) return "bad"
  return p.ttfb_ms < 300 ? "ok" : "warn"
}

function VlessNodes({ o, st }: { o: ModelOutput; st?: OutputStatus }) {
  const { setDraft, engine } = useStore()
  const [reply, setReply] = useState<VlessNodesReply | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [probe, setProbe] = useState<Map<number, VlessProbe> | null>(null)
  const [probing, setProbing] = useState(false)
  const [order, setOrder] = useState<"list" | "delay">("list")

  useEffect(() => {
    if (!engine?.reachable) return
    call("engine.vlessNodes", { out: o.name })
      .then((r) => {
        setReply(r)
        setErr(null)
      })
      .catch((e) => setErr(errorText(e)))
  }, [o.name, engine?.reachable])

  const chosen = o.nodes ?? []

  const runProbe = async () => {
    setProbing(true)
    try {
      const r = await call("engine.vlessProbe", { out: o.name })
      setProbe(new Map((r.results ?? []).map((p) => [p.index, p])))
      setOrder("delay")
      if (r.error) setErr(r.error)
    } catch (e) {
      setErr(errorText(e))
    } finally {
      setProbing(false)
    }
  }

  const toggle = (idx: number) =>
    setDraft((m) => {
      const out = m.outputs[o.name]
      const cur = out.nodes ?? []
      out.nodes = cur.includes(idx) ? cur.filter((n) => n !== idx) : [...cur, idx]
      return m
    })
  const firstWorking = () =>
    setDraft((m) => {
      m.outputs[o.name].nodes = []
      return m
    })

  // Активный — первый из выбранных (или из всех при «первом рабочем»), у которого замер
  // не показал отказа. Без замера и без подъёма выхода утверждать нечего.
  const active = useMemo(() => {
    if (!st?.up || !reply) return undefined
    const pool = chosen.length ? chosen : reply.nodes.map((n) => n.index)
    return pool.find((i) => !probe || probe.get(i)?.ok)
  }, [st?.up, reply, chosen, probe])

  const nodes = useMemo(() => {
    const list = [...(reply?.nodes ?? [])]
    if (order === "delay" && probe) {
      const key = (i: number) => {
        const p = probe.get(i)
        return p?.ok ? p.ttfb_ms : Number.MAX_SAFE_INTEGER
      }
      list.sort((a, b) => key(a.index) - key(b.index))
    }
    return list
  }, [reply, order, probe])

  if (err && !reply) return <div style={{ font: "var(--an-text-body-sm)", color: "var(--an-danger-ink)" }}>{err}</div>
  if (!reply) return engine?.reachable ? <Skeleton height={52} count={3} /> : null

  return (
    <div style={col("var(--an-space-4)")}>
      <div style={{ ...rowS("var(--an-space-6)"), justifyContent: "space-between" }}>
        <span style={{ font: "var(--an-text-body)", fontWeight: "var(--an-weight-medium)" }}>Узлы</span>
        <Button tone="secondary" size="sm" icon={<Icon name="zap" />} busy={probing} onClick={runProbe}>
          {probing ? "Замеряем…" : "Замерить"}
        </Button>
      </div>
      {probe ? (
        <Segmented
          items={[
            { value: "list", label: "по порядку" },
            { value: "delay", label: "по задержке" },
          ]}
          value={order}
          onChange={setOrder}
        />
      ) : null}
      <NodeRow checked={chosen.length === 0} order={null} title="Первый рабочий" sub="из всех узлов подписки" onClick={firstWorking} />
      {nodes.map((n) => {
        const pos = chosen.indexOf(n.index)
        const p = probe?.get(n.index)
        const tone = delayTone(p)
        return (
          <NodeRow
            key={n.index}
            checked={pos >= 0}
            order={pos >= 0 && chosen.length > 1 ? pos + 1 : null}
            title={n.name || `${n.host}:${n.port}`}
            sub={`${n.type} · ${n.security}${n.vision ? " · vision" : ""}`}
            active={active === n.index}
            right={
              p ? (
                <span style={{ ...rowS("var(--an-space-3)"), font: "var(--an-text-caption)", color: tone === "bad" ? "var(--an-danger-ink)" : "var(--an-text-secondary)" }}>
                  {tone ? <StatusDot tone={tone} /> : null}
                  <span className="sp-tab">{p.ok ? `${p.ttfb_ms} мс` : "нет ответа"}</span>
                </span>
              ) : null
            }
            onClick={() => toggle(n.index)}
          />
        )
      })}
      {reply.skipped > 0 ? (
        <div style={muted}>
          не подходят: {reply.skipped}
          {reply.skipped_reasons?.length ? ` · ${reply.skipped_reasons.map((r) => r.reason).join(", ")}` : ""}
        </div>
      ) : null}
      {err ? <div style={{ font: "var(--an-text-caption)", color: "var(--an-danger-ink)" }}>{err}</div> : null}
    </div>
  )
}

function NodeRow({
  checked,
  order,
  title,
  sub,
  right,
  active,
  onClick,
}: {
  checked: boolean
  order: number | null
  title: string
  sub: string
  right?: ReactNode
  active?: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={checked}
      style={{
        ...rowS("var(--an-space-6)"),
        width: "100%",
        minHeight: 52,
        padding: "6px 12px",
        borderRadius: "var(--an-radius-block)",
        border: `1px solid ${checked ? "var(--an-accent-line)" : "var(--an-border)"}`,
        background: checked ? "var(--an-accent-soft)" : "var(--an-surface-card)",
        color: "var(--an-text)",
        textAlign: "left",
        cursor: "pointer",
      }}
    >
      <span
        style={{
          width: 20,
          height: 20,
          flex: "0 0 auto",
          borderRadius: 6,
          border: `1.5px solid ${checked ? "var(--an-accent)" : "var(--an-control-line)"}`,
          background: checked ? "var(--an-accent)" : "transparent",
          color: "var(--an-text-on-accent)",
          display: "inline-flex",
          alignItems: "center",
          justifyContent: "center",
          font: "var(--an-text-micro)",
          fontWeight: "var(--an-weight-semibold)",
        }}
      >
        {checked ? order ?? <Icon name="check" size={13} /> : null}
      </span>
      <span style={{ ...col("1px"), flex: 1, minWidth: 0 }}>
        <span style={{ ...rowS("var(--an-space-3)") }}>
          <span style={{ font: "var(--an-text-body-sm)", fontWeight: "var(--an-weight-medium)", ...ellipsis }}>{title}</span>
          {active ? <Badge tone="success">активен</Badge> : null}
        </span>
        <span style={{ font: "var(--an-text-micro)", color: "var(--an-text-muted)", ...ellipsis }}>{sub}</span>
      </span>
      {right}
    </button>
  )
}

function OutputCard({ o, subs }: { o: ModelOutput; subs: Sub[] | null }) {
  const { status, engine, draft, setDraft } = useStore()
  const [confirm, setConfirm] = useState(false)
  const rulesHere = (draft?.channels ?? []).filter((c) => c.out === o.name).map((c) => c.name)
  const remove = () => {
    setConfirm(false)
    setDraft((m) => {
      delete m.outputs[o.name]
      return m
    })
  }
  const st = status?.outputs[o.name]
  const s = outputState(st, !!engine?.enabled, usedOutputs(draft).has(o.name), !!status)
  const sub = o.sub ? subs?.find((x) => x.id === o.sub) : undefined
  const setOnFail = (v: OnFail) =>
    setDraft((m) => {
      m.outputs[o.name].on_fail = v
      return m
    })
  return (
    <Card style={col()}>
      <div style={{ ...rowS("var(--an-space-5)"), justifyContent: "space-between" }}>
        <div style={{ ...col("2px"), minWidth: 0 }}>
          <h2 style={{ font: "var(--an-text-heading)", ...ellipsis }}>{o.name}</h2>
          <span style={{ ...muted, ...ellipsis }}>{[KIND_TEXT[o.kind], o.title].filter(Boolean).join(" · ")}</span>
        </div>
        <Badge tone={TONE_BADGE[s.tone]}>{s.text}</Badge>
      </div>
      <div style={col("var(--an-space-2)")}>
        {o.kind === "vless" ? <KV k="подписка" v={sub ? `${sub.name} · узлов: ${sub.nodes}` : o.sub || "—"} /> : null}
        {st?.up && st.device ? <KV k="устройство" v={<span style={{ font: "var(--an-text-code)" }}>{st.device}</span>} /> : null}
        <div style={{ ...rowS("var(--an-space-6)"), justifyContent: "space-between" }}>
          <span style={{ font: "var(--an-text-body-sm)", color: "var(--an-text-secondary)", flex: "0 0 auto" }}>если всё упало</span>
          <Select value={o.on_fail ?? "drop"} onChange={(e) => setOnFail(e.currentTarget.value as OnFail)} style={{ minWidth: 0, maxWidth: 200 }}>
            {(Object.keys(ON_FAIL_TEXT) as OnFail[])
              .filter((k) => k !== "zapret")
              .map((k) => (
                <option key={k} value={k}>
                  {ON_FAIL_TEXT[k]}
                </option>
              ))}
          </Select>
        </div>
      </div>
      {o.kind === "vless" ? (
        <>
          <Divider />
          <VlessNodes o={o} st={st} />
        </>
      ) : null}
      {o.kind === "interface" && (o.devices?.length ?? 0) > 0 ? (
        <>
          <Divider />
          <div style={col("var(--an-space-3)")}>
            <span style={{ font: "var(--an-text-body)", fontWeight: "var(--an-weight-medium)" }}>Устройства</span>
            {o.devices!.map((d, i) => (
              <div key={d} style={{ ...rowS("var(--an-space-6)"), minHeight: 36 }}>
                <span style={{ ...muted, width: 16 }}>{i + 1}</span>
                <span style={{ font: "var(--an-text-code)", flex: 1 }}>{d}</span>
                {st?.up && st.device === d ? <Badge tone="success">активно</Badge> : null}
              </div>
            ))}
          </div>
        </>
      ) : null}
      {rulesHere.length ? (
        <div style={muted}>в правилах: {rulesHere.join(", ")}</div>
      ) : (
        <Button tone="ghost" icon={<Icon name="trash" />} onClick={() => setConfirm(true)} style={{ alignSelf: "flex-start", color: "var(--an-danger)", padding: "0 4px" }}>
          Удалить выход
        </Button>
      )}
      <Dialog open={confirm} title={`Удалить ${o.name}?`} confirmLabel="Удалить выход" onConfirm={remove} onCancel={() => setConfirm(false)}>
        Выход удалится из настроек.
      </Dialog>
    </Card>
  )
}

const NAME_RE = /^[a-z][a-z0-9-]{0,14}$/

function NewOutput({ subs }: { subs: Sub[] | null }) {
  const { draft, setDraft } = useStore()
  const { open } = useNav()
  const [sub, setSub] = useState("")
  const [name, setName] = useState("")
  const [title, setTitle] = useState("")
  const [tried, setTried] = useState(false)
  if (!draft || !subs) return null
  const subId = sub || subs[0]?.id || ""
  if (subs.length === 0)
    return (
      <Card style={col("var(--an-space-4)")}>
        <CardHead title="Новый выход" />
        <div style={muted}>подписок нет</div>
        <Button tone="secondary" full icon={<Icon name="link" />} onClick={() => open({ kind: "subs" }, "more")}>
          Добавить подписку
        </Button>
      </Card>
    )
  const nameErr = !NAME_RE.test(name) ? "Латиница, цифры и дефис, до 15 знаков" : draft.outputs[name] ? "Такой выход уже есть" : null
  const add = () => {
    setTried(true)
    if (nameErr) return
    setDraft((m) => {
      m.outputs[name] = { name, kind: "vless", sub: subId, nodes: [], on_fail: "drop", ...(title.trim() ? { title: title.trim() } : {}) }
      return m
    })
    setName("")
    setTitle("")
    setTried(false)
  }
  return (
    <Card style={col("var(--an-space-4)")}>
      <CardHead title="Новый выход" />
      <Field label="Подписка">
        <Select value={subId} onChange={(e) => setSub(e.currentTarget.value)} style={{ width: "100%" }}>
          {subs.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name} · узлов: {s.nodes}
            </option>
          ))}
        </Select>
      </Field>
      <Field label="Имя" error={tried ? nameErr : null}>
        <Input mono value={name} placeholder="vless-nl" onInput={(e) => setName(e.currentTarget.value.toLowerCase())} autoCapitalize="none" autoCorrect="off" spellCheck={false} invalid={tried && !!nameErr} />
      </Field>
      <Field label="Подпись">
        <Input value={title} placeholder="Нидерланды" onInput={(e) => setTitle(e.currentTarget.value)} />
      </Field>
      <Button tone="secondary" full icon={<Icon name="plus" />} onClick={add}>
        Добавить выход
      </Button>
    </Card>
  )
}

export function Outputs() {
  const { draft, modelError } = useStore()
  const [subs, setSubs] = useState<Sub[] | null>(null)
  useEffect(() => {
    call("subs.list").then(setSubs).catch(() => setSubs([]))
  }, [])
  const outs = draft ? Object.values(draft.outputs).filter((o) => o.kind !== "direct") : null
  return (
    <>
      <Header title="Выходы" />
      <Body>
        {modelError ? <Empty icon="outputs" text={modelError} /> : null}
        {!outs && !modelError ? <Skeleton height={220} count={2} radius="var(--an-radius-card)" /> : null}
        {outs && outs.length === 0 ? <Empty icon="outputs" text="Выходов нет" /> : null}
        {outs?.map((o) => <OutputCard key={o.name} o={o} subs={subs} />)}
        {outs ? <NewOutput subs={subs} /> : null}
      </Body>
    </>
  )
}

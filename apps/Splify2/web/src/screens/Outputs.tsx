/**
 * Выходы: куда правила ведут трафик. У выхода VLESS — узлы подписки, выбранные по
 * предпочтению (первый ответивший забирает трафик), и замер задержки; у туннеля-интерфейса —
 * устройства по порядку; у WireGuard/AmneziaWG — сервер, обфускация, рукопожатие и объём из
 * ядра. Выбор узлов, «если всё упало», «через выход» и новые выходы правят черновик модели и
 * уходят в движок пилюлей «Применить», как и правила. Файл WireGuard логика проверяет и
 * сохраняет сразу (outputs.importAwg / pickAwg), а в черновик попадает только ссылка на него.
 */
import { useEffect, useMemo, useState, type ReactNode } from "react"
import { Badge, Button, Card, Dialog, Field, Input, Select, Skeleton, StatusDot, Textarea } from "@andromeda/ui"
import { useStore } from "../store"
import { useNav } from "../nav"
import { call, errorText } from "../bridge"
import { Body, CardHead, Divider, Empty, Header, KV, Segmented, col, ellipsis, muted, rowS } from "../ui"
import { Icon } from "../icons"
import { ON_FAIL_TEXT, fmtAgoSec, fmtBytes, outputLabel, outputState, outputSub, usedOutputs, viaCapable, viaTargets, type Tone } from "../format"
import type { AwgImport, Model, ModelOutput, OnFail, OutputStatus, Sub, VlessNodesReply, VlessProbe } from "../types"

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
      if (r.results) {
        setProbe(new Map(r.results.map((p) => [p.index, p])))
        setOrder("delay")
        setErr(null)
      } else setErr(r.error || "Замер не удался")
    } catch (e) {
      setErr(errorText(e))
    } finally {
      setProbing(false)
    }
  }

  const toggle = (idx: number) =>
    setDraft((m) => {
      const out = m.outputs.find((x) => x.name === o.name)
      if (out) {
        const cur = out.nodes ?? []
        out.nodes = cur.includes(idx) ? cur.filter((n) => n !== idx) : [...cur, idx]
      }
      return m
    })
  const firstWorking = () =>
    setDraft((m) => {
      const out = m.outputs.find((x) => x.name === o.name)
      if (out) out.nodes = []
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

/** Строка «подпись — выбор» в карточке выхода. */
function SelectRow({ label, value, onChange, children }: { label: string; value: string; onChange: (v: string) => void; children: ReactNode }) {
  return (
    <div style={{ ...rowS("var(--an-space-6)"), justifyContent: "space-between" }}>
      <span style={{ font: "var(--an-text-body-sm)", color: "var(--an-text-secondary)", flex: "0 0 auto" }}>{label}</span>
      <Select value={value} onChange={(e) => onChange(e.currentTarget.value)} style={{ minWidth: 0, maxWidth: 200 }}>
        {children}
      </Select>
    </div>
  )
}

/** WireGuard: то, что знает файл, и то, что видит ядро (status → awg). */
function AwgDetails({ o, st, live }: { o: ModelOutput; st?: OutputStatus; live: boolean }) {
  const a = st?.awg
  const server = (live && a?.endpoint) || o.info?.endpoint || "не задан"
  return (
    <>
      <KV k="сервер" v={<span style={{ font: "var(--an-text-code)" }}>{server}</span>} />
      <KV k="обфускация" v={o.info?.obfs ? "включена" : "выключена"} />
      {live && a ? (
        a.live ? (
          <>
            <KV k="рукопожатие" v={fmtAgoSec(a.handshake_ago)} />
            <KV k="принято / отправлено" v={<span className="sp-tab">↓ {fmtBytes(a.rx)} · ↑ {fmtBytes(a.tx)}</span>} />
          </>
        ) : (
          <KV k="туннель" v="не поднят" />
        )
      ) : null}
    </>
  )
}

function OutputCard({ o, subs }: { o: ModelOutput; subs: Sub[] | null }) {
  const { status, engine, draft, setDraft, toast } = useStore()
  const [confirm, setConfirm] = useState(false)
  const [replacing, setReplacing] = useState(false)
  const rulesHere = (draft?.channels ?? []).filter((c) => c.out === o.name).map((c) => c.name)
  const viaHere = (draft?.outputs ?? []).filter((x) => x.via === o.name).map((x) => x.name)
  const targets = useMemo(() => viaTargets(draft, o.name), [draft, o.name])
  const setVia = (v: string) =>
    setDraft((m) => {
      const out = m.outputs.find((x) => x.name === o.name)
      if (out) {
        if (v) out.via = v
        else delete out.via
      }
      return m
    })
  // Новый файл — новая ссылка; ключи и сервер меняются, имя и правила остаются.
  const replaceFile = async () => {
    setReplacing(true)
    try {
      const r = await call("outputs.pickAwg", { name: o.name })
      if (r.picked)
        setDraft((m) => {
          const out = m.outputs.find((x) => x.name === o.name)
          if (out) {
            out.conf = r.conf
            out.info = r.info
          }
          return m
        })
    } catch (e) {
      toast(errorText(e), "bad")
    } finally {
      setReplacing(false)
    }
  }
  const remove = () => {
    setConfirm(false)
    setDraft((m) => {
      m.outputs = m.outputs.filter((x) => x.name !== o.name)
      return m
    })
  }
  const st = status?.outputs[o.name]
  const s = outputState(st, !!engine?.enabled, usedOutputs(draft).has(o.name), !!status)
  const sub = o.sub ? subs?.find((x) => x.id === o.sub) : undefined
  const setOnFail = (v: OnFail) =>
    setDraft((m) => {
      const out = m.outputs.find((x) => x.name === o.name)
      if (out) out.on_fail = v
      return m
    })
  return (
    <Card style={col()}>
      <div style={{ ...rowS("var(--an-space-5)"), justifyContent: "space-between" }}>
        <div style={{ ...col("2px"), minWidth: 0 }}>
          <h2 style={{ font: "var(--an-text-heading)", ...ellipsis }}>{o.name}</h2>
          <span style={{ ...muted, ...ellipsis }}>{outputSub(o, (id) => subs?.find((x) => x.id === id)?.name)}</span>
        </div>
        <Badge tone={TONE_BADGE[s.tone]}>{s.text}</Badge>
      </div>
      <div style={col("var(--an-space-2)")}>
        {o.kind === "vless" ? <KV k="подписка" v={sub ? `${sub.name} · узлов: ${sub.nodes}` : "нет — выберите другую"} /> : null}
        {o.kind === "awg" ? <AwgDetails o={o} st={st} live={!!engine?.enabled && !!status} /> : null}
        {st?.up && st.device ? <KV k="устройство" v={<span style={{ font: "var(--an-text-code)" }}>{st.device}</span>} /> : null}
        {o.kind === "tgws" ? (
          <KV k="если всё упало" v={ON_FAIL_TEXT.drop} />
        ) : (
          <SelectRow label="если всё упало" value={o.on_fail ?? "drop"} onChange={(v) => setOnFail(v as OnFail)}>
            {(Object.keys(ON_FAIL_TEXT) as OnFail[]).map((k) => (
              <option key={k} value={k}>
                {ON_FAIL_TEXT[k]}
              </option>
            ))}
          </SelectRow>
        )}
        {viaCapable(o) ? (
          <SelectRow label="через выход" value={o.via ?? ""} onChange={setVia}>
            <option value="">нет</option>
            {targets.map((t) => (
              <option key={t.name} value={t.name}>
                {outputLabel(t.name)}
              </option>
            ))}
            {o.via && !targets.some((t) => t.name === o.via) ? <option value={o.via}>{outputLabel(o.via)}</option> : null}
          </SelectRow>
        ) : null}
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
      {o.kind === "awg" && o.info?.ignored.length ? <div style={muted}>не используются из файла: {o.info.ignored.join(", ")}</div> : null}
      {o.kind === "awg" ? (
        <Button tone="secondary" size="sm" icon={<Icon name="file" />} busy={replacing} onClick={() => void replaceFile()} style={{ alignSelf: "flex-start" }}>
          Заменить файл
        </Button>
      ) : null}
      {rulesHere.length || viaHere.length ? (
        <div style={col("var(--an-space-1)")}>
          {rulesHere.length ? <div style={muted}>в правилах: {rulesHere.join(", ")}</div> : null}
          {viaHere.length ? <div style={muted}>через него идут: {viaHere.join(", ")}</div> : null}
        </div>
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

function nameError(name: string, draft: Model): string | null {
  if (!NAME_RE.test(name)) return "Латиница, цифры и дефис, до 15 знаков"
  if (name === "direct" || draft.outputs.some((o) => o.name === name)) return "Такое имя уже занято"
  return null
}

/** Имя выхода из имени файла: «Amsterdam_NL.conf» → «amsterdam-nl», свободное в черновике. */
function nameFromFile(file: string | undefined, draft: Model): string {
  let base = (file ?? "").replace(/\.[a-z0-9]+$/i, "").toLowerCase().replace(/[^a-z0-9-]+/g, "-").replace(/^[^a-z]+/, "").replace(/-+$/, "").slice(0, 15)
  if (!NAME_RE.test(base)) base = "wireguard"
  let n = base
  for (let k = 2; nameError(n, draft); k++) n = base.slice(0, 15 - String(k).length - 1) + "-" + k
  return n
}

type NewKind = "vless" | "awg"

function NewVless({ subs }: { subs: Sub[] }) {
  const { draft, setDraft } = useStore()
  const { open } = useNav()
  const [sub, setSub] = useState("")
  const [name, setName] = useState("")
  const [tried, setTried] = useState(false)
  if (!draft) return null
  const subId = sub || subs[0]?.id || ""
  if (subs.length === 0)
    return (
      <>
        <div style={muted}>подписок нет</div>
        <Button tone="secondary" full icon={<Icon name="link" />} onClick={() => open({ kind: "subs" }, "more")}>
          Добавить подписку
        </Button>
      </>
    )
  const nameErr = nameError(name, draft)
  const add = () => {
    setTried(true)
    if (nameErr) return
    setDraft((m) => {
      m.outputs.push({ name, kind: "vless", sub: subId, nodes: [], on_fail: "drop" })
      return m
    })
    setName("")
    setTried(false)
  }
  return (
    <>
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
      <Button tone="secondary" full icon={<Icon name="plus" />} onClick={add}>
        Добавить выход
      </Button>
    </>
  )
}

function NewAwg() {
  const { draft, setDraft, toast } = useStore()
  const [name, setName] = useState("")
  const [text, setText] = useState("")
  const [tried, setTried] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState<"file" | "text" | null>(null)
  if (!draft) return null
  const nameErr = name ? nameError(name, draft) : null
  const push = (n: string, r: AwgImport) => {
    setDraft((m) => {
      m.outputs.push({ name: n, kind: "awg", conf: r.conf, info: r.info, on_fail: "drop" })
      return m
    })
    toast(`Выход ${n} добавлен`)
    setName("")
    setText("")
    setTried(false)
    setErr(null)
  }
  const pick = async () => {
    setTried(true)
    if (nameErr) return
    setBusy("file")
    try {
      const r = await call("outputs.pickAwg", name ? { name } : {})
      if (r.picked) push(name || nameFromFile(r.file, draft), r)
    } catch (e) {
      setErr(errorText(e))
    } finally {
      setBusy(null)
    }
  }
  const paste = async () => {
    setTried(true)
    if (!name || nameErr) return
    if (!text.trim()) {
      setErr("Вставьте текст файла")
      return
    }
    setBusy("text")
    try {
      push(name, await call("outputs.importAwg", { name, text }))
    } catch (e) {
      setErr(errorText(e))
    } finally {
      setBusy(null)
    }
  }
  return (
    <>
      <Field label="Имя" error={tried ? nameErr ?? (!name && busy !== "file" && text ? "Введите имя" : null) : null}>
        <Input mono value={name} placeholder="nl-home" onInput={(e) => setName(e.currentTarget.value.toLowerCase())} autoCapitalize="none" autoCorrect="off" spellCheck={false} invalid={tried && !!nameErr} />
      </Field>
      <Button tone="secondary" full icon={<Icon name="file" />} busy={busy === "file"} onClick={() => void pick()}>
        Выбрать файл
      </Button>
      <div style={{ ...rowS("var(--an-space-5)"), color: "var(--an-text-muted)", font: "var(--an-text-caption)" }}>
        <span style={{ flex: 1, height: 1, background: "var(--an-border)" }} />
        или вставьте текст файла
        <span style={{ flex: 1, height: 1, background: "var(--an-border)" }} />
      </div>
      <Field label="Текст файла" error={err}>
        <Textarea
          rows={8}
          mono
          value={text}
          placeholder={"[Interface]\nPrivateKey = …\nAddress = 10.8.0.2/32\n\n[Peer]\nPublicKey = …\nEndpoint = vpn.example.net:51820"}
          onInput={(e) => {
            setText(e.currentTarget.value)
            setErr(null)
          }}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
        />
      </Field>
      <Button tone="secondary" full icon={<Icon name="plus" />} busy={busy === "text"} disabled={!text.trim()} onClick={() => void paste()}>
        Добавить выход
      </Button>
    </>
  )
}

function NewOutput({ subs }: { subs: Sub[] | null }) {
  const { draft } = useStore()
  const [kind, setKind] = useState<NewKind>("vless")
  if (!draft || !subs) return null
  return (
    <Card style={col("var(--an-space-4)")}>
      <CardHead title="Добавить выход" />
      <Segmented
        items={[
          { value: "vless", label: "VLESS" },
          { value: "awg", label: "WireGuard · AmneziaWG" },
        ]}
        value={kind}
        onChange={setKind}
      />
      {kind === "vless" ? <NewVless subs={subs} /> : <NewAwg />}
    </Card>
  )
}

export function Outputs() {
  const { draft, modelError } = useStore()
  const [subs, setSubs] = useState<Sub[] | null>(null)
  useEffect(() => {
    call("subs.list").then(setSubs).catch(() => setSubs([]))
  }, [])
  const outs = draft ? draft.outputs.filter((o) => o.kind !== "direct") : null
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

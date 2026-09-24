/**
 * Соединения и DNS: соединения, которые движок повёл в свои выходы, и недавние имена с тем,
 * куда они попали; из строки — «добавить правило». Плюс «куда пойдёт запрос» (engine.explain).
 *
 * Формы — `steer conns` и `steer dns-log` (steer/docs/ctl.md). Приложения и имени хоста у
 * соединения нет — conntrack их не хранит, — поэтому строка соединения называет адрес, порт и
 * выход, а правило по выходу подбирается, только если в этот выход ведёт ровно одно правило:
 * иначе экран назвал бы правило, которого движок не называл. Движок без этих команд —
 * `unknown-method`, экран показывает пустое состояние.
 */
import { useCallback, useEffect, useState } from "react"
import { Button, Card, CodeBlock, IconButton, Input, Skeleton } from "@andromeda/ui"
import { BridgeError, call, errorText } from "../bridge"
import { useStore } from "../store"
import { useNav, type RulePrefill } from "../nav"
import { parseConns, parseDnsLog } from "../parse"
import { Body, CardHead, Empty, Header, Segmented, col, ellipsis, muted, rowS } from "../ui"
import { Icon } from "../icons"
import { fmtBytes, fmtDuration, fmtInt } from "../format"
import type { Conn, DnsName, Model } from "../types"

type View = "conns" | "dns"

function Explain() {
  const [q, setQ] = useState("")
  const [busy, setBusy] = useState(false)
  const [out, setOut] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const run = async () => {
    if (!q.trim()) return
    setBusy(true)
    setErr(null)
    try {
      const r = await call("engine.explain", { q: q.trim() })
      setOut(typeof (r as { text?: unknown }).text === "string" ? (r as { text: string }).text : JSON.stringify(r, null, 2))
    } catch (e) {
      setOut(null)
      setErr(errorText(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Card style={col("var(--an-space-4)")}>
      <CardHead title="Куда пойдёт запрос" />
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void run()
        }}
        style={{ display: "flex", gap: "var(--an-space-3)" }}
      >
        <Input
          mono
          value={q}
          placeholder="youtube.com или 1.2.3.4"
          onInput={(e) => setQ(e.currentTarget.value)}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          enterKeyHint="go"
          style={{ flex: 1, minWidth: 0 }}
        />
        <Button tone="secondary" type="submit" busy={busy} disabled={!q.trim()}>
          Проверить
        </Button>
      </form>
      {err ? <div style={{ font: "var(--an-text-body-sm)", color: "var(--an-danger-ink)" }}>{err}</div> : null}
      {out ? <CodeBlock>{out}</CodeBlock> : null}
    </Card>
  )
}

function useFeed<T>(method: "engine.conns" | "engine.dnsLog", parse: (v: unknown) => T, active: boolean) {
  const [data, setData] = useState<T | null>(null)
  const [missing, setMissing] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const load = useCallback(() => {
    call(method)
      .then((v) => {
        setData(parse(v))
        setErr(null)
        setMissing(false)
      })
      .catch((e) => {
        if (e instanceof BridgeError && e.code === "unknown-method") setMissing(true)
        else setErr(errorText(e))
      })
  }, [method, parse])
  useEffect(() => {
    if (!active) return
    load()
    const t = window.setInterval(() => {
      if (document.visibilityState === "visible" && !missing) load()
    }, 5000)
    return () => window.clearInterval(t)
  }, [active, load, missing])
  return { data, missing, err, load }
}

const outName = (out: string | null | undefined) => (!out || out === "direct" ? "напрямую" : out)

/** Правило по выходу — только когда в выход ведёт ровно одно включённое правило. */
function ruleByOut(model: Model | null, out: string | null): string | undefined {
  if (!out) return undefined
  const rules = (model?.channels ?? []).filter((c) => c.enabled && c.out === out)
  return rules.length === 1 ? rules[0].name : undefined
}

function Row({ title, sub, meta, onAdd }: { title: string; sub: string; meta?: string; onAdd?: () => void }) {
  return (
    <div style={{ ...rowS("var(--an-space-5)"), minHeight: 56, padding: "4px 0" }}>
      <div style={{ ...col("1px"), flex: 1, minWidth: 0 }}>
        <span style={{ font: "var(--an-text-code)", color: "var(--an-text)", ...ellipsis }}>{title}</span>
        <span style={{ font: "var(--an-text-caption)", color: "var(--an-text-secondary)", ...ellipsis }}>{sub}</span>
        {meta ? <span className="sp-tab" style={{ font: "var(--an-text-micro)", color: "var(--an-text-muted)", ...ellipsis }}>{meta}</span> : null}
      </div>
      {onAdd ? (
        <IconButton label="Добавить правило" onClick={onAdd}>
          <Icon name="plus" size={18} />
        </IconButton>
      ) : null}
    </div>
  )
}

/** Состояние TCP словами: установленное — обычное дело и не называется; слова conntrack
 *  (time_wait, syn_sent…) человеку ничего не говорят. */
function stateText(st?: string): string | null {
  if (!st || st === "established" || st === "none") return null
  if (st.startsWith("syn")) return "устанавливается"
  return "закрывается"
}

function ConnRow({ c, rule, onAdd }: { c: Conn; rule?: string; onAdd?: () => void }) {
  const dst = c.family === "ipv6" && c.dport ? `[${c.dst}]:${c.dport}` : c.dport ? `${c.dst}:${c.dport}` : c.dst
  const sub = c.out ? `${rule ? `${rule} → ` : ""}${outName(c.out)}` : "выход уже убран"
  const bytes = c.reply_bytes != null || c.bytes != null ? `↓ ${fmtBytes(c.reply_bytes)} · ↑ ${fmtBytes(c.bytes)}` : null
  return <Row title={dst} sub={sub} meta={[c.proto, stateText(c.state), bytes].filter(Boolean).join(" · ")} onAdd={onAdd} />
}

function DnsRow({ e, onAdd }: { e: DnsName; onAdd: () => void }) {
  const sub = e.channel ? `${e.channel} → ${outName(e.out)}` : "без правила → напрямую"
  const meta = `запросов: ${fmtInt(e.count)} · ${e.ago < 45 ? "только что" : `${fmtDuration(e.ago)} назад`}`
  return <Row title={e.name} sub={sub} meta={meta} onAdd={onAdd} />
}

export function Conns() {
  const { engine, draft } = useStore()
  const { open } = useNav()
  const [view, setView] = useState<View>("conns")
  const conns = useFeed("engine.conns", parseConns, view === "conns")
  const dns = useFeed("engine.dnsLog", parseDnsLog, view === "dns")

  const firstOut = draft?.outputs.find((o) => o.kind !== "tgws")?.name
  const addRule = (text: string, name: string) => {
    const prefill: RulePrefill = { name, who: { kind: "phone" }, listText: text, ...(firstOut ? { out: firstOut } : {}) }
    open({ kind: "rule", index: -1, prefill }, "rules")
  }

  const feed = view === "conns" ? conns : dns
  const off = engine && !engine.enabled
  const rows = view === "conns" ? conns.data?.rows : dns.data?.rows
  return (
    <>
      <Header
        title="Соединения и DNS"
        right={
          !feed.missing ? (
            <IconButton label="Обновить" onClick={feed.load}>
              <Icon name="refresh" size={18} />
            </IconButton>
          ) : null
        }
      />
      <Body>
        <Explain />
        <Segmented<View>
          items={[
            { value: "conns", label: "Соединения" },
            { value: "dns", label: "Имена" },
          ]}
          value={view}
          onChange={setView}
        />
        <Card style={col("var(--an-space-1)")}>
          {feed.missing ? (
            <Empty icon={view === "conns" ? "conns" : "list"} text="Недоступно в этой версии системы" />
          ) : off ? (
            <Empty icon="power" text="Маршрутизация выключена" />
          ) : feed.err && !rows ? (
            <Empty icon="conns" text={feed.err} />
          ) : !rows ? (
            <Skeleton height={52} count={4} />
          ) : view === "dns" && dns.data && !dns.data.running ? (
            <Empty icon="list" text="Правил по доменам сейчас нет — имена не записываются" />
          ) : rows.length === 0 ? (
            <Empty icon={view === "conns" ? "conns" : "list"} text={view === "conns" ? "Соединений через выходы нет" : "Запросов не было"} />
          ) : view === "conns" && conns.data ? (
            <>
              <div style={muted}>
                соединений: {fmtInt(conns.data.total)}
                {conns.data.truncated ? ` · показано: ${fmtInt(conns.data.rows.length)}` : ""}
              </div>
              {conns.data.rows.map((c, i) => (
                <ConnRow
                  key={i}
                  c={c}
                  rule={ruleByOut(draft, c.out)}
                  // Свои списки держат подсети IPv4 (как на роутере) — для IPv6 правило не заготовить.
                  onAdd={c.family === "ipv4" ? () => addRule(`${c.dst}/32`, c.dst) : undefined}
                />
              ))}
            </>
          ) : dns.data ? (
            <>
              <div style={muted}>имён: {fmtInt(dns.data.rows.length)}</div>
              {dns.data.rows.map((e, i) => (
                <DnsRow key={i} e={e} onAdd={() => addRule(e.name, e.name)} />
              ))}
            </>
          ) : null}
        </Card>
      </Body>
    </>
  )
}

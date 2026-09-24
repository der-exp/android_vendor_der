/**
 * Соединения и DNS: живые соединения с правилом и выходом, недавние имена и куда они
 * попали; из строки — «добавить правило». Плюс «куда пойдёт запрос» (engine.explain),
 * который есть уже сейчас.
 *
 * `engine.conns` и `engine.dnsLog` появятся вместе с командами сокета; до того оболочка
 * отвечает `unknown-method`, и экран показывает пустое состояние. Разбор ответа готов
 * (parse.ts), форма строк — types.ts (Conn, DnsEntry).
 */
import { useCallback, useEffect, useState } from "react"
import { Button, Card, CodeBlock, IconButton, Input, Skeleton } from "@andromeda/ui"
import { BridgeError, call, errorText } from "../bridge"
import { useStore } from "../store"
import { useNav } from "../nav"
import { useAppLabels } from "../apps"
import { parseConns, parseDnsLog } from "../parse"
import { AppIcon, Body, CardHead, Empty, Header, Segmented, col, ellipsis, muted, rowS } from "../ui"
import { Icon } from "../icons"
import { fmtBytes, fmtDuration, fmtTime } from "../format"
import type { AppInfo, Conn, DnsEntry, ModelChannel } from "../types"

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

function useFeed<T>(method: "engine.conns" | "engine.dnsLog", parse: (v: unknown) => T[], active: boolean) {
  const [rows, setRows] = useState<T[] | null>(null)
  const [missing, setMissing] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const load = useCallback(() => {
    call(method)
      .then((v) => {
        setRows(parse(v))
        setErr(null)
        setMissing(false)
      })
      .catch((e) => {
        if (e instanceof BridgeError && e.code === "unknown-method") setMissing(true)
        else setErr(errorText(e))
        setRows((r) => r ?? [])
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
  return { rows, missing, err, load }
}

function Route({ channel, out }: { channel?: string; out?: string }) {
  if (!channel) return <>без правила → напрямую</>
  return (
    <>
      {channel} → {out === "direct" || !out ? "напрямую" : out}
    </>
  )
}

function ConnRow({ c, app, onAdd }: { c: Conn; app?: AppInfo; onAdd: () => void }) {
  const name = c.host || c.dst
  return (
    <div style={{ ...rowS("var(--an-space-5)"), minHeight: 56, padding: "4px 0" }}>
      {app ? <AppIcon pkg={app.pkg} label={app.label} size={32} /> : <span style={{ width: 32, flex: "0 0 auto", color: "var(--an-text-muted)", display: "inline-flex", justifyContent: "center" }}><Icon name="phone" size={18} /></span>}
      <div style={{ ...col("1px"), flex: 1, minWidth: 0 }}>
        <span style={{ font: "var(--an-text-code)", color: "var(--an-text)", ...ellipsis }}>
          {name}
          {c.dport ? `:${c.dport}` : ""}
        </span>
        <span style={{ font: "var(--an-text-caption)", color: "var(--an-text-secondary)", ...ellipsis }}>
          {app ? `${app.label} · ` : ""}
          <Route channel={c.channel} out={c.out} />
        </span>
        <span className="sp-tab" style={{ font: "var(--an-text-micro)", color: "var(--an-text-muted)", ...ellipsis }}>
          {[c.proto, c.bytes != null ? fmtBytes(c.bytes) : null, c.age != null ? fmtDuration(c.age) : null].filter(Boolean).join(" · ")}
        </span>
      </div>
      <IconButton label="Добавить правило" onClick={onAdd}>
        <Icon name="plus" size={18} />
      </IconButton>
    </div>
  )
}

function DnsRow({ e, app, onAdd }: { e: DnsEntry; app?: AppInfo; onAdd: () => void }) {
  return (
    <div style={{ ...rowS("var(--an-space-5)"), minHeight: 56, padding: "4px 0" }}>
      {app ? <AppIcon pkg={app.pkg} label={app.label} size={32} /> : <span style={{ width: 32, flex: "0 0 auto", color: "var(--an-text-muted)", display: "inline-flex", justifyContent: "center" }}><Icon name="phone" size={18} /></span>}
      <div style={{ ...col("1px"), flex: 1, minWidth: 0 }}>
        <span style={{ font: "var(--an-text-code)", color: "var(--an-text)", ...ellipsis }}>{e.name}</span>
        <span style={{ font: "var(--an-text-caption)", color: "var(--an-text-secondary)", ...ellipsis }}>
          {app ? `${app.label} · ` : ""}
          <Route channel={e.channel} out={e.out} />
        </span>
        <span className="sp-tab" style={{ font: "var(--an-text-micro)", color: "var(--an-text-muted)", ...ellipsis }}>
          {[e.at ? fmtTime(e.at) : null, e.qtype, e.answers?.length ? e.answers.join(", ") : null].filter(Boolean).join(" · ")}
        </span>
      </div>
      <IconButton label="Добавить правило" onClick={onAdd}>
        <Icon name="plus" size={18} />
      </IconButton>
    </div>
  )
}

export function Conns() {
  const { engine, draft } = useStore()
  const { open } = useNav()
  const [view, setView] = useState<View>("conns")
  const [, apps] = useAppLabels()
  const conns = useFeed("engine.conns", parseConns, view === "conns")
  const dns = useFeed("engine.dnsLog", parseDnsLog, view === "dns")
  const app = (uid?: number) => (uid == null ? undefined : apps?.find((a) => a.uid === uid))

  const firstOut = draft ? Object.values(draft.outputs).find((o) => o.kind !== "direct")?.name : undefined
  const addRule = (host: string | undefined, ip: string | undefined, uid?: number) => {
    const a = app(uid)
    const prefill: Partial<ModelChannel> = {
      name: host || ip || "",
      who: a ? "apps" : "phone",
      ...(a ? { uids: [a.uid] } : {}),
      match: host ? { domains: [host] } : ip ? { prefixes: [ip.includes(":") ? `${ip}/128` : `${ip}/32`] } : {},
      ...(firstOut ? { out: firstOut } : {}),
    }
    open({ kind: "rule", index: -1, prefill }, "rules")
  }

  const feed = view === "conns" ? conns : dns
  const off = engine && !engine.enabled
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
            <Empty icon={view === "conns" ? "conns" : "list"} text="Недоступно в этой версии приложения" />
          ) : off ? (
            <Empty icon="power" text="Маршрутизация выключена" />
          ) : feed.err ? (
            <Empty icon="conns" text={feed.err} />
          ) : feed.rows === null ? (
            <Skeleton height={52} count={4} />
          ) : feed.rows.length === 0 ? (
            <Empty icon={view === "conns" ? "conns" : "list"} text={view === "conns" ? "Соединений нет" : "Запросов не было"} />
          ) : view === "conns" ? (
            <>
              <div style={muted}>соединений: {feed.rows.length}</div>
              {(conns.rows ?? []).map((c, i) => (
                <ConnRow key={i} c={c} app={app(c.uid)} onAdd={() => addRule(c.host, c.dst, c.uid)} />
              ))}
            </>
          ) : (
            <>
              <div style={muted}>запросов: {feed.rows.length}</div>
              {(dns.rows ?? []).map((e, i) => (
                <DnsRow key={i} e={e} app={app(e.uid)} onAdd={() => addRule(e.name, undefined, e.uid)} />
              ))}
            </>
          )}
        </Card>
      </Body>
    </>
  )
}

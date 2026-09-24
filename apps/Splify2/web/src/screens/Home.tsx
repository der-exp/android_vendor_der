/**
 * Главная: включена ли маршрутизация и работает ли она, сеть, выходы, объём по правилам,
 * Частный DNS. Всё — состояние; действие одно — главный переключатель.
 */
import { Badge, Callout, Card, Meter, Skeleton, StatPair, StatusDot, Switch, Verdict } from "@andromeda/ui"
import { useStore } from "../store"
import { useNav } from "../nav"
import { Body, CardHead, Divider, Header, TapRow, col, muted, rowS, ellipsis } from "../ui"
import { Icon, type IconName } from "../icons"
import { fmtBytes, kindText, outputLabel, outputState, usedOutputs } from "../format"
import type { NetworkInfo, PrivateDns } from "../types"
import logo from "@andromeda/ui/assets/logo-andromeda.svg"

/**
 * Заголовок состояния. Та же закрытая пара «точка + фраза», что у Verdict пакета, но точка
 * своя: у Verdict живое состояние пульсирует прозрачностью до 0,3, и в тёмной теме зелёная
 * точка на тёмной карточке в этой фазе почти пропадает — «работает» читалось как «погасло».
 * Здесь точка держит полный цвет, а живость показывает кольцо вокруг — полупрозрачным тем же
 * цветом состояния (токены --an-success/--an-danger/--an-warn), заметное в обеих темах.
 */
const HEAD = {
  running: { tone: "ok", color: "var(--an-success)", text: "Маршрутизация работает" },
  broken: { tone: "bad", color: "var(--an-danger)", text: "Есть поломки" },
  silent: { tone: "warn", color: "var(--an-warn)", text: "Движок не отвечает" },
  off: { tone: "off", color: "var(--an-text-muted)", text: "Маршрутизация выключена" },
} as const

function Head({ state, meta }: { state: keyof typeof HEAD; meta?: string }) {
  const h = HEAD[state]
  return (
    <div>
      <div style={rowS("var(--an-space-5)")}>
        <StatusDot
          tone={h.tone}
          size={11}
          style={state === "off" ? undefined : { boxShadow: `0 0 0 4px color-mix(in srgb, ${h.color} 28%, transparent)` }}
        />
        <h1 style={{ font: "var(--an-text-verdict)", letterSpacing: "var(--an-tracking-verdict)" }}>{h.text}</h1>
      </div>
      {meta ? <p style={{ marginTop: "var(--an-space-3)", font: "var(--an-text-body-sm)", color: "var(--an-text-muted)" }}>{meta}</p> : null}
    </div>
  )
}

function MainCard() {
  const { engine, engineError, status, statusError, draft, setEnabled, engineBusy, applyError } = useStore()
  const used = usedOutputs(draft)
  const enabledRules = draft?.channels.filter((c) => c.enabled !== false).length ?? 0
  const silent = (engine && !engine.reachable) || !!engineError || (engine?.enabled && !!statusError && !status)
  const broken =
    !!status &&
    ((status.warnings?.length ?? 0) > 0 ||
      [...used].some((o) => {
        const st = status.outputs[o]
        return st && st.kind !== "direct" && !st.up && st.probe?.state !== "probing"
      }))

  let head
  if (!engine && !engineError) head = <Verdict state="loading" />
  else if (silent) {
    const why = engineError || statusError
    head = <Head state="silent" meta={why && !why.startsWith("Движок не отвечает") ? why : undefined} />
  } else if (!engine?.enabled) head = <Head state="off" meta="весь трафик идёт напрямую" />
  else head = <Head state={broken ? "broken" : "running"} meta={`правил включено: ${enabledRules}`} />

  const warnings = status?.warnings ?? []
  return (
    <Card style={col()}>
      {head}
      {engine?.enabled && warnings.length ? (
        <Callout tone="warn" title="предупреждений" count={warnings.length}>
          <div style={col("var(--an-space-3)")}>
            {warnings.map((w, i) => (
              <div key={i} style={{ font: "var(--an-text-body-sm)", color: "var(--an-text)" }}>{w.text}</div>
            ))}
          </div>
        </Callout>
      ) : null}
      {applyError ? (
        <Callout tone="danger" title="Правила не применились" verbatim={applyError.message}>
          {applyError.rolledBack ? <div style={muted}>работают прежние правила</div> : null}
        </Callout>
      ) : null}
      <Divider />
      <div style={{ ...rowS("var(--an-space-6)"), justifyContent: "space-between", minHeight: 44 }}>
        <span style={{ font: "var(--an-text-body)", fontWeight: "var(--an-weight-medium)" }}>Маршрутизация</span>
        <Switch
          size="lg"
          label="Маршрутизация"
          checked={!!engine?.enabled}
          disabled={!engine || engineBusy || !engine.reachable}
          onChange={() => engine && void setEnabled(!engine.enabled)}
        />
      </div>
    </Card>
  )
}

const NET: Record<NetworkInfo["type"], { icon: IconName; text: string }> = {
  wifi: { icon: "wifi", text: "Wi-Fi" },
  cellular: { icon: "cell", text: "Мобильная сеть" },
  ethernet: { icon: "ethernet", text: "Ethernet" },
  none: { icon: "offline", text: "Нет сети" },
}

function NetworkCard() {
  const { network } = useStore()
  if (!network) return <Skeleton height={76} radius="var(--an-radius-card)" />
  const n = NET[network.type] ?? NET.none
  return (
    <Card style={col("var(--an-space-4)")}>
      <CardHead title="Сеть" />
      <div style={{ ...rowS("var(--an-space-6)"), minHeight: 32 }}>
        <span style={{ color: "var(--an-text-secondary)" }}><Icon name={n.icon} size={18} /></span>
        <span style={{ flex: 1, font: "var(--an-text-body)", ...ellipsis }}>
          {network.name && network.type !== "none" ? `${n.text} · ${network.name}` : n.text}
        </span>
        {network.metered ? <Badge tone="warn">лимитная</Badge> : null}
      </div>
    </Card>
  )
}

function OutputsCard() {
  const { draft, status, engine } = useStore()
  const { go } = useNav()
  if (!draft) return <Skeleton height={160} radius="var(--an-radius-card)" />
  const used = usedOutputs(draft)
  const outs = draft.outputs.filter((o) => o.kind !== "direct")
  const states = outs.map((o) => outputState(status?.outputs[o.name], !!engine?.enabled, used.has(o.name), !!status))
  const working = states.filter((s) => s.tone === "ok").length
  return (
    <Card style={col("var(--an-space-3)")}>
      <CardHead title="Выходы" meta={engine?.enabled && status ? `работают: ${working} из ${outs.length}` : undefined} />
      {outs.length === 0 ? <div style={muted}>выходов нет</div> : null}
      {outs.map((o, i) => {
        const st = status?.outputs[o.name]
        const s = states[i]
        // «через» важнее имени устройства: строка одна, и влезают два слова после вида.
        const sub = [kindText(o), o.via ? `через ${outputLabel(o.via)}` : st?.up && st.device ? st.device : null].filter(Boolean).join(" · ")
        return <TapRow key={o.name} dot={s.tone} title={outputLabel(o.name)} subtitle={sub} right={s.text} onClick={() => go("outputs")} />
      })}
    </Card>
  )
}

function TrafficCard() {
  const { status, statusError, engine } = useStore()
  if (!engine?.enabled || !engine.reachable || (statusError && !status)) return null
  if (!status) return <Skeleton height={180} radius="var(--an-radius-card)" />
  const rows = status.channels
    .map((c) => ({ label: c.channels?.length ? c.channels.join(", ") : c.name, out: c.out, down: c.down_bytes ?? 0, up: c.bytes ?? 0, hasDown: c.down_bytes != null }))
    .sort((a, b) => b.down + b.up - (a.down + a.up))
  const totalDown = rows.reduce((s, r) => s + r.down, 0)
  const totalUp = rows.reduce((s, r) => s + r.up, 0)
  const max = Math.max(1, ...rows.map((r) => r.down + r.up))
  return (
    <Card style={col()}>
      <CardHead title="Трафик по правилам" meta="с включения" />
      <dl style={{ display: "flex", gap: "var(--an-space-12)", margin: 0 }}>
        <StatPair label="скачано ↓" value={<span className="sp-tab">{fmtBytes(totalDown)}</span>} />
        <StatPair label="отдано ↑" value={<span className="sp-tab">{fmtBytes(totalUp)}</span>} />
      </dl>
      {rows.length === 0 ? <div style={muted}>трафика по правилам ещё не было</div> : null}
      <div style={col("var(--an-space-6)")}>
        {rows.map((r, i) => (
          <div key={r.label + i} style={col("var(--an-space-2)")}>
            <div style={{ display: "flex", justifyContent: "space-between", gap: "var(--an-space-6)", alignItems: "baseline" }}>
              <span style={{ font: "var(--an-text-body-sm)", ...ellipsis }}>
                {r.label} <span style={{ color: "var(--an-text-muted)" }}>→ {r.out === "direct" ? "напрямую" : r.out}</span>
              </span>
              <span className="sp-tab" style={{ ...muted, flex: "0 0 auto" }}>
                {r.hasDown ? `↓ ${fmtBytes(r.down)} · ` : ""}↑ {fmtBytes(r.up)}
              </span>
            </div>
            <Meter value={((r.down + r.up) / max) * 100} tone={r.out === "direct" ? "off" : "accent"} delay={i * 70} />
          </div>
        ))}
      </div>
    </Card>
  )
}

function dnsText(p: PrivateDns): { title: string; sub?: string; host?: string; tone: "ok" | "off" | "warn" } {
  const human = (m: PrivateDns["mode"], host?: string) =>
    m === "hostname" ? host || "свой сервер" : m === "opportunistic" ? "автоматический режим" : "выключен"
  if (p.managed) {
    if (!p.saved || p.saved.mode === "off") return { title: "Выключен на время правил по доменам", tone: "off" }
    return { title: "Выключен на время правил по доменам", sub: "после их отключения вернётся:", host: human(p.saved.mode, p.saved.host), tone: "off" }
  }
  if (p.mode === "hostname") return { title: `Свой сервер · ${p.host ?? ""}`, tone: "ok" }
  if (p.mode === "opportunistic") return { title: "Автоматический режим", tone: "ok" }
  return { title: "Выключен", tone: "off" }
}

function DnsCard() {
  const { pdns } = useStore()
  if (!pdns) return <Skeleton height={86} radius="var(--an-radius-card)" />
  const t = dnsText(pdns)
  return (
    <Card style={col("var(--an-space-4)")}>
      <CardHead title="Частный DNS" />
      <div style={{ ...rowS("var(--an-space-6)"), alignItems: "flex-start" }}>
        <span style={{ color: "var(--an-text-secondary)", paddingTop: 2 }}><Icon name="shield" size={18} /></span>
        <div style={{ ...col("var(--an-space-1)"), flex: 1, minWidth: 0 }}>
          <span style={{ font: "var(--an-text-body)" }}>{t.title}</span>
          {t.sub ? <span style={{ font: "var(--an-text-body-sm)", color: "var(--an-text-secondary)" }}>{t.sub}</span> : null}
          {t.host ? <span style={{ font: "var(--an-text-body-sm)", color: "var(--an-text)", ...ellipsis }}>{t.host}</span> : null}
        </div>
      </div>
    </Card>
  )
}

export function Home() {
  return (
    <>
      <Header
        title={
          <span style={rowS("var(--an-space-4)")}>
            <img src={logo} alt="" width={28} height={28} />
            splify2
          </span>
        }
      />
      <Body>
        <MainCard />
        <NetworkCard />
        <OutputsCard />
        <TrafficCard />
        <DnsCard />
      </Body>
    </>
  )
}

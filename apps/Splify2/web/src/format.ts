/**
 * Числа и подписи — по правилам текста Andromeda: пробел на тысячи, запятая на дробь,
 * единица через пробел, счётчик — «подпись: число», а не фраза.
 */
import type { Model, ModelChannel, ModelOutput, OnFail, OutputKind, OutputStatus } from "./types"

export function fmtInt(n: number): string {
  return Math.round(n).toLocaleString("ru-RU").replace(/ |,/g, " ")
}

function fmtFrac(n: number): string {
  return (n < 10 ? n.toFixed(1) : Math.round(n).toString()).replace(".", ",")
}

export function fmtBytes(b: number | undefined | null): string {
  if (!b || b < 1) return "0 Б"
  const u = ["Б", "КБ", "МБ", "ГБ", "ТБ"]
  let i = 0
  while (b >= 1024 && i < u.length - 1) {
    b /= 1024
    i++
  }
  return `${i === 0 ? Math.round(b) : fmtFrac(b)} ${u[i]}`
}

/** «3 ч назад», «только что». */
export function fmtAgo(ts: number | undefined | null): string {
  if (!ts) return "никогда"
  const d = Date.now() / 1000 - ts
  if (d < 45) return "только что"
  if (d < 3600) return `${Math.max(1, Math.round(d / 60))} мин назад`
  if (d < 86400) return `${Math.round(d / 3600)} ч назад`
  if (d < 7 * 86400) return `${Math.round(d / 86400)} дн назад`
  return fmtDate(ts)
}

export function fmtDate(ts: number): string {
  return new Date(ts * 1000).toLocaleDateString("ru-RU", { day: "2-digit", month: "2-digit", year: "numeric" })
}

export function fmtTime(ts: number): string {
  return new Date(ts * 1000).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit", second: "2-digit" })
}

export function fmtDuration(s: number | undefined): string {
  if (s == null) return ""
  if (s < 60) return `${Math.round(s)} с`
  if (s < 3600) return `${Math.round(s / 60)} мин`
  return `${Math.round(s / 3600)} ч`
}

export const KIND_TEXT: Record<OutputKind, string> = {
  vless: "VLESS",
  interface: "WireGuard",
  xsteer: "xsteer",
  direct: "напрямую",
  zapret: "zapret",
}

export const ON_FAIL_TEXT: Record<OnFail, string> = {
  drop: "остановить трафик",
  direct: "пустить напрямую",
  zapret: "напрямую через zapret",
}

/** «vless-nl · Нидерланды», «Напрямую · без туннеля»: термин никогда не идёт один. */
export function outputLabel(name: string, o?: ModelOutput): string {
  if (o?.kind === "direct") return `Напрямую · ${o.title || "без туннеля"}`
  return o?.title ? `${name} · ${o.title}` : name
}

export type Tone = "ok" | "warn" | "bad" | "off"

/** Состояние выхода одной точкой и словом. `used` — ведёт ли в него хоть одно включённое
 *  правило: неподнятый выход, которым никто не пользуется, — не поломка. */
export function outputState(st: OutputStatus | undefined, engineOn: boolean, used: boolean, haveStatus = true): { tone: Tone; text: string } {
  if (!engineOn) return { tone: "off", text: "выключено" }
  if (!haveStatus) return { tone: "off", text: "нет данных" }
  if (!st) return { tone: "off", text: "не применён" }
  if (st.kind === "direct") return { tone: "ok", text: "работает" }
  if (st.up) return { tone: "ok", text: "работает" }
  if (st.probe?.state === "probing") return { tone: "warn", text: "подключается…" }
  if (st.probe?.state === "no_such_node") return { tone: used ? "bad" : "off", text: "узла нет в подписке" }
  if (st.probe?.state === "failed") return { tone: used ? "bad" : "off", text: "нет рабочих узлов" }
  return { tone: used ? "bad" : "off", text: "нет связи" }
}

export function usedOutputs(model: Model | null): Set<string> {
  const s = new Set<string>()
  for (const c of model?.channels ?? []) if (c.enabled !== false) s.add(c.out)
  return s
}

export const WHO_TEXT = { phone: "Весь телефон", apps: "Приложения", tether: "Раздача" } as const

/** «что» правила одной строкой: «YouTube, Telegram», «весь трафик», «домены: 2». */
export function matchText(c: ModelChannel, listName: (id: string) => string): string {
  const m = c.match || {}
  if (m.any) return "весь трафик"
  const parts: string[] = []
  for (const id of m.lists ?? []) parts.push(listName(id))
  for (const n of m.custom ?? []) parts.push(n)
  if (m.domains?.length) parts.push(`доменов: ${m.domains.length}`)
  if (m.prefixes?.length) parts.push(`подсетей: ${m.prefixes.length}`)
  return parts.length ? parts.join(", ") : "ничего"
}

/** Затрагивает ли правило имена (домены) — тогда телефону нужен свой DNS, и Частный DNS
 *  выключается, пока правило включено. Списки подсетей имён не затрагивают. */
export function touchesDomains(c: ModelChannel, listKind: (id: string) => "domains" | "prefixes" | undefined): boolean {
  if (c.who === "tether") return false
  const m = c.match || {}
  if (m.any) return false
  if (m.domains?.length || m.custom?.length) return true
  return (m.lists ?? []).some((id) => listKind(id) !== "prefixes")
}

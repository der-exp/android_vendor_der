/**
 * Числа и подписи — по правилам текста Andromeda: пробел на тысячи, запятая на дробь,
 * единица через пробел, счётчик — «подпись: число», а не фраза.
 */
import type { ListKind, Model, ModelChannel, ModelOutput, OnFail, OutputKind, OutputStatus, WhoKind } from "./types"

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
  interface: "Туннель",
  direct: "напрямую",
  tgws: "Мост Telegram",
  awg: "WireGuard",
}

/** «AmneziaWG» или «WireGuard» — по тому, включена ли в файле обфускация. */
export function awgKind(o: ModelOutput): string {
  return o.info?.obfs ? "AmneziaWG" : "WireGuard"
}

/** Вид выхода для подписи: у awg — по файлу. */
export function kindText(o: ModelOutput): string {
  return o.kind === "awg" ? awgKind(o) : KIND_TEXT[o.kind]
}

/** Сколько секунд назад — коротко: «2 мин назад»; null — «нет». */
export function fmtAgoSec(s: number | null | undefined): string {
  if (s == null) return "нет"
  if (s < 45) return "только что"
  if (s < 3600) return `${Math.max(1, Math.round(s / 60))} мин назад`
  if (s < 86400) return `${Math.round(s / 3600)} ч назад`
  return `${Math.round(s / 86400)} дн назад`
}

/** Предел цепочки «через выход» — как у логики и движка: три перехода. */
export const VIA_MAX_HOPS = 3

/** Может ли выход идти через другой (vless, awg) и служить целью (interface, vless, awg). */
export const viaCapable = (o: ModelOutput) => o.kind === "vless" || o.kind === "awg"
const viaTarget = (o: ModelOutput) => o.kind === "interface" || o.kind === "vless" || o.kind === "awg"

/** Выходы, через которые можно пустить `name`, не замкнув круг и не превысив глубину.
 *  Окончательно решает логика (Model.kt, checkVia); здесь — чтобы в списке не было того, что
 *  она отвергнет. */
export function viaTargets(model: Model | null, name: string): ModelOutput[] {
  if (!model) return []
  const by = new Map(model.outputs.map((o) => [o.name, o]))
  const next = (n: string) => by.get(n)?.via || undefined
  // Глубина вниз от цели: сколько переходов уже у неё.
  const down = (n: string): number => {
    let k = 0
    for (let c = next(n), seen = new Set([n]); c && !seen.has(c); c = next(c)) {
      seen.add(c)
      k++
    }
    return k
  }
  // Глубина вверх: самая длинная цепочка выходов, которые идут через `name`.
  const up = (n: string, seen = new Set<string>()): number => {
    let best = 0
    for (const o of model.outputs)
      if (o.via === n && !seen.has(o.name)) best = Math.max(best, 1 + up(o.name, new Set([...seen, n])))
    return best
  }
  const reaches = (from: string, to: string) => {
    for (let c: string | undefined = from, seen = new Set<string>(); c && !seen.has(c); c = next(c)) {
      if (c === to) return true
      seen.add(c)
    }
    return false
  }
  const above = up(name)
  return model.outputs.filter((t) => t.name !== name && viaTarget(t) && !reaches(t.name, name) && above + 1 + down(t.name) <= VIA_MAX_HOPS)
}

export const ON_FAIL_TEXT: Record<OnFail, string> = {
  drop: "остановить трафик",
  direct: "пустить напрямую",
}

/** Выход по имени; "direct" есть всегда, хоть его и нет в модели (Model.kt). */
export function findOutput(model: Model | null, name: string): ModelOutput | undefined {
  if (name === "direct") return { name: "direct", kind: "direct" }
  return model?.outputs.find((o) => o.name === name)
}

/** «vless-nl», «Напрямую»: имя выхода, как его видит человек. */
export function outputLabel(name: string): string {
  return name === "direct" ? "Напрямую" : name
}

/** Вторая строка выхода: вид и то, откуда он берёт путь. «Через выход» здесь нет: в карточке
 *  выхода он — своим выбором ниже, а на главной — в своей строке. */
export function outputSub(o: ModelOutput, subName?: (id: string) => string | undefined): string {
  switch (o.kind) {
    case "vless": {
      const s = o.sub ? subName?.(o.sub) : undefined
      return s ? `VLESS · ${s}` : "VLESS"
    }
    case "interface":
      return o.devices?.length ? `Туннель · ${o.devices.join(", ")}` : "Туннель"
    case "tgws":
      return o.domain ? `Мост Telegram · ${o.domain}` : "Мост Telegram"
    case "awg": {
      return o.info?.endpoint ? `${awgKind(o)} · ${o.info.endpoint}` : awgKind(o)
    }
    default:
      return "без туннеля"
  }
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

export const WHO_TEXT: Record<WhoKind, string> = { phone: "Весь телефон", apps: "Приложения", tether: "Раздача" }

/** «что» правила одной строкой: «YouTube, Telegram», «весь трафик». */
export function matchText(c: ModelChannel, listName: (id: string) => string): string {
  const w = c.what
  if (w.all) return "весь трафик"
  const parts = [...w.lists.map(listName), ...w.custom]
  return parts.length ? parts.join(", ") : "ничего"
}

/** Затрагивает ли правило имена (домены) — тогда телефону нужен свой DNS, и Частный DNS
 *  выключается, пока правило включено. Раздача и списки из одних подсетей имён телефона не
 *  затрагивают. Окончательно это решает логика при сборке (needs_local_dns), здесь — только
 *  подсказка в редакторе, поэтому неизвестный вид списка считается доменным. */
export function touchesDomains(
  c: ModelChannel,
  listKinds: (id: string) => ListKind[] | undefined,
  customHasDomains: (name: string) => boolean,
): boolean {
  if (c.who.kind === "tether" || c.what.all) return false
  if (c.what.custom.some(customHasDomains)) return true
  return c.what.lists.some((id) => listKinds(id)?.includes("domains") ?? true)
}

/** Байты счётчика, пришедшие строкой (квота подписки): число или 0. */
export function bytesOf(s: string | undefined): number {
  const n = Number(s)
  return isFinite(n) && n > 0 ? n : 0
}

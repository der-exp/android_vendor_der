/**
 * Разбор ответов `engine.conns` и `engine.dnsLog` — форм `steer conns` и `steer dns-log`
 * (steer/docs/ctl.md). Разбор всё равно терпимый: движок и экран обновляются порознь, и
 * запись без адреса назначения (или имя без имени) лучше пропустить, чем уронить экран. Поля
 * сверх описанных — пропускаем, числа строкой — принимаем.
 */
import type { Conn, DnsName } from "./types"

type Obj = Record<string, unknown>

const objs = (v: unknown): Obj[] => (Array.isArray(v) ? v.filter((x): x is Obj => !!x && typeof x === "object") : [])

const str = (o: Obj, k: string): string | undefined => (typeof o[k] === "string" && o[k] ? (o[k] as string) : undefined)

const num = (o: Obj, k: string): number | undefined => {
  const v = o[k]
  if (typeof v === "number" && isFinite(v)) return v
  if (typeof v === "string" && v.trim() && isFinite(Number(v))) return Number(v)
  return undefined
}

export interface ConnsView {
  rows: Conn[]
  total: number
  truncated: boolean
}

export function parseConns(v: unknown): ConnsView {
  const o = (v && typeof v === "object" ? v : {}) as Obj
  const rows = objs(o.conns)
    .map((c): Conn | null => {
      const dst = str(c, "dst")
      if (!dst) return null
      return {
        family: c.family === "ipv6" ? "ipv6" : "ipv4",
        proto: str(c, "proto") ?? "",
        src: str(c, "src") ?? "",
        sport: num(c, "sport"),
        dst,
        dport: num(c, "dport"),
        out: str(c, "out") ?? null,
        state: str(c, "state"),
        bytes: num(c, "bytes"),
        reply_bytes: num(c, "reply_bytes"),
      }
    })
    .filter((c): c is Conn => c !== null)
  return { rows, total: num(o, "total") ?? rows.length, truncated: o.truncated === true }
}

export interface DnsView {
  running: boolean
  rows: DnsName[]
}

export function parseDnsLog(v: unknown): DnsView {
  const o = (v && typeof v === "object" ? v : {}) as Obj
  const rows = objs(o.names)
    .map((e): DnsName | null => {
      const name = str(e, "name")
      if (!name) return null
      return {
        name: name.replace(/\.$/, ""),
        channel: str(e, "channel") ?? null,
        out: str(e, "out") ?? null,
        count: num(e, "count") ?? 1,
        last: num(e, "last") ?? 0,
        ago: num(e, "ago") ?? 0,
      }
    })
    .filter((e): e is DnsName => e !== null)
  return { running: o.running !== false, rows }
}

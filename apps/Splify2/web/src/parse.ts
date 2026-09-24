/**
 * Разбор ответов `engine.conns` и `engine.dnsLog`. Формы в договоре моста ещё нет — команды
 * `conns` и `dns-log` у сокета появятся позже, — поэтому разбор терпимый: принимает и
 * массив, и объект с массивом под `conns`/`entries`/`log`, и строки со сдвинутыми именами
 * полей (`daddr`/`dst`, `dport`/`port`, `qname`/`name`). Запись без адреса назначения (или
 * без имени у журнала) отбрасывается: показать её нечем.
 */
import type { Conn, DnsEntry } from "./types"

type Obj = Record<string, unknown>

function arrayOf(v: unknown, keys: string[]): Obj[] {
  if (Array.isArray(v)) return v.filter((x): x is Obj => !!x && typeof x === "object")
  if (v && typeof v === "object") {
    for (const k of keys) {
      const a = (v as Obj)[k]
      if (Array.isArray(a)) return arrayOf(a, [])
    }
  }
  return []
}

const str = (o: Obj, ...keys: string[]): string | undefined => {
  for (const k of keys) {
    const v = o[k]
    if (typeof v === "string" && v) return v
  }
  return undefined
}

const num = (o: Obj, ...keys: string[]): number | undefined => {
  for (const k of keys) {
    const v = o[k]
    if (typeof v === "number" && isFinite(v)) return v
    if (typeof v === "string" && v.trim() && isFinite(Number(v))) return Number(v)
  }
  return undefined
}

export function parseConns(v: unknown): Conn[] {
  return arrayOf(v, ["conns", "connections", "entries"])
    .map((o): Conn | null => {
      const dst = str(o, "dst", "daddr", "dest")
      if (!dst) return null
      return {
        proto: str(o, "proto", "l4proto") ?? "",
        dst,
        dport: num(o, "dport", "port"),
        src: str(o, "src", "saddr"),
        sport: num(o, "sport"),
        host: str(o, "host", "name", "qname"),
        uid: num(o, "uid"),
        channel: str(o, "channel", "rule"),
        out: str(o, "out", "output"),
        bytes: num(o, "bytes"),
        age: num(o, "age"),
      }
    })
    .filter((c): c is Conn => c !== null)
}

export function parseDnsLog(v: unknown): DnsEntry[] {
  return arrayOf(v, ["entries", "log", "names"])
    .map((o): DnsEntry | null => {
      const name = str(o, "name", "qname", "host")
      if (!name) return null
      const answers = Array.isArray(o.answers) ? o.answers.filter((a): a is string => typeof a === "string") : undefined
      return {
        at: num(o, "at", "ts", "time") ?? 0,
        name: name.replace(/\.$/, ""),
        qtype: str(o, "qtype", "type"),
        uid: num(o, "uid"),
        channel: str(o, "channel", "rule"),
        out: str(o, "out", "output"),
        answers,
      }
    })
    .filter((e): e is DnsEntry => e !== null)
    .sort((a, b) => b.at - a.at)
}

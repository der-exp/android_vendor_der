/**
 * Заглушка оболочки: отвечает на все методы BRIDGE.md правдоподобными данными, когда
 * страница открыта без приложения (`npm run dev`, просмотр сборки в браузере).
 *
 * Отвечает тем же путём, что настоящая оболочка, — через `__splifyReply` с задержкой, —
 * чтобы экраны проходили через ожидание так же, как на телефоне. Форма `engine.status`
 * повторяет `steer status` (как у splify2 на роутере); `engine.conns` и `engine.dnsLog`
 * отвечают `unknown-method`, как ответит оболочка, пока у сокета нет этих команд.
 *
 * Переключатели в адресе страницы — для проверки состояний, которых на исправной
 * заглушке не бывает:
 *   ?conns=1        — соединения и журнал имён с данными (будущая форма ответа)
 *   ?apply=fail     — spec.apply не применяется и откатывается
 *   ?engine=down    — сокет движка не отвечает
 */
import type {
  AppInfo,
  BridgeReply,
  Catalog,
  CatalogEntry,
  CustomList,
  EngineState,
  Model,
  NetworkInfo,
  PrivateDns,
  Status,
  Sub,
  VlessNode,
} from "./types"

const q = new URLSearchParams(location.search)
const now = () => Math.floor(Date.now() / 1000)
const GB = 1024 ** 3
const MB = 1024 ** 2

let engine: EngineState = { enabled: true, reachable: q.get("engine") !== "down", version: "1.4.0" }

const network: NetworkInfo = { type: "wifi", name: "Дом 5G", metered: false }

let subs: Sub[] = [
  {
    id: "s1",
    name: "Мой VPN",
    url: "https://sub.example.net/api/sub/7f3a9c1e2b",
    nodes: 5,
    updated: now() - 3 * 3600,
    quota: { up: 1.2 * GB, down: 18.4 * GB, total: 100 * GB, expire: now() + 19 * 86400 },
  },
]

const nodes: VlessNode[] = [
  { index: 0, name: "NL · Амстердам", host: "nl1.example.net", port: 443, type: "tcp", security: "reality", vision: true },
  { index: 1, name: "DE · Франкфурт", host: "de1.example.net", port: 443, type: "tcp", security: "reality", vision: true },
  { index: 2, name: "NL · Амстердам 2", host: "nl2.example.net", port: 443, type: "xhttp", security: "reality", vision: false, mode: "auto" },
  { index: 3, name: "FI · Хельсинки", host: "fi1.example.net", port: 8443, type: "tcp", security: "reality", vision: true },
  { index: 4, name: "US · Нью-Йорк", host: "us1.example.net", port: 443, type: "grpc", security: "tls", vision: false },
]

let model: Model = {
  outputs: {
    "vless-nl": { name: "vless-nl", kind: "vless", title: "Нидерланды", sub: "s1", nodes: [2, 0], on_fail: "drop" },
    "wg-home": { name: "wg-home", kind: "interface", title: "свой WireGuard", devices: ["wg0"], on_fail: "direct" },
    direct: { name: "direct", kind: "direct", title: "без туннеля" },
  },
  channels: [
    { name: "Банки напрямую", enabled: true, who: "apps", uids: [10123, 10145], match: { any: true }, out: "direct" },
    { name: "YouTube", enabled: true, who: "phone", match: { lists: ["youtube"] }, out: "vless-nl" },
    { name: "Telegram", enabled: true, who: "phone", match: { lists: ["telegram", "telegram-ip"] }, out: "vless-nl" },
    { name: "Discord", enabled: true, who: "phone", match: { lists: ["discord"] }, out: "vless-nl" },
    { name: "Работа", enabled: true, who: "apps", uids: [10201], match: { custom: ["работа"] }, out: "vless-nl" },
    { name: "Раздача через VPN", enabled: false, who: "tether", match: { any: true }, out: "vless-nl" },
  ],
}

const privateDns: PrivateDns = { mode: "off", managed: true, saved: { mode: "hostname", host: "dns.adguard-dns.com" } }

const catalog: CatalogEntry[] = [
  { id: "youtube", name: "YouTube", category: "Видео", kind: "domains", count: 1284, description: "YouTube и его сети доставки", selected: true },
  { id: "twitch", name: "Twitch", category: "Видео", kind: "domains", count: 96, selected: false },
  { id: "telegram", name: "Telegram", category: "Мессенджеры", kind: "domains", count: 312, selected: true },
  { id: "telegram-ip", name: "Telegram · адреса", category: "Мессенджеры", kind: "prefixes", count: 18, selected: true },
  { id: "whatsapp", name: "WhatsApp", category: "Мессенджеры", kind: "domains", count: 74, selected: false },
  { id: "discord", name: "Discord", category: "Мессенджеры", kind: "domains", count: 420, description: "голос и текст", selected: true },
  { id: "instagram", name: "Instagram", category: "Соцсети", kind: "domains", count: 210, selected: false },
  { id: "facebook", name: "Facebook", category: "Соцсети", kind: "domains", count: 388, selected: false },
  { id: "x", name: "X", category: "Соцсети", kind: "domains", count: 142, selected: false },
  { id: "chatgpt", name: "ChatGPT", category: "Нейросети", kind: "domains", count: 58, selected: false },
  { id: "gemini", name: "Gemini", category: "Нейросети", kind: "domains", count: 33, selected: false },
  { id: "ru-blocked", name: "Заблокированные в России", category: "Общие", kind: "domains", count: 84312, description: "сводный список недоступных сайтов", selected: false },
].map((e) => ({ ...e, kind: e.kind as "domains" | "prefixes", updated: now() - 5 * 3600 }))

let custom: CustomList[] = [
  { name: "работа", domains: ["gitlab.work.example", "jira.work.example"], prefixes: ["10.20.0.0/16"] },
]

const apps: AppInfo[] = [
  { uid: 10201, pkg: "com.android.chrome", label: "Chrome", system: false, shared: [] },
  { uid: 10134, pkg: "com.google.android.youtube", label: "YouTube", system: false, shared: [] },
  { uid: 10156, pkg: "org.telegram.messenger", label: "Telegram", system: false, shared: [] },
  { uid: 10123, pkg: "ru.sberbankmobile", label: "СберБанк", system: false, shared: [] },
  { uid: 10145, pkg: "com.idamob.tinkoff.android", label: "Т-Банк", system: false, shared: [] },
  { uid: 10178, pkg: "com.discord", label: "Discord", system: false, shared: [] },
  { uid: 10190, pkg: "ru.rostel", label: "Госуслуги", system: false, shared: [] },
  { uid: 10165, pkg: "ru.yandex.yandexmaps", label: "Яндекс Карты", system: false, shared: [] },
  { uid: 10170, pkg: "com.whatsapp", label: "WhatsApp", system: false, shared: [] },
  { uid: 10210, pkg: "org.mozilla.firefox", label: "Firefox", system: false, shared: [] },
  { uid: 10110, pkg: "com.google.android.gms", label: "Сервисы Google Play", system: true, shared: ["com.google.android.gms", "com.google.android.gsf"] },
  { uid: 10060, pkg: "com.android.vending", label: "Google Play Маркет", system: true, shared: [] },
]

function status(): Status {
  const outs: Status["outputs"] = {}
  for (const o of Object.values(model.outputs)) {
    outs[o.name] = {
      name: o.name,
      kind: o.kind,
      up: o.kind !== "interface",
      device: o.kind === "vless" ? "tun-vless0" : o.devices?.[0],
      devices: o.devices,
      on_fail: o.on_fail,
      nodes: o.kind === "vless" ? o.nodes ?? [] : undefined,
    }
  }
  const bytes: Record<string, [number, number]> = {
    YouTube: [118 * MB, 3.4 * GB],
    Telegram: [64 * MB, 212 * MB],
    Discord: [9 * MB, 46 * MB],
    "Банки напрямую": [12 * MB, 81 * MB],
    Работа: [4 * MB, 19 * MB],
  }
  return {
    schema: 1,
    features: ["pool", "status_cache"],
    outputs: outs,
    channels: model.channels
      .filter((c) => c.enabled !== false)
      .map((c) => ({
        name: c.name,
        out: c.out,
        live: engine.enabled,
        bytes: bytes[c.name]?.[0] ?? 0,
        down_bytes: bytes[c.name]?.[1] ?? 0,
      })),
    warnings: [],
    at: now(),
  }
}

function catalogReply(): Catalog {
  return { lists: catalog, updated: now() - 5 * 3600 }
}

type Emit = (name: string, payload: unknown) => void

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms))

class Fail extends Error {
  readonly code: string
  constructor(code: string, msg?: string) {
    super(msg)
    this.code = code
  }
}

function needEngine() {
  if (!engine.reachable) throw new Fail("engine-down", "Движок не отвечает")
}

async function run(method: string, args: Record<string, any>, emit: Emit): Promise<unknown> {
  switch (method) {
    case "engine.state":
      return engine
    case "engine.setEnabled":
      needEngine()
      await delay(500)
      engine = { ...engine, enabled: !!args.on }
      setTimeout(() => emit("engine.changed", engine), 50)
      return engine
    case "engine.status":
      needEngine()
      return status()
    case "engine.diag":
      needEngine()
      await delay(600)
      return {
        checks: [
          { id: "outputs", verdict: "ok", what: "выходы: 2 из 3 работают", why: "" },
          { id: "lists-age", verdict: "warn", what: "список discord старше суток", why: "не скачивался 31 ч" },
          { id: "dns", verdict: "ok", what: "резолвер отвечает", why: "" },
        ],
        warn: 1,
        fail: 0,
      }
    case "engine.explain": {
      needEngine()
      await delay(300)
      const q = String(args.q || "").trim()
      if (!q) throw new Fail("bad-args", "Нужен адрес или имя")
      const yt = /youtube|googlevideo|ytimg/.test(q)
      return {
        text: yt
          ? `${q}\n  правило: YouTube (список youtube)\n  выход:   vless-nl · tun-vless0 · узел 3 NL · Амстердам 2`
          : `${q}\n  ни одно правило не совпало\n  выход:   напрямую`,
      }
    }
    case "engine.vlessNodes":
      needEngine()
      await delay(250)
      return {
        output: args.out,
        sub_file: "/data/misc/steer/lists/sub-s1.txt",
        node: -1,
        chosen: model.outputs[args.out]?.nodes ?? [],
        usable: nodes.length,
        skipped: 2,
        foreign: 0,
        nodes,
        skipped_reasons: [{ reason: "shadowsocks не поддерживается", count: 2, example: "SS · Стамбул" }],
      }
    case "engine.vlessProbe": {
      needEngine()
      await delay(1600)
      const ms = [182, 96, 141, 268, 0]
      return {
        output: args.out,
        working: 4,
        results: nodes.map((n, i) => ({
          index: n.index,
          name: n.name,
          type: n.type,
          ok: ms[i] > 0,
          handshake_ms: ms[i] ? ms[i] * 2 + 40 : 0,
          ttfb_ms: ms[i],
          why: ms[i] ? "" : "нет ответа за 8 с",
        })),
      }
    }
    case "engine.conns":
      if (q.get("conns") !== "1") throw new Fail("unknown-method")
      return {
        conns: [
          { proto: "tcp", dst: "142.250.74.110", dport: 443, host: "rr3.googlevideo.com", uid: 10134, channel: "YouTube", out: "vless-nl", bytes: 48 * MB, age: 94 },
          { proto: "udp", dst: "149.154.167.51", dport: 443, uid: 10156, channel: "Telegram", out: "vless-nl", bytes: 2.1 * MB, age: 610 },
          { proto: "tcp", dst: "194.54.14.131", dport: 443, host: "online.sberbank.ru", uid: 10123, channel: "Банки напрямую", out: "direct", bytes: 310 * 1024, age: 12 },
          { proto: "tcp", dst: "87.250.250.242", dport: 443, host: "yandex.ru", uid: 10201, bytes: 96 * 1024, age: 33 },
        ],
      }
    case "engine.dnsLog":
      if (q.get("conns") !== "1") throw new Fail("unknown-method")
      return [
        { at: now() - 4, name: "rr3.googlevideo.com", qtype: "A", uid: 10134, channel: "YouTube", out: "vless-nl", answers: ["198.18.0.41"] },
        { at: now() - 9, name: "yandex.ru", qtype: "A", uid: 10201, answers: ["87.250.250.242"] },
        { at: now() - 15, name: "discord.com", qtype: "AAAA", uid: 10178, channel: "Discord", out: "vless-nl", answers: [] },
        { at: now() - 40, name: "online.sberbank.ru", qtype: "A", uid: 10123, channel: "Банки напрямую", out: "direct", answers: ["194.54.14.131"] },
      ]
    case "system.network":
      return network
    case "system.privateDns":
      return privateDns
    case "apps.list":
      await delay(350)
      return args.system ? apps : apps.filter((a) => !a.system)
    case "settings.get":
      return structuredClone(model)
    case "settings.put":
      if (!args || !Array.isArray(args.channels)) throw new Fail("bad-args", "Модель без правил")
      model = structuredClone(args as Model)
      return { saved: true }
    case "spec.preview":
      return { spec: { schema: 1 }, check: { code: 0, stderr: "" } }
    case "spec.apply":
      needEngine()
      await delay(900)
      if (q.get("apply") === "fail")
        return { applied: false, rolled_back: true, message: "канал «Работа»: список работа пуст" }
      setTimeout(() => emit("engine.changed", engine), 50)
      return { applied: true }
    case "lists.catalog":
      await delay(200)
      return catalogReply()
    case "lists.select": {
      const e = catalog.find((c) => c.id === args.id)
      if (!e) throw new Fail("bad-args", "Нет такого списка")
      e.selected = !!args.on
      return { saved: true }
    }
    case "lists.update":
      setTimeout(() => {
        for (const e of catalog) e.updated = now()
        emit("lists.updated", { ok: true, changed: 3 })
      }, 2200)
      return { started: true }
    case "lists.custom":
      if (args.put) {
        const put = args.put as CustomList
        custom = [...custom.filter((c) => c.name !== put.name), put]
        return { saved: true }
      }
      if (args.remove) {
        custom = custom.filter((c) => c.name !== args.remove)
        return { saved: true }
      }
      return custom
    case "subs.list":
      return subs
    case "subs.add": {
      await delay(1200)
      const url = String(args.url || "")
      if (!/^(https?|vless):\/\//.test(url)) throw new Fail("bad-args", "Нужна ссылка https://… или vless://")
      subs = [...subs, { id: "s" + (subs.length + 1), name: new URL(url.replace(/^vless:/, "http:")).hostname, url, nodes: 3, updated: now() }]
      return subs
    }
    case "subs.remove":
      subs = subs.filter((s) => s.id !== args.id)
      return subs
    case "subs.refresh":
      await delay(1400)
      subs = subs.map((s) => (!args.id || s.id === args.id ? { ...s, updated: now() } : s))
      setTimeout(() => emit("subs.updated", subs), 50)
      return subs
    case "backup.export":
      await delay(300)
      return { file: "splify2-2026-09-24.json" }
    case "backup.import": {
      let parsed: unknown
      try {
        parsed = JSON.parse(String(args.json || ""))
      } catch {
        throw new Fail("bad-args", "Файл не похож на резервную копию splify2")
      }
      if (!parsed || typeof parsed !== "object" || !Array.isArray((parsed as Model).channels))
        throw new Fail("bad-args", "Файл не похож на резервную копию splify2")
      model = parsed as Model
      return { saved: true }
    }
  }
  throw new Fail("unknown-method")
}

export async function handle(
  id: string,
  method: string,
  argsJson: string,
  reply: (id: string, r: BridgeReply) => void,
  emit: Emit,
) {
  await delay(120 + Math.random() * 180)
  let r: BridgeReply
  try {
    r = { ok: true, result: await run(method, JSON.parse(argsJson || "{}"), emit) }
  } catch (e) {
    r = e instanceof Fail ? { ok: false, error: e.code, message: e.message || undefined } : { ok: false, error: "internal", message: String(e) }
  }
  reply(id, JSON.parse(JSON.stringify(r)))
}

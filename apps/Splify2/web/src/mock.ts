/**
 * Заглушка оболочки: отвечает на все методы BRIDGE.md правдоподобными данными, когда
 * страница открыта без приложения (`npm run dev`, просмотр сборки в браузере, снимки
 * scripts/shots.mjs).
 *
 * Формы — те, что отдают настоящие части: модель и методы логики — как logic/Dispatcher.kt
 * (settings.put принимает часть модели, подписки меняются только через subs.*), движок — как
 * его команды (conns, dns-log, vless-probe — steer/docs/ctl.md). Отвечает тем же путём, что
 * оболочка, — через `__splifyReply` с задержкой, — чтобы экраны проходили через ожидание так
 * же, как на телефоне.
 *
 * Переключатели в адресе страницы — для состояний, которых на исправной заглушке не бывает:
 *   ?conns=0        — движок без команд conns и dns-log (unknown-method)
 *   ?apply=fail     — spec.apply: движок отверг новые правила, остались прежние
 *   ?engine=down    — сокет движка не отвечает
 */
import type {
  AppInfo,
  BridgeReply,
  Catalog,
  CatalogItem,
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
    kind: "url",
    url: "https://sub.example.net/api/sub/7f3a9c1e2b",
    nodes: 5,
    updated: now() - 3 * 3600,
    skipped: 2,
    foreign: 0,
    used_by: ["vless-nl"],
    hwid: "3f9a0c1e2b7d4a5c6e8f",
    quota: { up: String(1.2 * GB), down: String(18.4 * GB), total: String(100 * GB), expire: now() + 19 * 86400 },
  },
  {
    id: "s2",
    name: "Свои ссылки",
    kind: "links",
    nodes: 1,
    updated: now() - 9 * 86400,
    skipped: 0,
    foreign: 0,
    used_by: [],
    hwid: "3f9a0c1e2b7d4a5c6e8f",
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
  version: 1,
  outputs: [
    { name: "vless-nl", kind: "vless", sub: "s1", nodes: [2, 0], on_fail: "drop" },
    { name: "wg-home", kind: "interface", devices: ["wg0"], on_fail: "direct" },
  ],
  channels: [
    { name: "Банки напрямую", enabled: true, who: { kind: "apps", uids: [10123, 10145] }, what: { lists: [], custom: [], all: true }, out: "direct" },
    { name: "YouTube", enabled: true, who: { kind: "phone" }, what: { lists: ["itdoginfo:youtube"], custom: [], all: false }, out: "vless-nl" },
    { name: "Telegram", enabled: true, who: { kind: "phone" }, what: { lists: ["itdoginfo:telegram"], custom: [], all: false }, out: "vless-nl" },
    { name: "Discord", enabled: true, who: { kind: "phone" }, what: { lists: ["itdoginfo:discord"], custom: [], all: false }, out: "vless-nl" },
    { name: "Работа", enabled: true, who: { kind: "apps", uids: [10201] }, what: { lists: [], custom: ["work"], all: false }, out: "wg-home" },
    { name: "Раздача через VPN", enabled: false, who: { kind: "tether", from: [] }, what: { lists: [], custom: [], all: true }, out: "vless-nl" },
  ],
  lists: ["itdoginfo:youtube", "itdoginfo:telegram", "itdoginfo:discord"],
  custom: [{ name: "work", domains: ["gitlab.work.example", "jira.work.example"], prefixes: ["10.20.0.0/16"] }],
  subs: [
    { id: "s1", name: "Мой VPN", kind: "url", url: "https://sub.example.net/api/sub/7f3a9c1e2b" },
    { id: "s2", name: "Свои ссылки", kind: "links" },
  ],
  tether: { devices: ["rndis0", "ncm0", "softap0", "ap0", "swlan0", "bt-pan"] },
  catalog_url: null,
  update: { unmetered_only: true },
}

const privateDns: PrivateDns = { mode: "off", managed: true, saved: { mode: "hostname", host: "dns.adguard-dns.com" } }

const IT = "itdoginfo (allow-domains)"
const GEO = "b4geoip (игры и сервисы)"
const catalog: CatalogItem[] = (
  [
    ["itdoginfo:youtube", "YouTube", IT, ["domains"], 1284],
    ["itdoginfo:telegram", "Telegram", IT, ["prefixes", "domains"], 330],
    ["itdoginfo:discord", "Discord", IT, ["prefixes", "domains"], 420],
    ["itdoginfo:meta", "Meta", IT, ["prefixes", "domains"], 598],
    ["itdoginfo:twitter", "X (Twitter)", IT, ["prefixes", "domains"], 142],
    ["itdoginfo:cloudflare", "Cloudflare", IT, ["prefixes"], undefined],
    ["itdoginfo:anime", "Аниме", IT, ["domains"], undefined],
    ["b4geoip:steam", "Steam", GEO, ["prefixes", "domains"], 1210],
    ["b4geoip:riot", "Riot Games", GEO, ["prefixes", "domains"], 86],
    ["mydyson", "MyDyson", undefined, ["domains"], 1],
  ] as [string, string, string | undefined, ("domains" | "prefixes")[], number | undefined][]
).map(([id, name, source, kinds, count]) => {
  const selected = model.lists.includes(id)
  return {
    id,
    name,
    source,
    kinds,
    count,
    default_on: false,
    selected,
    used: model.channels.some((c) => c.what.lists.includes(id)),
    ...(selected ? { downloaded: { count: count ?? 212, updated: now() - 5 * 3600 } } : {}),
    ...(id === "mydyson" ? { description: "Приложение MyDyson и сайт Dyson — из РФ не открываются" } : {}),
    ...(id === "itdoginfo:discord" ? { narrow: { proto: "udp", ports: ["50000-65535"] } } : {}),
  }
})

let lastUpdate: Catalog["last_update"] = { ok: true, changed: 0, at: now() - 5 * 3600 }

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
  const outs: Status["outputs"] = { direct: { name: "direct", kind: "direct", up: true } }
  for (const o of model.outputs) {
    outs[o.name] = {
      name: o.name,
      kind: o.kind,
      up: true,
      device: o.kind === "vless" ? "tun-vless-nl" : o.devices?.[0],
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
  for (const e of catalog) {
    e.selected = model.lists.includes(e.id)
    e.used = model.channels.some((c) => c.what.lists.includes(e.id))
  }
  return { version: "2026-09-23_08-32", updated: now() - 5 * 3600, last_update: lastUpdate, items: structuredClone(catalog) }
}

function customReply(): CustomList[] {
  return model.custom.map((c) => ({ ...c, used: model.channels.some((ch) => ch.what.custom.includes(c.name)) }))
}

function subsReply(): Sub[] {
  return subs.map((s) => ({ ...s, used_by: model.outputs.filter((o) => o.sub === s.id).map((o) => o.name) }))
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
  if (!engine.reachable) throw new Fail("engine-down", "Движок не отвечает — включите его или перезагрузите телефон")
}

const PREFIX = /^(\d{1,3}(\.\d{1,3}){3}(\/\d{1,2})?|[0-9a-f:]+:[0-9a-f:]*(\/\d{1,3})?)$/i
const DOMAIN = /^(\*\.)?([a-z0-9-]+\.)+[a-z0-9-]{2,}$/i

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
          { id: "outputs", verdict: "ok", what: "выходы: 2 из 2 работают", why: "" },
          { id: "lists-age", verdict: "warn", what: "список Discord старше суток", why: "не скачивался 31 ч" },
          { id: "dns", verdict: "ok", what: "резолвер отвечает", why: "" },
        ],
        warn: 1,
        fail: 0,
      }
    case "engine.explain": {
      needEngine()
      await delay(300)
      const s = String(args.q || "").trim()
      if (!s) throw new Fail("bad-args", "Нужен адрес или имя")
      const yt = /youtube|googlevideo|ytimg/.test(s)
      return {
        text: yt
          ? `${s}\n  правило: YouTube (список youtube)\n  выход:   vless-nl · tun-vless-nl · узел 3 NL · Амстердам 2`
          : `${s}\n  ни одно правило не совпало\n  выход:   напрямую`,
      }
    }
    case "engine.vlessNodes":
      needEngine()
      await delay(250)
      return {
        output: args.out,
        sub_file: "/data/misc/steer/lists/sub-s1-0a1b2c3d.txt",
        node: -1,
        chosen: model.outputs.find((o) => o.name === args.out)?.nodes ?? [],
        usable: nodes.length,
        skipped: 2,
        foreign: 0,
        nodes,
        skipped_reasons: [{ reason: "tls по адресу без sni: нечем сверить", count: 2, example: "SS · Стамбул" }],
      }
    case "engine.vlessProbe": {
      needEngine()
      await delay(1600)
      const ms = [182, 96, 141, 268, 0]
      return {
        output: args.out,
        sub_file: "/data/misc/steer/lists/sub-s1-0a1b2c3d.txt",
        results: nodes.map((n, i) => ({
          index: n.index,
          name: n.name,
          type: n.type,
          ok: ms[i] > 0,
          handshake_ms: ms[i] ? ms[i] * 2 + 40 : 0,
          ttfb_ms: ms[i],
          why: ms[i] ? "" : "нет ответа за 8 с",
        })),
        working: 4,
      }
    }
    case "engine.conns":
      needEngine()
      if (q.get("conns") === "0") throw new Fail("unknown-method", "Недоступно в этой версии системы")
      return {
        schema: 1,
        conns: [
          { family: "ipv4", proto: "tcp", src: "10.0.0.5", sport: 40312, dst: "142.250.74.110", dport: 443, mark: "0x00400000", out: "vless-nl", state: "established", bytes: 310 * 1024, reply_bytes: 48 * MB },
          { family: "ipv4", proto: "udp", src: "10.0.0.5", sport: 51000, dst: "149.154.167.51", dport: 443, mark: "0x00400000", out: "vless-nl", bytes: 96 * 1024, reply_bytes: 2.1 * MB },
          { family: "ipv6", proto: "tcp", src: "2a00:1450::5", sport: 44120, dst: "2a00:1450:4010:c05::64", dport: 443, mark: "0x00800000", out: "wg-home", state: "established" },
          { family: "ipv4", proto: "tcp", src: "10.0.0.5", sport: 40990, dst: "162.159.135.232", dport: 443, mark: "0x00c00000", out: null, state: "time_wait" },
        ],
        shown: 4,
        total: 4,
        truncated: false,
      }
    case "engine.dnsLog":
      needEngine()
      if (q.get("conns") === "0") throw new Fail("unknown-method", "Недоступно в этой версии системы")
      return {
        schema: 1,
        running: engine.enabled,
        size: 256,
        names: engine.enabled
          ? [
              { name: "rr3.googlevideo.com", channel: "YouTube", out: "vless-nl", count: 14, last: now() - 3, ago: 3 },
              { name: "yandex.ru", channel: null, out: null, count: 6, last: now() - 9, ago: 9 },
              { name: "discord.com", channel: "Discord", out: "vless-nl", count: 2, last: now() - 40, ago: 40 },
              { name: "gitlab.work.example", channel: "Работа", out: "wg-home", count: 1, last: now() - 610, ago: 610 },
            ]
          : [],
      }
    case "system.network":
      return network
    case "system.privateDns":
      return privateDns
    case "apps.list":
      await delay(350)
      return args.system ? apps : apps.filter((a) => !a.system)
    case "settings.get":
      return structuredClone(model)
    case "settings.put": {
      // Как Dispatcher.settingsPut: часть модели, подписки — только названия известных.
      const next = { ...structuredClone(model), ...structuredClone(args) } as Model
      next.subs = model.subs.map((s) => ({ ...s, name: args.subs?.find((x: { id: string }) => x.id === s.id)?.name ?? s.name }))
      for (const c of next.channels) {
        if (!c.name.trim()) throw new Fail("bad-args", "У правила нет имени")
        if (c.out !== "direct" && !next.outputs.some((o) => o.name === c.out)) throw new Fail("bad-args", `Правило «${c.name}»: выхода «${c.out}» нет`)
        for (const n of c.what.custom) if (!next.custom.some((x) => x.name === n)) throw new Fail("bad-args", `Правило «${c.name}»: своего списка «${n}» нет`)
      }
      model = next
      return { saved: true }
    }
    case "spec.preview":
      return { spec: { schema: 1 }, check: { code: 0, stderr: "" }, warnings: [], needs_local_dns: true }
    case "spec.apply":
      needEngine()
      await delay(900)
      if (q.get("apply") === "fail")
        return {
          applied: false,
          saved: false,
          rolled_back: true,
          message: "Система не приняла новые правила — оставлены прежние: устройство wg0 не найдено",
          warnings: [],
        }
      setTimeout(() => emit("engine.changed", engine), 50)
      return engine.enabled
        ? { applied: true, saved: true, warnings: [] }
        : { applied: false, saved: true, message: "Настройка сохранена; правила заработают, когда движок включат", warnings: [] }
    case "lists.catalog":
      await delay(200)
      return catalogReply()
    case "lists.select": {
      if (!catalog.some((c) => c.id === args.id)) throw new Fail("bad-args", "Не указан список")
      model.lists = args.on ? [...new Set([...model.lists, args.id])] : model.lists.filter((x) => x !== args.id)
      return { saved: true }
    }
    case "lists.update":
      setTimeout(() => {
        for (const e of catalog) if (e.downloaded) e.downloaded.updated = now()
        lastUpdate = { ok: true, changed: 3, at: now() }
        emit("lists.updated", { ok: true, changed: 3 })
      }, 2200)
      return { started: true }
    case "lists.custom": {
      if (args.put) {
        const name = String(args.put.name || "")
        if (!/^[A-Za-z0-9_-]{1,24}$/.test(name)) throw new Fail("bad-args", `Свой список «${name}»: латиница, цифры, «_» и «-», до 24 знаков`)
        const lines = [...(args.put.domains ?? []), ...(args.put.prefixes ?? []), ...String(args.put.text ?? "").split(/\s+/)]
          .map((x: string) => x.trim().toLowerCase())
          .filter(Boolean)
        const prefixes = lines.filter((x: string) => PREFIX.test(x))
        const domains = lines.filter((x: string) => !PREFIX.test(x) && DOMAIN.test(x))
        if (!domains.length && !prefixes.length) throw new Fail("bad-args", `В списке «${name}» нет ни одного домена или подсети`)
        model.custom = [...model.custom.filter((c) => c.name !== name), { name, domains, prefixes }]
        return { saved: true, domains: domains.length, prefixes: prefixes.length, dropped: lines.length - domains.length - prefixes.length }
      }
      if (args.remove) {
        const user = model.channels.find((c) => c.what.custom.includes(args.remove))
        if (user) throw new Fail("bad-args", `Список «${args.remove}» используется в правиле «${user.name}» — сначала уберите его оттуда`)
        model.custom = model.custom.filter((c) => c.name !== args.remove)
        return { saved: true }
      }
      return customReply()
    }
    case "subs.list":
      return subsReply()
    case "subs.add": {
      await delay(1200)
      const url = String(args.url || "").trim()
      const id = "s" + (subs.length + 1)
      if (/^https?:\/\//.test(url)) {
        const name = args.name || new URL(url).hostname
        subs = [...subs, { id, name, kind: "url", url, nodes: 3, updated: now(), skipped: 0, foreign: 0, used_by: [], hwid: subs[0]?.hwid ?? "" }]
        model.subs = [...model.subs, { id, name, kind: "url", url }]
      } else if (url.includes("vless://")) {
        const n = url.split(/\s+/).filter((x) => x.startsWith("vless://")).length
        subs = [...subs, { id, name: args.name || "Свои ссылки", kind: "links", nodes: n, updated: now(), skipped: 0, foreign: 0, used_by: [], hwid: subs[0]?.hwid ?? "" }]
        model.subs = [...model.subs, { id, name: args.name || "Свои ссылки", kind: "links" }]
      } else throw new Fail("bad-args", "Нужна ссылка на подписку (https://) или ссылка vless://")
      return subsReply()
    }
    case "subs.remove": {
      const user = model.outputs.find((o) => o.sub === args.id)
      if (user) throw new Fail("bad-args", `Подписку использует выход «${user.name}» — сначала уберите или перенастройте его`)
      subs = subs.filter((s) => s.id !== args.id)
      model.subs = model.subs.filter((s) => s.id !== args.id)
      return subsReply()
    }
    case "subs.refresh":
      await delay(1400)
      if (args.id && subs.find((s) => s.id === args.id)?.kind === "links") throw new Fail("bad-args", "Эту подписку нечем обновить — она из вставленных ссылок")
      subs = subs.map((s) => ((!args.id || s.id === args.id) && s.kind === "url" ? { ...s, updated: now() } : s))
      return subsReply()
    case "backup.export":
      await delay(300)
      return { file: "/data/user/0/com.der.splify2/files/exports/splify2-20260924-101500.json", name: "splify2-20260924-101500.json", bytes: 4812, saved: true }
    case "backup.import": {
      let parsed: { format?: string; model?: Model } | null = null
      try {
        parsed = JSON.parse(String(args.json || ""))
      } catch {
        throw new Fail("bad-args", "Это не файл настроек splify2")
      }
      if (!parsed || parsed.format !== "splify2-android-backup" || !parsed.model) throw new Fail("bad-args", "Это не файл настроек splify2 для телефона")
      model = parsed.model
      return { saved: true }
    }
  }
  throw new Fail("unknown-method", "Эта версия приложения не знает такого действия")
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

/**
 * Типы ответов моста (apps/Splify2/BRIDGE.md, версия 1).
 *
 * Две группы. Ответы движка (`engine.*`) — то, что печатает steer, дословно: форма
 * `steer status` здесь та же, что у splify2 на роутере (splify2/ui/src/lib/model.ts),
 * урезанная до полей, которые читает экран телефона. Лишние поля движка терпим, а не
 * требуем: движок и экран обновляются порознь.
 *
 * Модель настроек (`settings.get`/`settings.put`) принадлежит логике (logic/Model.kt).
 * Пока её схемы в дереве нет, форма ниже — предложение экрана, повторяющее понятия спеки;
 * экран сохраняет поля, которых не знает (`[k: string]: unknown`), и отдаёт модель обратно
 * целиком, поэтому расширение модели на стороне логики его не ломает.
 */

// ── Конверт моста ─────────────────────────────────────────────────────────────

export type BridgeErrorCode =
  | "bad-args"
  | "unknown-method"
  | "engine-down"
  | "engine"
  | "network"
  | "io"
  | "internal"

export type BridgeReply<T = unknown> =
  | { ok: true; result: T }
  | { ok: false; error: BridgeErrorCode | string; message?: string }

// ── engine ────────────────────────────────────────────────────────────────────

export interface EngineState {
  enabled: boolean
  reachable: boolean
  version: string
}

export type OutputKind = "interface" | "direct" | "vless" | "xsteer" | "zapret"
export type OnFail = "drop" | "direct" | "zapret"

/** Выход в ответе `steer status`. */
export interface OutputStatus {
  name: string
  kind: OutputKind
  up?: boolean
  device?: string
  devices?: string[]
  on_fail?: OnFail
  sub_file?: string
  node?: number
  nodes?: number[]
  /** Ход подъёма vless: только когда `up` ложно и движку есть что сказать. */
  probe?: { state: "probing" | "failed" | "no_such_node"; node?: number; total?: number }
}

/** Набор правила в ответе `steer status`: счётчики наружу и внутрь. */
export interface ChannelStatus {
  name: string
  out: string
  live: boolean
  packets?: number
  bytes?: number
  down_packets?: number
  down_bytes?: number
  /** Правила, сведённые движком в этот набор: счётчик у них общий. */
  channels?: string[]
  lists?: number
  kind?: "domains" | "prefixes"
}

export interface Status {
  schema: number
  features?: string[]
  outputs: Record<string, OutputStatus>
  channels: ChannelStatus[]
  warnings?: { code: string; text: string; channel?: string }[]
  at?: number
  cached?: true
}

export interface DiagCheck {
  id: string
  verdict: "ok" | "note" | "warn" | "fail"
  what: string
  why: string
}

export interface Diag {
  checks: DiagCheck[]
  warn: number
  fail: number
}

/** Узел подписки, как его видит движок; `index` — то же число, что `nodes` спеки. */
export interface VlessNode {
  index: number
  name: string
  host: string
  port: number
  type: string
  security: string
  vision: boolean
  mode?: string
}

export interface VlessNodesReply {
  output: string
  sub_file: string
  node: number
  chosen?: number[]
  usable: number
  skipped: number
  foreign: number
  nodes: VlessNode[]
  skipped_reasons?: { reason: string; count: number; example: string }[]
}

export interface VlessProbe {
  index: number
  name: string
  type: string
  ok: boolean
  handshake_ms: number
  ttfb_ms: number
  why: string
}

export interface VlessProbeReply {
  output?: string
  results?: VlessProbe[]
  working?: number
  error?: string
}

/** `engine.explain`: JSON движка как есть, либо `{text}`, если ответ не JSON. */
export type ExplainReply = { text: string } | Record<string, unknown>

/**
 * Соединение (`engine.conns`, появится с командой `conns` сокета). Формы в договоре ещё
 * нет; разбор (`parseConns` в lib/parse.ts) терпит и массив, и `{conns:[…]}`, и
 * отсутствие любого поля кроме адреса назначения.
 */
export interface Conn {
  proto: string
  dst: string
  dport?: number
  src?: string
  sport?: number
  /** Имя, под которым приложение спросило адрес (из журнала резолвера), если известно. */
  host?: string
  uid?: number
  channel?: string
  out?: string
  bytes?: number
  /** Сколько секунд живёт соединение. */
  age?: number
}

/** Запрос имени (`engine.dnsLog`, появится с командой `dns-log` сокета). */
export interface DnsEntry {
  /** unix-время запроса */
  at: number
  name: string
  qtype?: string
  uid?: number
  channel?: string
  out?: string
  answers?: string[]
}

// ── system ────────────────────────────────────────────────────────────────────

export interface NetworkInfo {
  type: "wifi" | "cellular" | "ethernet" | "none"
  name?: string
  metered: boolean
}

export type PrivateDnsMode = "off" | "opportunistic" | "hostname"

export interface PrivateDns {
  mode: PrivateDnsMode
  host?: string
  /** splify2 держит его выключенным ради доменных правил телефона. */
  managed: boolean
  /** Что было у человека до этого. */
  saved?: { mode: PrivateDnsMode; host?: string }
}

// ── apps ──────────────────────────────────────────────────────────────────────

export interface AppInfo {
  uid: number
  pkg: string
  label: string
  system: boolean
  shared: string[]
}

// ── settings / spec ───────────────────────────────────────────────────────────

/** Выход в модели. `sub` — id подписки (subs.list), а не файл: файл — дело логики. */
export interface ModelOutput {
  name: string
  kind: OutputKind
  /** Подпись для человека: «Нидерланды», «свой WireGuard». */
  title?: string
  sub?: string
  /** vless: выбранные узлы по предпочтению; пусто — первый рабочий. */
  nodes?: number[]
  devices?: string[]
  on_fail?: OnFail
  [k: string]: unknown
}

/** Кому правило: весь телефон (`from:"self"`), приложения (`from:"uid:N"`), раздача. */
export type Who = "phone" | "apps" | "tether"

export interface ModelMatch {
  /** Весь трафик. */
  any?: boolean
  /** id списков каталога (lists.catalog). */
  lists?: string[]
  /** имена своих списков (lists.custom). */
  custom?: string[]
  domains?: string[]
  prefixes?: string[]
}

export interface ModelChannel {
  name: string
  enabled?: boolean
  who: Who
  /** who=apps: uid приложений. */
  uids?: number[]
  match: ModelMatch
  /** Имя выхода. */
  out: string
  [k: string]: unknown
}

export interface Model {
  outputs: Record<string, ModelOutput>
  /** Сверху вниз: первое совпадение побеждает. */
  channels: ModelChannel[]
  [k: string]: unknown
}

export interface ApplyResult {
  applied: boolean
  rolled_back?: boolean
  message?: string
}

export interface SpecPreview {
  spec: Record<string, unknown>
  check: { code: number; stderr: string }
}

// ── lists ─────────────────────────────────────────────────────────────────────

export interface CatalogEntry {
  id: string
  name: string
  description?: string
  kind: "domains" | "prefixes"
  /** Раздел каталога: «Видео», «Мессенджеры»… */
  category?: string
  /** записей */
  count?: number
  /** unix-время файла */
  updated?: number
  selected: boolean
}

/** Каталог целиком. Ответ-массив разбор сводит к `{lists}`. */
export interface Catalog {
  lists: CatalogEntry[]
  /** когда каталог обновлялся, unix-время */
  updated?: number
}

export interface CustomList {
  name: string
  domains: string[]
  prefixes: string[]
}

export interface ListsUpdated {
  ok: boolean
  changed: number
  message?: string
}

// ── subs ──────────────────────────────────────────────────────────────────────

export interface Sub {
  id: string
  name: string
  url: string
  nodes: number
  /** unix-время последнего обновления, 0 — ещё не скачивалась */
  updated: number
  /** Остаток по заголовку subscription-userinfo: байты и unix-время; 0 — не названо. */
  quota?: { up: number; down: number; total: number; expire: number }
}

// ── backup ────────────────────────────────────────────────────────────────────

export interface BackupExport {
  file: string
}

// ── Карта методов: имя → [аргументы, результат] ─────────────────────────────

export interface Methods {
  "engine.state": [void, EngineState]
  "engine.setEnabled": [{ on: boolean }, EngineState]
  "engine.status": [{ fast?: boolean } | void, Status]
  "engine.diag": [void, Diag]
  "engine.explain": [{ q: string }, ExplainReply]
  "engine.vlessNodes": [{ out: string }, VlessNodesReply]
  "engine.vlessProbe": [{ out: string; node?: string }, VlessProbeReply]
  "engine.conns": [void, unknown]
  "engine.dnsLog": [void, unknown]
  "system.network": [void, NetworkInfo]
  "system.privateDns": [void, PrivateDns]
  "apps.list": [{ system?: boolean } | void, AppInfo[]]
  "settings.get": [void, Model]
  "settings.put": [Model, { saved: true }]
  "spec.preview": [void, SpecPreview]
  "spec.apply": [void, ApplyResult]
  "lists.catalog": [void, Catalog | CatalogEntry[]]
  "lists.select": [{ id: string; on: boolean }, { saved: true }]
  "lists.update": [{ force?: boolean } | void, { started: true }]
  "lists.custom": [{ put: CustomList } | { remove: string } | void, CustomList[] | { saved: true }]
  "subs.list": [void, Sub[]]
  "subs.add": [{ url: string }, Sub[]]
  "subs.remove": [{ id: string }, Sub[]]
  "subs.refresh": [{ id?: string } | void, Sub[]]
  "backup.export": [void, BackupExport]
  "backup.import": [{ json: string }, { saved: true }]
}

export type Method = keyof Methods

export interface Events {
  "engine.changed": EngineState
  "network.changed": NetworkInfo
  "lists.updated": ListsUpdated
  "subs.updated": Sub[]
}

export type EventName = keyof Events

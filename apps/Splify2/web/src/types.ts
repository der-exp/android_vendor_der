/**
 * Типы ответов моста (apps/Splify2/BRIDGE.md, версия 1).
 *
 * Три группы, у каждой свой источник правды:
 *   - `engine.*` — то, что печатает движок, дословно (steer/docs/ctl.md и вывод его команд):
 *     форма `steer status` та же, что у splify2 на роутере (splify2/ui/src/lib/model.ts),
 *     урезанная до полей, которые читает экран телефона. Лишние поля движка терпим, а не
 *     требуем: движок и экран обновляются порознь;
 *   - модель настроек и методы `settings`, `spec`, `lists`, `subs`, `backup` — логика
 *     (src/com/der/splify2/logic, форма модели — в шапке Model.kt). Логику проверяет стенд
 *     против настоящего движка, поэтому экран подстраивается под неё, а не наоборот;
 *   - `system.*`, `apps.*` — оболочка.
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

/** Виды выходов на телефоне (Model.kt, OutKind). zapret и xsteer здесь нет — решение владельца. */
export type OutputKind = "interface" | "direct" | "vless" | "tgws"
export type OnFail = "drop" | "direct"

/** Выход в ответе `steer status`. */
export interface OutputStatus {
  name: string
  kind: OutputKind | string
  up?: boolean
  device?: string
  devices?: string[]
  on_fail?: string
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

/** Узел подписки, как его видит движок; `index` — то же число, что `nodes` выхода. */
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

/** `steer vless-probe` (src/ext/tunnel.c): замер — `{output, sub_file, results, working}`;
 *  замерять нечего (нет узлов, нет такого узла) — `{ok:false, error}`. */
export interface VlessProbeReply {
  output?: string
  sub_file?: string
  results?: VlessProbe[]
  working?: number
  ok?: false
  error?: string
}

/** `engine.explain`: JSON движка как есть, либо `{text}`, если ответ не JSON. */
export type ExplainReply = { text: string } | Record<string, unknown>

/** Соединение из `steer conns` (ctl.md, «conns»): conntrack с меткой движка. Приложения и
 *  имени, под которым спросили адрес, здесь нет — conntrack их не хранит. */
export interface Conn {
  family: "ipv4" | "ipv6"
  proto: string
  src: string
  sport?: number
  dst: string
  dport?: number
  mark?: string
  /** Выход по реестру меток применённой спеки; null — выход уже убран, соединение доживает. */
  out: string | null
  state?: string
  packets?: number
  bytes?: number
  reply_packets?: number
  reply_bytes?: number
}

export interface ConnsReply {
  schema: number
  conns: Conn[]
  shown: number
  total: number
  truncated: boolean
}

/** Имя из журнала резолвера (`steer dns-log`): куда попало при последнем запросе. */
export interface DnsName {
  name: string
  /** Правило; null — имя не попало ни в одно доменное правило. */
  channel: string | null
  out: string | null
  count: number
  last: number
  /** Сколько секунд назад спрашивали последний раз. */
  ago: number
}

export interface DnsLogReply {
  schema: number
  /** false — резолвер не запущен (маршрутизация выключена или правил по доменам нет). */
  running: boolean
  size: number
  names: DnsName[]
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

// ── Модель настроек (logic/Model.kt) ─────────────────────────────────────────

/** Выход. `direct` в модели не заводится — «напрямую» есть всегда (out: "direct"). */
export interface ModelOutput {
  name: string
  kind: OutputKind
  /** interface: устройства по предпочтению — первое здоровое забирает трафик. */
  devices?: string[]
  on_fail?: OnFail
  /** vless: id подписки (Model.subs, subs.list). */
  sub?: string
  /** vless: номера узлов по предпочтению; пусто — первый рабочий. */
  nodes?: number[]
  /** tgws: имя за Cloudflare для моста Telegram. */
  domain?: string
}

/** Кому правило: весь телефон (from:"self"), приложения (from:"uid:N"), раздача
 *  (from пуст — все устройства раздачи; иначе адреса, подсети или MAC). */
export type Who = { kind: "phone" } | { kind: "apps"; uids: number[] } | { kind: "tether"; from: string[] }

export type WhoKind = Who["kind"]

export interface What {
  /** id служб каталога (lists.catalog → items[].id). */
  lists: string[]
  /** имена своих списков (lists.custom). */
  custom: string[]
  /** Весь трафик — вместо списков. */
  all: boolean
}

export interface ModelChannel {
  name: string
  enabled: boolean
  who: Who
  what: What
  /** Имя выхода или "direct". */
  out: string
}

export interface ModelCustom {
  name: string
  domains: string[]
  prefixes: string[]
}

export interface ModelSub {
  id: string
  name: string
  url?: string
  kind: "url" | "links"
}

export interface Model {
  version: number
  outputs: ModelOutput[]
  /** Сверху вниз: первое совпадение побеждает. */
  channels: ModelChannel[]
  /** Выбранные службы каталога — их списки обновляются, даже если правила на них ещё нет. */
  lists: string[]
  custom: ModelCustom[]
  /** Только своими методами subs.*: settings.put подписки не добавляет и не удаляет. */
  subs: ModelSub[]
  tether: { devices: string[] }
  catalog_url: string | null
  update: { unmetered_only: boolean }
}

/** settings.put: модель целиком или её часть — чего нет в запросе, остаётся как было. */
export type ModelPatch = Partial<Omit<Model, "subs">> & { subs?: Pick<ModelSub, "id" | "name">[] }

export interface ApplyResult {
  /** Правила стоят. */
  applied: boolean
  /** Движок сохранил настройку (при выключенном движке — только сохранил). */
  saved: boolean
  rolled_back?: boolean
  message?: string
  /** Что пропущено при сборке: список ещё не скачан, правилу нечего забирать. */
  warnings?: string[]
}

export interface SpecPreview {
  spec: Record<string, unknown>
  check: { code: number; stderr: string; error?: string }
  warnings: string[]
  needs_local_dns: boolean
}

// ── lists ─────────────────────────────────────────────────────────────────────

export type ListKind = "domains" | "prefixes"

/** Служба каталога splify2-lists: одна строка на экране, за ней — файлы доменов и подсетей. */
export interface CatalogItem {
  id: string
  name: string
  description?: string
  /** Издатель: «itdoginfo (allow-domains)». */
  source?: string
  kinds: ListKind[]
  /** Записей по каталогу (у наборов издателя бывает не названо). */
  count?: number
  tag?: string
  default_on: boolean
  selected: boolean
  /** Есть в правилах (сохранённых). */
  used: boolean
  /** Скачано на телефон: записей и когда (unix-время). */
  downloaded?: { count: number; updated: number }
  /** Подсети службы сужены протоколом и портами. */
  narrow?: { proto: string | null; ports: string[] }
}

export interface ListsUpdated {
  ok: boolean
  changed: number
  message?: string
}

export interface Catalog {
  version: string
  /** Когда скачан сам каталог, unix-время; 0 — ещё не скачивался. */
  updated: number
  /** Итог последнего обновления списков (at — когда). */
  last_update: (ListsUpdated & { at?: number }) | null
  items: CatalogItem[]
}

export interface CustomList {
  name: string
  domains: string[]
  prefixes: string[]
  used?: boolean
}

/** lists.custom put: имя и строки. `text` логика сама делит на домены и подсети. */
export interface CustomPut {
  name: string
  text?: string
  domains?: string[]
  prefixes?: string[]
}

export interface CustomPutReply {
  saved: true
  domains: number
  prefixes: number
  /** Строк, не похожих ни на домен, ни на подсеть. */
  dropped: number
}

// ── subs ──────────────────────────────────────────────────────────────────────

/** Остаток по заголовку subscription-userinfo. Байты — строками (JSON-число в JavaScript
 *  точно только до 2^53); total "" — объём не назван; expire 0 — срок не назван. */
export interface Quota {
  up: string
  down: string
  total: string
  expire: number
  at?: number
}

export interface Sub {
  id: string
  name: string
  kind: "url" | "links"
  /** Только у подписки по ссылке; у вставленных ссылок vless:// её нет. */
  url?: string
  /** Пригодных узлов. */
  nodes: number
  /** unix-время последнего скачивания; 0 — ещё не скачивалась. */
  updated: number
  skipped: number
  foreign: number
  /** Выходы, которые её используют. */
  used_by: string[]
  hwid: string
  quota?: Quota
  /** Что сказала панель об устройстве — готовая фраза. */
  warn?: string
  /** Страница поставщика. */
  link?: string
  /** Почему узлы не подошли: причина → сколько. */
  reasons?: Record<string, number>
}

// ── backup ────────────────────────────────────────────────────────────────────

export interface BackupExport {
  file: string
  name: string
  bytes: number
  /** Ставит оболочка после «Сохранить как»: false — человек закрыл окно. */
  saved?: boolean
}

// ── Карта методов: имя → [аргументы, результат] ─────────────────────────────

export interface Methods {
  "engine.state": [void, EngineState]
  "engine.setEnabled": [{ on: boolean }, EngineState]
  "engine.status": [{ fast?: boolean } | void, Status]
  "engine.diag": [void, Diag]
  "engine.explain": [{ q: string }, ExplainReply]
  "engine.vlessNodes": [{ out: string }, VlessNodesReply]
  "engine.vlessProbe": [{ out: string; node?: number }, VlessProbeReply]
  "engine.conns": [void, ConnsReply]
  "engine.dnsLog": [void, DnsLogReply]
  "system.network": [void, NetworkInfo]
  "system.privateDns": [void, PrivateDns]
  "apps.list": [{ system?: boolean } | void, AppInfo[]]
  "settings.get": [void, Model]
  "settings.put": [ModelPatch, { saved: true }]
  "spec.preview": [void, SpecPreview]
  "spec.apply": [void, ApplyResult]
  "lists.catalog": [void, Catalog]
  "lists.select": [{ id: string; on: boolean }, { saved: true }]
  "lists.update": [{ force?: boolean } | void, { started: boolean; running?: boolean }]
  "lists.custom": [{ put: CustomPut } | { remove: string } | void, CustomList[] | CustomPutReply | { saved: true }]
  "subs.list": [void, Sub[]]
  "subs.add": [{ url: string; name?: string }, Sub[]]
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

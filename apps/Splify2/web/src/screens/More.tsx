/**
 * Ещё: списки из каталога и их обновление, свои списки, подписки, резервная копия,
 * движок. Каждое — подэкран со стрелкой назад (глубже двух уровней Andromeda не ходит).
 *
 * В отличие от правил, здесь всё сохраняется сразу: выбор списка, свой список, подписка,
 * настройка обновления, импорт — это данные логики, а не черновик правил; к движку они
 * попадут со следующим применением правил (или ночным обновлением — оно применяет само).
 */
import { useEffect, useMemo, useRef, useState } from "react"
import { Badge, Button, Callout, Card, Dialog, Field, Meter, Skeleton, StatusDot, Switch, Textarea } from "@andromeda/ui"
import { call, errorText, on } from "../bridge"
import { groupBySource, useStore } from "../store"
import { useNav, type Sub as SubRoute } from "../nav"
import { Body, CardHead, Divider, Empty, Header, KV, TapRow, col, ellipsis, mono, muted, rowS } from "../ui"
import { Icon } from "../icons"
import { bytesOf, fmtAgo, fmtBytes, fmtDate } from "../format"
import type { Diag, Sub } from "../types"
import { PickRow, Search, catalogMeta, customSub } from "./Pickers"
import { CustomEdit } from "./CustomEdit"

// ── Списки каталога ───────────────────────────────────────────────────────────

function useListsUpdate() {
  const { toast, reloadCatalog } = useStore()
  const [busy, setBusy] = useState(false)
  useEffect(
    () =>
      on("lists.updated", (r) => {
        setBusy(false)
        void reloadCatalog()
        if (r.ok) toast(r.changed ? `Списки обновлены · изменилось: ${r.changed}` : "Списки обновлены, изменений нет")
        else toast(r.message || "Списки не обновились", "bad")
      }),
    [toast, reloadCatalog],
  )
  const start = async () => {
    setBusy(true)
    try {
      await call("lists.update", { force: true })
    } catch (e) {
      setBusy(false)
      toast(errorText(e), "bad")
    }
  }
  return { busy, start }
}

function ListsPage() {
  const { catalog, catalogInfo, catalogError, reloadCatalog, draft, toast } = useStore()
  const { back } = useNav()
  const upd = useListsUpdate()
  const [q, setQ] = useState("")
  const [local, setLocal] = useState<Record<string, boolean>>({})
  // «В правилах» — по черновику: человек видит свои правила такими, какими их только что правил.
  const usedBy = useMemo(() => {
    const m = new Map<string, string[]>()
    for (const c of draft?.channels ?? []) for (const id of c.what.lists) m.set(id, [...(m.get(id) ?? []), c.name])
    return m
  }, [draft])
  const s = q.trim().toLowerCase()
  const items = (catalog ?? []).filter((e) => !s || e.name.toLowerCase().includes(s) || e.id.toLowerCase().includes(s))
  const groups = groupBySource(items)
  const selected = (catalog ?? []).filter((e) => local[e.id] ?? e.selected).length
  const last = catalogInfo?.last_update
  const toggle = async (id: string, cur: boolean) => {
    setLocal((l) => ({ ...l, [id]: !cur }))
    try {
      await call("lists.select", { id, on: !cur })
      await reloadCatalog()
    } catch (e) {
      toast(errorText(e), "bad")
    }
    setLocal((l) => {
      const n = { ...l }
      delete n[id]
      return n
    })
  }
  return (
    <>
      <Header title="Списки" back={back} />
      <Body>
        <Card style={col("var(--an-space-4)")}>
          <KV k="выбрано" v={catalog ? `${selected} из ${catalog.length}` : "—"} />
          <KV k="обновлены" v={last?.at ? fmtAgo(last.at) : "ещё не обновлялись"} />
          {last && !last.ok && last.message ? <Callout tone="warn" title="Обновились не все" verbatim={last.message} /> : null}
          <Button tone="secondary" full icon={<Icon name="refresh" />} busy={upd.busy} onClick={upd.start}>
            {upd.busy ? "Скачиваем…" : "Обновить списки"}
          </Button>
        </Card>
        <div style={muted}>Выбранные списки обновляются, даже если их ещё нет в правилах.</div>
        <Search value={q} onChange={setQ} placeholder="Найти список" />
        {!catalog ? <Skeleton height={56} count={6} /> : null}
        {catalog && catalog.length === 0 && catalogError ? <Empty icon="list" text={catalogError} /> : null}
        {catalog && catalog.length > 0 && items.length === 0 ? <Empty icon="search" text="Ничего не нашлось." /> : null}
        {groups.map(([src, list]) => (
          <Card key={src} style={col("var(--an-space-1)")}>
            <CardHead title={src} />
            {list.map((e) => {
              const cur = local[e.id] ?? e.selected
              const used = usedBy.get(e.id)
              return (
                <PickRow
                  key={e.id}
                  on={cur}
                  onClick={() => void toggle(e.id, cur)}
                  title={e.name}
                  sub={used ? `в правилах: ${used.join(", ")}` : e.description ?? (e.downloaded ? `скачан ${fmtAgo(e.downloaded.updated)}` : undefined)}
                  meta={catalogMeta(e)}
                />
              )
            })}
          </Card>
        ))}
      </Body>
    </>
  )
}

// ── Свои списки ───────────────────────────────────────────────────────────────

function CustomPage() {
  const { back } = useNav()
  const { custom, reloadCustom } = useStore()
  const [edit, setEdit] = useState<{ orig?: string; text: string } | null>(null)
  useEffect(() => {
    void reloadCustom()
  }, [reloadCustom])

  if (edit)
    return <CustomEdit orig={edit.orig} initText={edit.text} onSaved={() => setEdit(null)} onBack={() => setEdit(null)} />

  return (
    <>
      <Header title="Свои списки" back={back} />
      <Body>
        {!custom ? <Skeleton height={56} count={3} /> : null}
        {custom && custom.length === 0 ? <Empty icon="file" text="Своих списков нет" /> : null}
        {custom && custom.length > 0 ? (
          <Card style={col("var(--an-space-1)")}>
            {custom.map((c) => (
              <TapRow
                key={c.name}
                icon="file"
                title={c.name}
                subtitle={customSub(c)}
                onClick={() => setEdit({ orig: c.name, text: [...c.domains, ...c.prefixes].join("\n") })}
              />
            ))}
          </Card>
        ) : null}
        <Button tone="secondary" full icon={<Icon name="plus" />} onClick={() => setEdit({ text: "" })}>
          Новый список
        </Button>
      </Body>
    </>
  )
}

// ── Подписки ──────────────────────────────────────────────────────────────────

function SubCard({ s, onChange }: { s: Sub; onChange: (l: Sub[]) => void }) {
  const { toast, draft } = useStore()
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState(false)
  // Выходы — по черновику: выход, добавленный, но ещё не применённый, тоже держит подписку.
  const outs = [...new Set([...(draft?.outputs ?? []).filter((o) => o.sub === s.id).map((o) => o.name), ...s.used_by])]
  const q = s.quota
  const used = q ? bytesOf(q.up) + bytesOf(q.down) : 0
  const total = q ? bytesOf(q.total) : 0
  const refresh = async () => {
    setBusy(true)
    try {
      onChange(await call("subs.refresh", { id: s.id }))
      toast("Подписка обновлена")
    } catch (e) {
      toast(errorText(e), "bad")
    } finally {
      setBusy(false)
    }
  }
  const remove = async () => {
    setConfirm(false)
    try {
      onChange(await call("subs.remove", { id: s.id }))
    } catch (e) {
      toast(errorText(e), "bad")
    }
  }
  const expired = q?.expire ? q.expire < Date.now() / 1000 : false
  return (
    <Card style={col("var(--an-space-4)")}>
      <div style={col("2px")}>
        <h2 style={{ font: "var(--an-text-heading)", ...ellipsis }}>{s.name || "Подписка"}</h2>
        {s.url ? <span style={{ ...mono, ...ellipsis, wordBreak: "normal" }}>{s.url}</span> : <span style={muted}>вставленные ссылки</span>}
      </div>
      <div style={col("var(--an-space-2)")}>
        <KV k="узлов" v={s.skipped || s.foreign ? `${s.nodes} · не подходят: ${s.skipped + s.foreign}` : s.nodes} />
        <KV k="обновлена" v={s.updated ? fmtAgo(s.updated) : "ещё не скачивалась"} />
        {outs.length ? <KV k="выходы" v={outs.join(", ")} /> : null}
        {q?.expire ? <KV k="действует до" v={<span style={{ color: expired ? "var(--an-danger-ink)" : undefined }}>{fmtDate(q.expire)}</span>} /> : null}
      </div>
      {q && (total > 0 || used > 0) ? (
        <div style={col("var(--an-space-3)")}>
          {total > 0 ? <Meter value={Math.min(100, (used / total) * 100)} tone={used / total > 0.9 ? "warn" : "accent"} height={8} /> : null}
          <span className="sp-tab" style={muted}>
            израсходовано: {fmtBytes(used)}
            {total > 0 ? ` из ${fmtBytes(total)}` : " · объём не ограничен"}
          </span>
        </div>
      ) : null}
      {s.warn ? <Callout tone="warn" title="Панель подписки" verbatim={s.warn} /> : null}
      <div style={{ display: "flex", gap: "var(--an-space-3)" }}>
        {s.kind === "url" ? (
          <Button tone="secondary" icon={<Icon name="refresh" />} busy={busy} onClick={refresh} style={{ flex: 1 }}>
            {busy ? "Обновляем…" : "Обновить"}
          </Button>
        ) : null}
        <Button tone="danger" icon={<Icon name="trash" />} disabled={outs.length > 0} onClick={() => setConfirm(true)} style={{ flex: 1 }}>
          Удалить
        </Button>
      </div>
      {outs.length ? <div style={muted}>Чтобы удалить подписку, уберите выходы с ней.</div> : null}
      <Dialog open={confirm} title={`Удалить «${s.name}»?`} confirmLabel="Удалить подписку" onConfirm={remove} onCancel={() => setConfirm(false)}>
        Узлы этой подписки удалятся с телефона.
      </Dialog>
    </Card>
  )
}

function SubsPage() {
  const { back } = useNav()
  const { toast } = useStore()
  const [subs, setSubs] = useState<Sub[] | null>(null)
  const [url, setUrl] = useState("")
  const [adding, setAdding] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => {
    call("subs.list").then(setSubs).catch((e) => {
      setSubs([])
      toast(errorText(e), "bad")
    })
    return on("subs.updated", setSubs)
  }, [toast])
  const add = async () => {
    const u = url.trim()
    if (!/^https?:\/\//i.test(u) && !u.includes("vless://")) {
      setErr("Нужна ссылка на подписку https://… или ссылки vless://")
      return
    }
    setAdding(true)
    setErr(null)
    try {
      setSubs(await call("subs.add", { url: u }))
      setUrl("")
      toast("Подписка добавлена")
    } catch (e) {
      setErr(errorText(e))
    } finally {
      setAdding(false)
    }
  }
  return (
    <>
      <Header title="Подписки" back={back} />
      <Body>
        {!subs ? <Skeleton height={180} radius="var(--an-radius-card)" /> : null}
        {subs && subs.length === 0 ? <Empty icon="link" text="Подписок нет" /> : null}
        {subs?.map((s) => <SubCard key={s.id} s={s} onChange={setSubs} />)}
        <Card style={col("var(--an-space-4)")}>
          <CardHead title="Новая подписка" />
          <Field label="Ссылка" hint="на подписку или сами ссылки vless://, по одной в строке" error={err}>
            <Textarea rows={3} mono value={url} placeholder="https://… или vless://…" onInput={(e) => setUrl(e.currentTarget.value)} autoCapitalize="none" autoCorrect="off" spellCheck={false} />
          </Field>
          <Button tone="primary" full icon={<Icon name="plus" />} busy={adding} disabled={!url.trim()} onClick={add}>
            {adding ? "Скачиваем…" : "Добавить"}
          </Button>
        </Card>
      </Body>
    </>
  )
}

// ── Резервная копия ───────────────────────────────────────────────────────────

function BackupPage() {
  const { back } = useNav()
  const { toast, loadDraft, reloadCustom, reloadCatalog } = useStore()
  const [exporting, setExporting] = useState(false)
  const [pending, setPending] = useState<{ name: string; json: string } | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const exp = async () => {
    setExporting(true)
    try {
      const r = await call("backup.export")
      // saved ставит оболочка после «Сохранить как»: false — окно закрыли, это не ошибка.
      if (r.saved !== false) toast(`Сохранено: ${r.name}`)
    } catch (e) {
      toast(errorText(e), "bad")
    } finally {
      setExporting(false)
    }
  }
  const pick = (f: File | undefined) => {
    if (!f) return
    f.text().then((json) => setPending({ name: f.name, json }))
    if (fileRef.current) fileRef.current.value = ""
  }
  const imp = async () => {
    if (!pending) return
    const p = pending
    setPending(null)
    setErr(null)
    try {
      await call("backup.import", { json: p.json })
      // Загруженное сохранено, но не применено: черновик получает модель из файла, и
      // пилюля показывает, сколько правил и выходов поменяется при применении.
      const m = await call("settings.get")
      loadDraft(m)
      void reloadCustom()
      void reloadCatalog()
      toast("Загружено · примените правила")
    } catch (e) {
      setErr(errorText(e))
    }
  }
  return (
    <>
      <Header title="Резервная копия" back={back} />
      <Body>
        <Card style={col("var(--an-space-4)")}>
          <CardHead title="Сохранить в файл" />
          <div style={muted}>правила, выходы, подписки и списки</div>
          <div style={muted}>В файле — доступ ко всем подключениям подписок: не выкладывайте его в чаты и общие папки.</div>
          <Button tone="secondary" full icon={<Icon name="download" />} busy={exporting} onClick={exp}>
            Сохранить
          </Button>
        </Card>
        <Card style={col("var(--an-space-4)")}>
          <CardHead title="Загрузить из файла" />
          <input ref={fileRef} type="file" accept="application/json,.json" style={{ display: "none" }} onChange={(e) => pick(e.currentTarget.files?.[0])} />
          <Button tone="secondary" full icon={<Icon name="upload" />} onClick={() => fileRef.current?.click()}>
            Выбрать файл
          </Button>
          {err ? <Callout tone="danger" title="Не загрузилось" verbatim={err} /> : null}
        </Card>
      </Body>
      <Dialog open={!!pending} tone="accent" title="Загрузить копию?" confirmLabel="Загрузить" onConfirm={imp} onCancel={() => setPending(null)}>
        Правила, выходы и списки заменятся содержимым файла {pending?.name}.
      </Dialog>
    </>
  )
}

// ── Движок ────────────────────────────────────────────────────────────────────

const VERDICT_TONE = { ok: "ok", note: "off", warn: "warn", fail: "bad" } as const

function EnginePage() {
  const { back } = useNav()
  const { engine, engineError } = useStore()
  const [diag, setDiag] = useState<Diag | null>(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const run = async () => {
    setBusy(true)
    setErr(null)
    try {
      setDiag(await call("engine.diag"))
    } catch (e) {
      setErr(errorText(e))
    } finally {
      setBusy(false)
    }
  }
  const bad = diag?.checks.filter((c) => c.verdict === "warn" || c.verdict === "fail") ?? []
  const rest = diag?.checks.filter((c) => c.verdict === "ok" || c.verdict === "note") ?? []
  return (
    <>
      <Header title="Движок" back={back} />
      <Body>
        <Card style={col("var(--an-space-2)")}>
          <KV k="версия" v={engine?.version || "—"} />
          <KV
            k="состояние"
            v={
              <span style={rowS("var(--an-space-3)")}>
                <StatusDot tone={engine?.reachable ? "ok" : "bad"} />
                {engineError ? engineError : engine?.reachable ? "отвечает" : "не отвечает"}
              </span>
            }
          />
          <KV k="маршрутизация" v={engine?.enabled ? "включена" : "выключена"} />
        </Card>
        <Card style={col("var(--an-space-4)")}>
          <CardHead title="Проверка" meta={diag ? `предупреждений: ${diag.warn} · отказов: ${diag.fail}` : undefined} />
          <Button tone="secondary" full icon={<Icon name="diag" />} busy={busy} onClick={run}>
            {busy ? "Проверяем…" : diag ? "Проверить ещё раз" : "Проверить"}
          </Button>
          {err ? <div style={{ font: "var(--an-text-body-sm)", color: "var(--an-danger-ink)" }}>{err}</div> : null}
          {bad.map((c) => (
            <Callout key={c.id} tone={c.verdict === "fail" ? "danger" : "warn"} title={c.verdict === "fail" ? "отказ" : "предупреждение"} verbatim={c.what}>
              {c.why ? <div style={muted}>{c.why}</div> : null}
            </Callout>
          ))}
          {rest.length ? (
            <div style={col("var(--an-space-3)")}>
              {rest.map((c) => (
                <div key={c.id} style={{ ...rowS("var(--an-space-5)"), alignItems: "baseline" }}>
                  <StatusDot tone={VERDICT_TONE[c.verdict]} style={{ transform: "translateY(-1px)" }} />
                  <span style={{ font: "var(--an-text-body-sm)" }}>{c.what}</span>
                </div>
              ))}
            </div>
          ) : null}
        </Card>
      </Body>
    </>
  )
}

// ── Меню ──────────────────────────────────────────────────────────────────────

export function More() {
  const { route, open } = useNav()
  const { catalog, catalogInfo, engine, custom, draft, saveSetting } = useStore()
  const [subs, setSubs] = useState<Sub[] | null>(null)
  useEffect(() => {
    if (route.sub) return
    call("subs.list").then(setSubs).catch(() => {})
  }, [route.sub])

  switch ((route.sub as SubRoute | undefined)?.kind) {
    case "lists":
      return <ListsPage />
    case "custom":
      return <CustomPage />
    case "subs":
      return <SubsPage />
    case "backup":
      return <BackupPage />
    case "engine":
      return <EnginePage />
  }

  const sel = catalog?.filter((e) => e.selected).length
  const last = catalogInfo?.last_update?.at
  const unmetered = draft?.update.unmetered_only ?? true
  return (
    <>
      <Header title="Ещё" />
      <Body>
        <Card style={col("0px")}>
          <TapRow
            icon="list"
            title="Списки"
            subtitle={catalog ? `выбрано: ${sel} из ${catalog.length} · ${last ? `обновлены ${fmtAgo(last)}` : "ещё не обновлялись"}` : undefined}
            onClick={() => open({ kind: "lists" })}
          />
          <Divider />
          <TapRow icon="file" title="Свои списки" subtitle={custom ? `списков: ${custom.length}` : undefined} onClick={() => open({ kind: "custom" })} />
          <Divider />
          <TapRow
            icon="link"
            title="Подписки"
            subtitle={subs ? `подписок: ${subs.length}` : undefined}
            right={subs?.some((s) => s.quota?.expire && s.quota.expire < Date.now() / 1000) ? <Badge tone="danger">истекла</Badge> : undefined}
            onClick={() => open({ kind: "subs" })}
          />
          <Divider />
          <TapRow icon="archive" title="Резервная копия" subtitle="сохранить и загрузить настройки" onClick={() => open({ kind: "backup" })} />
          <Divider />
          <TapRow icon="diag" title="Движок" subtitle={engine ? `версия ${engine.version || "—"}` : undefined} onClick={() => open({ kind: "engine" })} />
        </Card>
        <Card style={col("var(--an-space-3)")}>
          <CardHead title="Обновление списков и подписок" meta="раз в сутки" />
          <div style={{ ...rowS("var(--an-space-6)"), justifyContent: "space-between", minHeight: 44 }}>
            <span style={{ font: "var(--an-text-body-sm)" }}>Только без лимитной сети</span>
            <Switch
              size="lg"
              label="Только без лимитной сети"
              checked={unmetered}
              disabled={!draft}
              onChange={() => void saveSetting({ update: { unmetered_only: !unmetered } })}
            />
          </div>
          <div style={muted}>{unmetered ? "по мобильной сети и лимитному Wi-Fi не обновляются" : "обновляются в любой сети"}</div>
        </Card>
      </Body>
    </>
  )
}

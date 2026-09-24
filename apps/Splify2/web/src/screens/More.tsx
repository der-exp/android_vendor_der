/**
 * Ещё: списки из каталога и их обновление, свои списки, подписки, резервная копия,
 * движок. Каждое — подэкран со стрелкой назад (глубже двух уровней Andromeda не ходит).
 *
 * В отличие от правил, здесь всё действует сразу: выбор списка, подписка, импорт — это
 * данные логики, а не спека; к движку они попадут со следующим применением правил.
 */
import { useEffect, useMemo, useRef, useState } from "react"
import { Badge, Button, Callout, Card, Dialog, Field, Input, Meter, Skeleton, StatusDot, Textarea } from "@andromeda/ui"
import { call, errorText, on } from "../bridge"
import { useStore } from "../store"
import { useNav, type Sub as SubRoute } from "../nav"
import { Body, CardHead, Divider, Empty, Header, KV, TapRow, col, ellipsis, mono, muted, rowS } from "../ui"
import { Icon } from "../icons"
import { fmtAgo, fmtBytes, fmtDate, fmtInt } from "../format"
import type { CustomList, Diag, Sub } from "../types"
import { PickRow, Search } from "./Pickers"

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
  const { catalog, catalogUpdated, reloadCatalog, draft, toast } = useStore()
  const { back } = useNav()
  const upd = useListsUpdate()
  const [q, setQ] = useState("")
  const [local, setLocal] = useState<Record<string, boolean>>({})
  const usedBy = useMemo(() => {
    const m = new Map<string, string[]>()
    for (const c of draft?.channels ?? []) for (const id of c.match.lists ?? []) m.set(id, [...(m.get(id) ?? []), c.name])
    return m
  }, [draft])
  const s = q.trim().toLowerCase()
  const items = (catalog ?? []).filter((e) => !s || e.name.toLowerCase().includes(s) || e.id.includes(s))
  const groups = new Map<string, typeof items>()
  for (const e of items) {
    const k = e.category || "Прочее"
    groups.set(k, [...(groups.get(k) ?? []), e])
  }
  const selected = (catalog ?? []).filter((e) => local[e.id] ?? e.selected).length
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
          <KV k="обновлены" v={fmtAgo(catalogUpdated)} />
          <Button tone="secondary" full icon={<Icon name="refresh" />} busy={upd.busy} onClick={upd.start}>
            {upd.busy ? "Скачиваем…" : "Обновить списки"}
          </Button>
        </Card>
        <Search value={q} onChange={setQ} placeholder="Найти список" />
        {!catalog ? <Skeleton height={56} count={6} /> : null}
        {catalog && items.length === 0 ? <Empty icon="search" text="Ничего не нашлось." /> : null}
        {[...groups.entries()].map(([cat, list]) => (
          <Card key={cat} style={col("var(--an-space-1)")}>
            <CardHead title={cat} />
            {list.map((e) => {
              const cur = local[e.id] ?? e.selected
              const used = usedBy.get(e.id)
              return (
                <PickRow
                  key={e.id}
                  on={cur}
                  onClick={() => void toggle(e.id, cur)}
                  title={e.name}
                  sub={used ? `в правилах: ${used.join(", ")}` : e.description}
                  meta={e.count != null ? `${e.kind === "prefixes" ? "подсетей" : "доменов"}: ${fmtInt(e.count)}` : undefined}
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

const splitLines = (s: string) =>
  s
    .split(/[\s,]+/)
    .map((x) => x.trim())
    .filter(Boolean)

function CustomPage() {
  const { back } = useNav()
  const { toast, draft } = useStore()
  const [items, setItems] = useState<CustomList[] | null>(null)
  const [edit, setEdit] = useState<{ orig: string | null; name: string; domains: string; prefixes: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const load = () =>
    call("lists.custom")
      .then((r) => setItems(Array.isArray(r) ? r : []))
      .catch((e) => {
        setItems([])
        toast(errorText(e), "bad")
      })
  useEffect(() => {
    void load()
  }, [])

  const usedBy = (name: string) => (draft?.channels ?? []).filter((c) => c.match.custom?.includes(name)).map((c) => c.name)

  const save = async () => {
    if (!edit) return
    setBusy(true)
    try {
      await call("lists.custom", { put: { name: edit.name.trim(), domains: splitLines(edit.domains), prefixes: splitLines(edit.prefixes) } })
      if (edit.orig && edit.orig !== edit.name.trim()) await call("lists.custom", { remove: edit.orig })
      toast("Список сохранён")
      setEdit(null)
      await load()
    } catch (e) {
      toast(errorText(e), "bad")
    } finally {
      setBusy(false)
    }
  }
  const remove = async () => {
    if (!edit?.orig) return
    setConfirm(false)
    try {
      await call("lists.custom", { remove: edit.orig })
      setEdit(null)
      await load()
    } catch (e) {
      toast(errorText(e), "bad")
    }
  }

  if (edit) {
    const nameErr = !edit.name.trim() ? "Нужно название" : items?.some((c) => c.name === edit.name.trim() && c.name !== edit.orig) ? "Такой список уже есть" : null
    const used = edit.orig ? usedBy(edit.orig) : []
    return (
      <>
        <Header title={edit.orig ?? "Новый список"} back={() => setEdit(null)} />
        <Body>
          <Card style={col("var(--an-space-6)")}>
            <Field label="Название" error={edit.name ? nameErr : null}>
              <Input value={edit.name} onInput={(e) => setEdit({ ...edit, name: e.currentTarget.value })} />
            </Field>
            <Field label="Домены" hint="по одному в строке">
              <Textarea rows={5} value={edit.domains} placeholder="example.com" onInput={(e) => setEdit({ ...edit, domains: e.currentTarget.value })} autoCapitalize="none" spellCheck={false} />
            </Field>
            <Field label="Подсети" hint="по одной в строке">
              <Textarea rows={4} value={edit.prefixes} placeholder="203.0.113.0/24" onInput={(e) => setEdit({ ...edit, prefixes: e.currentTarget.value })} autoCapitalize="none" spellCheck={false} />
            </Field>
            {used.length ? <div style={muted}>в правилах: {used.join(", ")}</div> : null}
          </Card>
          <Button tone="primary" full busy={busy} disabled={!!nameErr} onClick={save}>
            Сохранить
          </Button>
          {edit.orig ? (
            <Button tone="danger" full icon={<Icon name="trash" />} onClick={() => setConfirm(true)}>
              Удалить список
            </Button>
          ) : null}
        </Body>
        <Dialog open={confirm} title={`Удалить «${edit.orig}»?`} confirmLabel="Удалить список" onConfirm={remove} onCancel={() => setConfirm(false)}>
          {used.length ? `Правила ${used.map((n) => `«${n}»`).join(", ")} перестанут забирать его домены и подсети.` : "Список удалится с телефона."}
        </Dialog>
      </>
    )
  }

  return (
    <>
      <Header title="Свои списки" back={back} />
      <Body>
        {!items ? <Skeleton height={56} count={3} /> : null}
        {items && items.length === 0 ? <Empty icon="file" text="Своих списков нет" /> : null}
        {items && items.length > 0 ? (
          <Card style={col("var(--an-space-1)")}>
            {items.map((c) => (
              <TapRow
                key={c.name}
                icon="file"
                title={c.name}
                subtitle={`доменов: ${c.domains.length} · подсетей: ${c.prefixes.length}`}
                onClick={() => setEdit({ orig: c.name, name: c.name, domains: c.domains.join("\n"), prefixes: c.prefixes.join("\n") })}
              />
            ))}
          </Card>
        ) : null}
        <Button tone="secondary" full icon={<Icon name="plus" />} onClick={() => setEdit({ orig: null, name: "", domains: "", prefixes: "" })}>
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
  const outs = Object.values(draft?.outputs ?? {}).filter((o) => o.sub === s.id).map((o) => o.name)
  const q = s.quota
  const used = q ? q.up + q.down : 0
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
        <h2 style={{ font: "var(--an-text-heading)", ...ellipsis }}>{s.name}</h2>
        <span style={{ ...mono, ...ellipsis, wordBreak: "normal" }}>{s.url}</span>
      </div>
      <div style={col("var(--an-space-2)")}>
        <KV k="узлов" v={s.nodes} />
        <KV k="обновлена" v={fmtAgo(s.updated)} />
        {outs.length ? <KV k="выходы" v={outs.join(", ")} /> : null}
        {q?.expire ? <KV k="действует до" v={<span style={{ color: expired ? "var(--an-danger-ink)" : undefined }}>{fmtDate(q.expire)}</span>} /> : null}
      </div>
      {q && (q.total > 0 || used > 0) ? (
        <div style={col("var(--an-space-3)")}>
          {q.total > 0 ? <Meter value={Math.min(100, (used / q.total) * 100)} tone={used / q.total > 0.9 ? "warn" : "accent"} height={8} /> : null}
          <span className="sp-tab" style={muted}>
            израсходовано: {fmtBytes(used)}
            {q.total > 0 ? ` из ${fmtBytes(q.total)}` : " · объём не ограничен"}
          </span>
        </div>
      ) : null}
      <div style={{ display: "flex", gap: "var(--an-space-3)" }}>
        <Button tone="secondary" icon={<Icon name="refresh" />} busy={busy} onClick={refresh} style={{ flex: 1 }}>
          {busy ? "Обновляем…" : "Обновить"}
        </Button>
        <Button tone="danger" icon={<Icon name="trash" />} onClick={() => setConfirm(true)} style={{ flex: 1 }}>
          Удалить
        </Button>
      </div>
      <Dialog open={confirm} title={`Удалить «${s.name}»?`} confirmLabel="Удалить подписку" onConfirm={remove} onCancel={() => setConfirm(false)}>
        {outs.length ? `Выходы ${outs.join(", ")} останутся без узлов, и правила через них перестанут работать.` : "Узлы этой подписки удалятся с телефона."}
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
    if (!/^(https?|vless):\/\//i.test(u)) {
      setErr("Нужна ссылка https://… на подписку или vless://")
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
          <Field label="Ссылка" error={err}>
            <Input mono value={url} placeholder="https://… или vless://…" onInput={(e) => setUrl(e.currentTarget.value)} invalid={!!err} autoCapitalize="none" autoCorrect="off" spellCheck={false} inputMode="url" />
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
  const { toast, setDraft } = useStore()
  const [exporting, setExporting] = useState(false)
  const [pending, setPending] = useState<{ name: string; json: string } | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const exp = async () => {
    setExporting(true)
    try {
      const r = await call("backup.export")
      toast(`Сохранено: ${r.file}`)
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
      setDraft(() => m)
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
          <div style={muted}>правила, выходы, подписки и выбранные списки</div>
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
  const { catalog, catalogUpdated, engine } = useStore()
  const [subs, setSubs] = useState<Sub[] | null>(null)
  const [custom, setCustom] = useState<number | null>(null)
  useEffect(() => {
    if (route.sub) return
    call("subs.list").then(setSubs).catch(() => {})
    call("lists.custom")
      .then((r) => setCustom(Array.isArray(r) ? r.length : null))
      .catch(() => {})
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
  return (
    <>
      <Header title="Ещё" />
      <Body>
        <Card style={col("0px")}>
          <TapRow
            icon="list"
            title="Списки"
            subtitle={catalog ? `выбрано: ${sel} из ${catalog.length} · ${catalogUpdated ? `обновлены ${fmtAgo(catalogUpdated)}` : "не скачаны"}` : undefined}
            onClick={() => open({ kind: "lists" })}
          />
          <Divider />
          <TapRow icon="file" title="Свои списки" subtitle={custom != null ? `списков: ${custom}` : undefined} onClick={() => open({ kind: "custom" })} />
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
      </Body>
    </>
  )
}

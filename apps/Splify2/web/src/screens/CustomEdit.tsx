/**
 * Свой список: имя и строки — домены и подсети вперемешку, по одной в строке. Сохраняется
 * сразу (lists.custom put), а не черновиком: правило ссылается на список по имени, и список
 * должен существовать в модели раньше, чем правило с ним уйдёт в settings.put. Строки делит
 * на домены и подсети логика (поле text) — тем же разбором, что проверяет резервные копии;
 * что не подошло ни туда, ни сюда, она считает и называет числом.
 *
 * Переименования нет: у логики его нет, а «сохранить под новым именем и удалить старое»
 * ломалось бы на списке, который уже стоит в правилах. Имя задаётся при создании.
 *
 * Открывается из «Ещё → Свои списки» (страницей) и из редактора правила (слоем поверх).
 */
import { useState } from "react"
import { Button, Card, Dialog, Field, Input, Textarea } from "@andromeda/ui"
import { call, errorText } from "../bridge"
import { useStore } from "../store"
import { Body, Header, col, muted } from "../ui"
import { Icon } from "../icons"
import { Overlay } from "./Pickers"
import type { CustomPutReply } from "../types"

const NAME_RE = /^[A-Za-z0-9_-]{1,24}$/

/** Латинское имя из строки: для заготовки из домена («rr3.googlevideo.com» → «googlevideo-com»). */
export function suggestName(text: string, taken: (n: string) => boolean): string {
  const first = text.trim().split(/\s+/)[0] ?? ""
  const host = first.replace(/^\*\./, "").split(".").slice(-2).join("-")
  let base = host.replace(/[^A-Za-z0-9_-]/g, "-").replace(/-+/g, "-").replace(/^-|-$/g, "").slice(0, 20) || "list"
  let n = base
  for (let k = 2; taken(n); k++) n = `${base}-${k}`
  return n
}

export function CustomEdit({
  orig,
  initName = "",
  initText = "",
  overlay = false,
  onSaved,
  onBack,
}: {
  /** Имя существующего списка; нет — новый. */
  orig?: string
  initName?: string
  initText?: string
  overlay?: boolean
  onSaved: (name: string) => void
  onBack: () => void
}) {
  const { custom, reloadCustom, draft, toast } = useStore()
  const [name, setName] = useState(orig ?? initName)
  const [text, setText] = useState(initText)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [confirm, setConfirm] = useState(false)
  const [tried, setTried] = useState(false)

  const n = name.trim()
  const nameErr = orig
    ? null
    : !n
      ? "Нужно название"
      : !NAME_RE.test(n)
        ? "Латиница, цифры, «-» и «_», до 24 знаков"
        : custom?.some((c) => c.name === n)
          ? "Такой список уже есть"
          : null
  const textErr = !text.trim() ? "Добавьте хотя бы один домен или подсеть" : null
  // В правилах — и в черновике, и в сохранённом: удалить список, на который ссылается ещё не
  // применённое правило, значит получить отказ при «Применить».
  const usedBy = orig ? (draft?.channels ?? []).filter((c) => c.what.custom.includes(orig)).map((c) => c.name) : []

  const save = async () => {
    setTried(true)
    if (nameErr || textErr) return
    setBusy(true)
    setErr(null)
    try {
      const r = (await call("lists.custom", { put: { name: n, text } })) as CustomPutReply
      await reloadCustom()
      const parts = [r.domains ? `доменов: ${r.domains}` : null, r.prefixes ? `подсетей: ${r.prefixes}` : null].filter(Boolean).join(" · ")
      if (r.dropped) toast(`Сохранено · ${parts} · не подошло строк: ${r.dropped}`, "warn")
      else toast(`Сохранено · ${parts}`)
      onSaved(n)
    } catch (e) {
      setErr(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  const remove = async () => {
    if (!orig) return
    setConfirm(false)
    try {
      await call("lists.custom", { remove: orig })
      await reloadCustom()
      toast("Список удалён")
      onBack()
    } catch (e) {
      setErr(errorText(e))
    }
  }

  const body = (
    <>
      <Card style={col("var(--an-space-6)")}>
        {!orig ? (
          <Field label="Название" hint="латиница, цифры, «-» и «_»" error={tried ? nameErr : null}>
            <Input value={name} placeholder="games" onInput={(e) => setName(e.currentTarget.value)} invalid={tried && !!nameErr} autoCapitalize="none" autoCorrect="off" spellCheck={false} />
          </Field>
        ) : null}
        <Field label="Домены и подсети" hint="по одному в строке" error={tried ? textErr : null}>
          <Textarea rows={8} value={text} placeholder={"example.com\n*.example.org\n203.0.113.0/24"} onInput={(e) => setText(e.currentTarget.value)} autoCapitalize="none" autoCorrect="off" spellCheck={false} />
        </Field>
        {usedBy.length ? <div style={muted}>в правилах: {usedBy.join(", ")}</div> : null}
        {err ? <div style={{ font: "var(--an-text-body-sm)", color: "var(--an-danger-ink)" }}>{err}</div> : null}
      </Card>
      <Button tone="primary" full busy={busy} onClick={save}>
        {orig ? "Сохранить" : "Создать список"}
      </Button>
      {orig ? (
        <Button tone="danger" full icon={<Icon name="trash" />} disabled={usedBy.length > 0} onClick={() => setConfirm(true)}>
          Удалить список
        </Button>
      ) : null}
      {orig && usedBy.length ? <div style={muted}>Чтобы удалить список, уберите его из правил.</div> : null}
      <Dialog open={confirm} title={`Удалить «${orig}»?`} confirmLabel="Удалить список" onConfirm={remove} onCancel={() => setConfirm(false)}>
        Список удалится с телефона.
      </Dialog>
    </>
  )

  const title = orig ?? "Новый список"
  if (overlay)
    return (
      <Overlay title={title} onBack={onBack}>
        {body}
      </Overlay>
    )
  return (
    <>
      <Header title={title} back={onBack} />
      <Body>{body}</Body>
    </>
  )
}

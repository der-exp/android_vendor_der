/** Приложения телефона (apps.list) — один запрос на запуск страницы, с системными:
 *  в правиле может стоять и системное приложение. */
import { useEffect, useState } from "react"
import { call } from "./bridge"
import type { AppInfo } from "./types"

let appsCache: Promise<AppInfo[]> | null = null

/** Все приложения, включая системные: в правиле может стоять и системное. */
export function loadApps(): Promise<AppInfo[]> {
  appsCache ??= call("apps.list", { system: true }).catch((e) => {
    appsCache = null
    throw e
  })
  return appsCache
}

export function useAppLabels(): [(uid: number) => string, AppInfo[] | null] {
  const [apps, setApps] = useState<AppInfo[] | null>(null)
  useEffect(() => {
    loadApps().then(setApps).catch(() => setApps([]))
  }, [])
  const by = new Map((apps ?? []).map((a) => [a.uid, a]))
  return [(uid: number) => by.get(uid)?.label ?? "неизвестное приложение", apps]
}

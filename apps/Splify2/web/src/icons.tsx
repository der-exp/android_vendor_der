/**
 * Глифы Lucide (обводка 2 px, скруглённые концы), выписанные путями: страница живёт без
 * сети, и пакет иконок ради двух десятков глифов не нужен. Часть путей — те же, что в
 * ui_kits/console/Icon.tsx дизайн-системы, чтобы разделы назывались теми же картинками,
 * что в консоли на роутере.
 */
import type { CSSProperties } from "react"

const PATHS = {
  home: "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6M3 12a9 9 0 0 1 18 0",
  outputs: "M6 3v12M18 9v12M6 15a6 6 0 0 0 12-6",
  rules: "M4 6h6l4 12h6",
  conns: "M22 12h-4l-3 9L9 3l-3 9H2",
  more: "M4 6h16M4 12h16M4 18h16",
  plus: "M12 5v14M5 12h14",
  pencil: "m18 2 4 4-14 14H4v-4z",
  up: "M12 19V5M5 12l7-7 7 7",
  down: "M12 5v14M5 12l7 7 7-7",
  trash: "M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6",
  search: "M21 21l-4.3-4.3M11 19a8 8 0 1 1 0-16 8 8 0 0 1 0 16",
  refresh: "M21 12a9 9 0 1 1-2.6-6.4M21 3v6h-6",
  download: "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3",
  upload: "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12",
  chevron: "m9 18 6-6-6-6",
  x: "M18 6 6 18M6 6l12 12",
  check: "M20 6 9 17l-5-5",
  wifi: "M12 20h.01M2 8.82a15 15 0 0 1 20 0M5 12.86a10 10 0 0 1 14 0M8.5 16.43a5 5 0 0 1 7 0",
  cell: "M2 20h.01M7 20v-4M12 20v-8M17 20V8M22 4v16",
  ethernet: "M3 7h18v10H3zM7 17v3M12 17v3M17 17v3",
  offline: "M12 20h.01M8.5 16.43a5 5 0 0 1 7 0M2 2l20 20M5 12.86a10 10 0 0 1 5.17-2.69M19 12.86a10 10 0 0 0-2.01-1.49M2 8.82a15 15 0 0 1 4.18-2.65M10.66 5a15 15 0 0 1 11.34 3.82",
  phone: "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zM12 18h.01",
  apps: "M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z",
  tether: "M4.9 19.1C1 15.2 1 8.8 4.9 4.9M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.5M16.2 7.8c2.3 2.3 2.3 6.1 0 8.5M19.1 4.9C23 8.8 23 15.1 19.1 19M12 12h.01",
  shield: "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
  zap: "M13 2 3 14h9l-1 8 10-12h-9l1-8z",
  list: "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01",
  file: "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M9 13h6M9 17h6",
  link: "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
  archive: "M21 8v13H3V8M1 3h22v5H1zM10 12h4",
  power: "M18.4 6.6a9 9 0 1 1-12.8 0M12 2v10",
  diag: "M4 3v7a5 5 0 0 0 10 0V3M9 15v2a4 4 0 0 0 8 0v-1",
} as const

export type IconName = keyof typeof PATHS

export function Icon({ name, size = 16, style }: { name: IconName; size?: number; style?: CSSProperties }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      style={{ display: "block", flex: "0 0 auto", ...style }}
    >
      <path d={PATHS[name]} />
    </svg>
  )
}

import { useEffect, useState } from 'react'
export type Theme = 'light' | 'dark'
const KEY = 'dm7-console-theme'
function initialTheme(): Theme { const saved = localStorage.getItem(KEY); if (saved === 'light' || saved === 'dark') return saved; return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light' }
export function useTheme() {
  const [theme, setTheme] = useState<Theme>(initialTheme)
  useEffect(() => { document.documentElement.dataset.theme = theme; document.documentElement.style.colorScheme = theme }, [theme])
  const toggle = () => setTheme((current) => { const next = current === 'dark' ? 'light' : 'dark'; localStorage.setItem(KEY, next); return next })
  return { theme, setTheme: (next: Theme) => { localStorage.setItem(KEY, next); setTheme(next) }, toggle }
}

import { useEffect, useState } from 'react'
export function usePathname() {
  const [path, setPath] = useState(location.pathname)
  useEffect(() => { const update = () => setPath(location.pathname); addEventListener('popstate', update); return () => removeEventListener('popstate', update) }, [])
  const navigate = (event: React.MouseEvent<HTMLAnchorElement>, to: string) => { if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return; event.preventDefault(); history.pushState(null, '', to); setPath(to) }
  return { path, navigate }
}

import type { ReactNode } from 'react'

export function PageHeader({
  title,
  action,
}: {
  title: string
  action?: ReactNode
}) {
  return (
    <header className="page-header">
      <h1 className="page-header-title">{title}</h1>
      {action ? <div className="page-header-action">{action}</div> : null}
    </header>
  )
}

import type { ReactNode } from 'react';

interface AlertProps {
  kind: 'error' | 'success' | 'info';
  children: ReactNode;
}

export default function Alert({ kind, children }: AlertProps) {
  return <div className={`alert alert-${kind}`}>{children}</div>;
}

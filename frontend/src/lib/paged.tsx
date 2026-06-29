import { useState } from 'react';
import { useApi } from './format';
import { qs } from '../api/client';

export interface PageEnvelope<T> {
  content: T[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}

interface PagedResult<T> {
  data: PageEnvelope<T> | null;
  error: ReturnType<typeof useApi>['error'];
  loading: boolean;
  page: number;
  setPage: (p: number) => void;
  reload: () => void;
}

/** Drive a paged admin endpoint. `base` is the path; `filters` are merged into
 * the querystring alongside page/size. Changing filters resets to page 0 via deps. */
export function usePaged<T = any>(base: string, filters: Record<string, unknown> = {}, size = 20): PagedResult<T> {
  const [page, setPage] = useState(0);
  const filterKey = JSON.stringify(filters);
  const path = base + qs({ ...filters, page, size });
  const res = useApi<PageEnvelope<T>>(path, [page, filterKey]);
  return { data: res.data, error: res.error, loading: res.loading, page, setPage, reload: res.reload };
}

export function Pager({ page, totalPages, total, onPage }: { page: number; totalPages: number; total: number; onPage: (p: number) => void }) {
  if (total === 0) return null;
  return (
    <div className="pager">
      <span className="faint mono">{total} mục · trang {page + 1}/{Math.max(totalPages, 1)}</span>
      <button className="btn btn-ghost btn-sm" disabled={page <= 0} onClick={() => onPage(page - 1)}>← Trước</button>
      <button className="btn btn-ghost btn-sm" disabled={page >= totalPages - 1} onClick={() => onPage(page + 1)}>Sau →</button>
    </div>
  );
}

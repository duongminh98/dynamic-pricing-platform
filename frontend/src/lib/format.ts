import { useEffect, useRef, useState } from 'react';
import { apiFetch, FetchOpts, ApiError } from '../api/client';

/* ---------- formatting ---------- */

const vnd = new Intl.NumberFormat('vi-VN');

/** Format a VND amount as a grouped integer (no decimals). */
export function money(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  if (!isFinite(n)) return '—';
  return vnd.format(Math.round(n));
}

/** Money with the ₫ unit appended. */
export function vndLabel(v: number | string | null | undefined): string {
  const m = money(v);
  return m === '—' ? m : m + ' ₫';
}

const dtFull = new Intl.DateTimeFormat('vi-VN', {
  day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
});
const dtDate = new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' });

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '—' : dtFull.format(d);
}
export function dateOnly(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '—' : dtDate.format(d);
}

/** Relative "x ago" for notification timestamps. */
export function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '';
  const then = new Date(iso).getTime();
  if (isNaN(then)) return '';
  const s = Math.floor((Date.now() - then) / 1000);
  if (s < 60) return 'vừa xong';
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} phút trước`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} giờ trước`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d} ngày trước`;
  return dateOnly(iso);
}

/** Title-case a snake_case enum / machine key for display. */
export function humanize(s: string | null | undefined): string {
  if (!s) return '';
  return s.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}

/* ---------- async data hook ---------- */

interface UseApiState<T> {
  data: T | null;
  error: ApiError | null;
  loading: boolean;
  reload: () => void;
}

/** GET a path on mount (and whenever a dep changes), with loading/error state. */
export function useApi<T = any>(path: string | null, deps: unknown[] = []): UseApiState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(!!path);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!path) {
      setLoading(false);
      return;
    }
    let alive = true;
    setLoading(true);
    setError(null);
    apiFetch<T>(path)
      .then((d) => alive && setData(d))
      .catch((e) => alive && setError(e instanceof ApiError ? e : new ApiError(0, 'NETWORK', String(e), null, null)))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, tick, ...deps]);

  return { data, error, loading, reload: () => setTick((t) => t + 1) };
}

/* ---------- mutation helper ---------- */

interface MutState {
  run: <T = any>(path: string, opts?: FetchOpts) => Promise<T>;
  busy: boolean;
  error: ApiError | null;
  setError: (e: ApiError | null) => void;
}

/** Wraps a one-shot write (POST/PUT/PATCH) with busy + error envelope state. */
export function useMutation(): MutState {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const run = async <T = any>(path: string, opts: FetchOpts = {}): Promise<T> => {
    setBusy(true);
    setError(null);
    try {
      return await apiFetch<T>(path, opts);
    } catch (e) {
      const err = e instanceof ApiError ? e : new ApiError(0, 'NETWORK', String(e), null, null);
      setError(err);
      throw err;
    } finally {
      setBusy(false);
    }
  };
  return { run, busy, error, setError };
}

/* ---------- polling interval ---------- */

export function useInterval(cb: () => void, ms: number | null) {
  const saved = useRef(cb);
  saved.current = cb;
  useEffect(() => {
    if (ms === null) return;
    const id = setInterval(() => saved.current(), ms);
    return () => clearInterval(id);
  }, [ms]);
}

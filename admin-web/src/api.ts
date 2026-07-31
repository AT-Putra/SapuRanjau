// Satu tempat semua panggilan panel keluar (T-041, ADR-0013).
//
// Dua hal yang WAJIB ada di setiap permintaan dan gampang lupa kalau ditulis per-layar:
//   1. `credentials: 'include'` — sesi ada di cookie HttpOnly; JS tak pernah memegang tokennya.
//   2. header `X-Requested-With` — lapis kedua anti-CSRF; AdminSessionFilter menolak method non-GET
//      tanpa itu dengan 403.
export const ADMIN_API = '/admin/api';

export const CSRF_HEADER: [string, string] = ['X-Requested-With', 'XMLHttpRequest'];

// Server menjawab RFC 7807 (`{detail, code, status}`) — pesan yang berguna ada di `detail`.
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    message: string,
  ) {
    super(message);
  }
}

export const call = async <T>(path: string, body?: unknown): Promise<T> => {
  const res = await fetch(`${ADMIN_API}${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', [CSRF_HEADER[0]]: CSRF_HEADER[1] },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const teks = await res.text();
  const isi = teks ? JSON.parse(teks) : {};
  if (!res.ok) throw new ApiError(res.status, isi.code, isi.detail ?? res.statusText);
  return isi as T;
};

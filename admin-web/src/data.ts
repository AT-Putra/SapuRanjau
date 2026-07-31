import { fetchUtils, HttpError } from 'react-admin';
import simpleRestProvider from 'ra-data-simple-rest';
import { ADMIN_API, CSRF_HEADER } from './api';

const httpClient = (url: string, options: fetchUtils.Options = {}) => {
  const headers = new Headers(options.headers ?? { Accept: 'application/json' });
  headers.set(CSRF_HEADER[0], CSRF_HEADER[1]);
  return fetchUtils
    .fetchJson(url, { ...options, credentials: 'include', headers })
    .catch((e: HttpError) => {
      // `fetchJson` mencari `message` di body, server menulis `detail` (RFC 7807, ADR-0035) → tanpa
      // pemetaan ini setiap penolakan tampil sebagai teks status HTTP dan alasan sebenarnya hilang.
      throw new HttpError(e.body?.detail ?? e.message, e.status, e.body);
    });
};

// Data provider TIDAK ditulis tangan (ADR-0013): server sudah bicara `range`/`sort` + `Content-Range`
// (RaQuery, T-040), jadi yang tersisa cuma menyisipkan cookie + header CSRF di atas.
export const dataProvider = simpleRestProvider(ADMIN_API, httpClient);

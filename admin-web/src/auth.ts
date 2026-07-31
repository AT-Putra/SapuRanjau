import type { AuthProvider } from 'react-admin';
import { call } from './api';

type Me = { id: string; username: string; role: string };
type LoginResponse = { status: 'OK' | 'TOTP_SETUP_REQUIRED'; secret?: string; otpauthUri?: string };

// Enrolment 2FA bukan kegagalan login — ia langkah berikutnya. Tapi `authProvider.login` cuma punya
// dua jawaban (resolve = masuk, reject = tetap di halaman login), jadi langkah itu dibawa sebagai
// penolakan bertipe sendiri yang dikenali LoginPage. Resolve akan membuat react-admin mengalihkan
// operator ke panel padahal sesinya belum terautentikasi sama sekali.
export class SetupTotpError extends Error {
  constructor(
    readonly secret: string,
    readonly otpauthUri: string,
  ) {
    super('Akun ini belum punya 2FA — daftarkan authenticator dulu.');
  }
}

export type LoginParams = { username?: string; password?: string; code?: string; enroll?: boolean };

export const authProvider: AuthProvider = {
  async login(params: LoginParams) {
    const hasil = params.enroll
      ? await call<LoginResponse>('/totp/enroll', { code: params.code })
      : await call<LoginResponse>('/login', {
          username: params.username,
          password: params.password,
          code: params.code || null,
        });
    if (hasil.status === 'TOTP_SETUP_REQUIRED') {
      throw new SetupTotpError(hasil.secret!, hasil.otpauthUri!);
    }
  },

  async logout() {
    // Sesi yang sudah mati membalas 401; itu bukan alasan untuk menahan operator di panel.
    await call('/logout', {}).catch(() => undefined);
  },

  // Tanpa cache: kebenarannya ada di cookie yang tak bisa dibaca JS, jadi satu-satunya cara tahu
  // sesi masih hidup adalah bertanya. Operatornya 1–3 orang — GET /me per pindah halaman tak
  // pernah menjadi beban, dan cache akan membuat sesi kedaluwarsa tampak masih valid.
  async checkAuth() {
    await call<Me>('/me');
  },

  async checkError(error: { status?: number }) {
    if (error?.status === 401) throw new Error('Sesi berakhir — masuk lagi.');
    // 403 = peran tak berwenang (RBAC) atau header CSRF hilang. Melempar di sini akan MENGELUARKAN
    // operator dari panel karena satu tombol yang bukan haknya; biarkan react-admin menampilkan
    // pesannya saja.
  },

  async getIdentity() {
    const me = await call<Me>('/me');
    return { id: me.id, fullName: `${me.username} · ${me.role}` };
  },

  async getPermissions() {
    return call<Me>('/me')
      .then((me) => me.role)
      .catch(() => undefined);
  },
};

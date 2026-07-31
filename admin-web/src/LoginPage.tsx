import { useState } from 'react';
import type { FormEvent } from 'react';
import { Alert, Box, Button, TextField, Typography } from '@mui/material';
import { Login, useLogin, useNotify } from 'react-admin';
import { SetupTotpError } from './auth';

// Halaman masuk panel (T-041). Dua langkah, bukan dua halaman: akun yang belum punya 2FA menerima
// secret-nya sebagai jawaban login pertama (ADR-0013 / T-040), lalu form yang sama berganti menjadi
// kotak konfirmasi kode.
export const LoginPage = () => {
  const login = useLogin();
  const notify = useNotify();
  const [nama, setNama] = useState('');
  const [sandi, setSandi] = useState('');
  const [kode, setKode] = useState('');
  const [setup, setSetup] = useState<SetupTotpError | null>(null);
  const [sibuk, setSibuk] = useState(false);

  const kirim = async (e: FormEvent) => {
    e.preventDefault();
    setSibuk(true);
    try {
      await login(setup ? { enroll: true, code: kode } : { username: nama, password: sandi, code: kode });
    } catch (err) {
      if (err instanceof SetupTotpError) {
        setSetup(err);
        setKode('');
      } else {
        // Pesan server ditampilkan apa adanya: ia sudah dibuat untuk dibaca manusia dan sengaja
        // tidak membedakan username salah dari password salah (T-040).
        notify((err as Error).message, { type: 'error' });
      }
    } finally {
      setSibuk(false);
    }
  };

  return (
    <Login>
      <Box component="form" onSubmit={kirim} sx={{ p: 2, display: 'grid', gap: 2 }}>
        {setup ? (
          <>
            <Alert severity="info">
              Ketik kunci ini di aplikasi authenticator (Google Authenticator, Aegis, 1Password),
              lalu masukkan kode 6 digit yang muncul. Kunci hanya ditampilkan sekali.
            </Alert>
            <Typography variant="h6" sx={{ fontFamily: 'monospace', wordBreak: 'break-all', userSelect: 'all' }}>
              {setup.secret}
            </Typography>
            <Typography variant="caption" sx={{ wordBreak: 'break-all' }}>
              {setup.otpauthUri}
            </Typography>
          </>
        ) : (
          <>
            <TextField
              label="Username"
              value={nama}
              onChange={(e) => setNama(e.target.value)}
              autoFocus
              autoComplete="username"
            />
            <TextField
              label="Password"
              type="password"
              value={sandi}
              onChange={(e) => setSandi(e.target.value)}
              autoComplete="current-password"
            />
          </>
        )}
        <TextField
          label={setup ? 'Kode dari authenticator' : 'Kode 2FA'}
          value={kode}
          onChange={(e) => setKode(e.target.value.replace(/\D/g, '').slice(0, 6))}
          // `one-time-code` = pengelola sandi menawarkan kode, bukan menyimpannya sebagai password.
          autoComplete="one-time-code"
          inputMode="numeric"
          helperText={setup ? undefined : 'Kosongkan saat login pertama kali.'}
        />
        <Button type="submit" variant="contained" disabled={sibuk}>
          {setup ? 'Daftarkan & masuk' : 'Masuk'}
        </Button>
      </Box>
    </Login>
  );
};

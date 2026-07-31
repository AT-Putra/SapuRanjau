import { useState } from 'react';
import type { FormEvent } from 'react';
import { Alert, Box, Button, TextField, Typography } from '@mui/material';
import { Login, useLogin, useNotify } from 'react-admin';
import { renderSVG } from 'uqr';
import { SetupTotpError } from './auth';

// QR dipasang sebagai <img src="data:..."> alih-alih menyuntikkan SVG ke DOM: gambar yang dimuat
// lewat `src` tak bisa menjalankan script apa pun, jadi tak ada jalur `dangerouslySetInnerHTML`
// yang harus dipercaya. Kunci base32-nya TETAP ditampilkan di bawahnya — kamera rusak, aplikasi
// yang cuma menerima ketikan, atau operator yang membuka panel dari HP-nya sendiri tak boleh
// kehilangan satu-satunya jalan mendaftarkan 2FA.
const qrDataUri = (uri: string) => `data:image/svg+xml;utf8,${encodeURIComponent(renderSVG(uri, { border: 2 }))}`;

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
              Pindai kode ini dengan aplikasi authenticator (Google Authenticator, Aegis,
              1Password), lalu masukkan kode 6 digit yang muncul. Hanya ditampilkan sekali.
            </Alert>
            <Box
              component="img"
              src={qrDataUri(setup.otpauthUri)}
              alt="Kode QR pendaftaran 2FA — bisa juga diketik manual memakai kunci di bawahnya"
              // Latar putih wajib: QR gelap-di-atas-terang adalah asumsi tiap pemindai, dan kartu
              // login react-admin bisa bertema gelap.
              sx={{ width: 220, height: 220, justifySelf: 'center', bgcolor: '#fff', p: 1, borderRadius: 1 }}
            />
            <Typography variant="caption">Tak bisa memindai? Ketik kunci ini:</Typography>
            <Typography variant="body2" sx={{ fontFamily: 'monospace', wordBreak: 'break-all', userSelect: 'all' }}>
              {setup.secret}
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

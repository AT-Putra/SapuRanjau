import { useState } from 'react';
import {
  Button as MuiButton,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  TextField,
} from '@mui/material';
import { Button, useNotify, useRecordContext, useRefresh } from 'react-admin';
import { call } from './api';

// Tombol untuk endpoint KATA KERJA (gugurkan, kirim pesan, ampuni, tutup periode) — aksi yang
// sengaja bukan `PUT` resource karena masing-masing memicu aturan domain yang wajib ter-audit
// (ADR-0013). Satu komponen untuk semuanya: yang berbeda cuma judul dan ada-tidaknya satu field.
//
// `field` diisi = dialog menuntut teks (alasan/pesan) dan tombol kirim mati selama kosong — alasan
// wajib itu aturan ADR-0021/ADR-0025, jadi UI-nya tak boleh menawarkan jalan mengirim tanpa alasan.
export const AksiDialog = ({
  label,
  judul,
  keterangan,
  path,
  field,
  multiline = false,
  disabled = false,
}: {
  label: string;
  judul: string;
  keterangan?: string;
  path: (id: string) => string;
  field?: string;
  multiline?: boolean;
  disabled?: boolean;
}) => {
  const record = useRecordContext();
  const notify = useNotify();
  const refresh = useRefresh();
  const [buka, setBuka] = useState(false);
  const [teks, setTeks] = useState('');
  const [sibuk, setSibuk] = useState(false);

  if (!record) return null;

  const kirim = async () => {
    setSibuk(true);
    try {
      await call(path(String(record.id)), field ? { [field]: teks } : {});
      notify(`${label}: berhasil`, { type: 'info' });
      setBuka(false);
      setTeks('');
      refresh();
    } catch (e) {
      notify((e as Error).message, { type: 'error' });
    } finally {
      setSibuk(false);
    }
  };

  return (
    <>
      <Button label={label} onClick={() => setBuka(true)} disabled={disabled} />
      <Dialog open={buka} onClose={() => setBuka(false)} fullWidth maxWidth="sm">
        <DialogTitle>{judul}</DialogTitle>
        <DialogContent>
          {keterangan && <DialogContentText sx={{ mb: 2 }}>{keterangan}</DialogContentText>}
          {field && (
            <TextField
              autoFocus
              fullWidth
              multiline={multiline}
              minRows={multiline ? 3 : 1}
              value={teks}
              onChange={(e) => setTeks(e.target.value)}
              label={field === 'body' ? 'Isi pesan' : 'Alasan (wajib, tercatat di audit)'}
            />
          )}
        </DialogContent>
        <DialogActions>
          <MuiButton onClick={() => setBuka(false)}>Batal</MuiButton>
          <MuiButton onClick={kirim} disabled={sibuk || (!!field && !teks.trim())} variant="contained">
            Kirim
          </MuiButton>
        </DialogActions>
      </Dialog>
    </>
  );
};

import { useState } from 'react';
import { Alert, Box, Dialog, DialogContent, DialogTitle, Typography } from '@mui/material';
import {
  Button,
  Datagrid,
  DateField,
  FunctionField,
  List,
  NumberField,
  ReferenceInput,
  SelectInput,
  TextField,
  usePermissions,
  useNotify,
  useRecordContext,
  useRefresh,
} from 'react-admin';
import { AksiDialog } from './actions';
import { call, ADMIN_API, CSRF_HEADER } from './api';

// Pemenang per periode (ADR-0021). Layar inilah yang membuat inbox & klaim pemain berhenti kosong:
// pesan hanya bisa lahir dari admin (`message.admin_id NOT NULL`).
//
// PII klaim TIDAK ikut di daftar — ia diminta per-pemenang, hanya oleh `admin`/`finance`, dan tiap
// pembacaannya menulis audit (ADR-0020). Daftar ini dibuka tiap hari; PII di dalamnya akan berakhir
// di cache browser, ekspor CSV, dan screenshot rapat.

const filters = [
  <ReferenceInput key="p" source="periodId" reference="periods" alwaysOn>
    <SelectInput label="Periode" optionText={(r) => r.name || `Periode #${r.id}`} />
  </ReferenceInput>,
];

type Klaim = {
  status: string;
  phone: string;
  ewallet?: string | null;
  address?: string | null;
  prizeValue?: string | null;
  paidAt?: string | null;
};

const KlaimPii = () => {
  const record = useRecordContext();
  const notify = useNotify();
  const refresh = useRefresh();
  const [klaim, setKlaim] = useState<Klaim | null>(null);
  const [sibuk, setSibuk] = useState(false);

  if (!record) return null;

  const buka = async () => {
    setSibuk(true);
    try {
      // GET biasa (bukan `call`, yang selalu POST bila ada body) — pembacaannya tetap berjejak di
      // server, bukan di sini.
      const res = await fetch(`${ADMIN_API}/winners/${record.id}/claim`, {
        credentials: 'include',
        headers: { [CSRF_HEADER[0]]: CSRF_HEADER[1] },
      });
      const isi = await res.json();
      if (!res.ok) throw new Error(isi.detail ?? res.statusText);
      setKlaim(isi);
    } catch (e) {
      notify((e as Error).message, { type: 'error' });
    } finally {
      setSibuk(false);
    }
  };

  const tandai = async (status: 'verified' | 'paid') => {
    try {
      await call(`/winners/${record.id}/claim/status`, { status });
      notify(`Klaim ditandai ${status}`, { type: 'info' });
      setKlaim(null);
      refresh();
    } catch (e) {
      notify((e as Error).message, { type: 'error' });
    }
  };

  return (
    <>
      <Button label="Data klaim" onClick={buka} disabled={sibuk} />
      <Dialog open={!!klaim} onClose={() => setKlaim(null)} fullWidth maxWidth="sm">
        <DialogTitle>Data klaim hadiah</DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mb: 2 }}>
            Data pribadi pemenang. Pembukaan halaman ini tercatat di audit (ADR-0020). Jangan
            disalin ke luar sistem lebih dari yang dibutuhkan untuk membayar.
          </Alert>
          <Box sx={{ display: 'grid', gap: 1 }}>
            <Typography>
              <b>Status:</b> {klaim?.status}
            </Typography>
            <Typography>
              <b>No. HP:</b> {klaim?.phone}
            </Typography>
            <Typography>
              <b>E-wallet:</b> {klaim?.ewallet || '—'}
            </Typography>
            <Typography>
              <b>Alamat:</b> {klaim?.address || '—'}
            </Typography>
            <Typography>
              <b>Nilai hadiah:</b> {klaim?.prizeValue || '—'}
            </Typography>
          </Box>
          <Box sx={{ mt: 2, display: 'flex', gap: 1 }}>
            <Button label="Tandai terverifikasi" onClick={() => tandai('verified')} disabled={klaim?.status !== 'pending'} />
            <Button label="Tandai lunas" onClick={() => tandai('paid')} disabled={klaim?.status === 'paid'} />
          </Box>
        </DialogContent>
      </Dialog>
    </>
  );
};

const Aksi = () => {
  const { permissions } = usePermissions();
  const record = useRecordContext();
  const gugur = record?.status === 'disqualified';
  return (
    <Box sx={{ display: 'flex', gap: 1 }}>
      {(permissions === 'admin' || permissions === 'moderator') && (
        <>
          <AksiDialog
            label="Kirim pesan"
            judul="Kirim pesan ke inbox pemenang"
            keterangan="Pesan tampil di inbox pemain. Tak ada notifikasi push (ditunda), jadi tulis selengkap yang perlu dibaca."
            path={(id) => `/winners/${id}/message`}
            field="body"
            multiline
          />
          <AksiDialog
            label="Gugurkan"
            judul="Gugurkan pemenang ini?"
            keterangan="Peringkat di bawahnya naik satu tingkat dan kandidat berikutnya masuk. Alasan wajib dan tercatat permanen di audit (ADR-0021)."
            path={(id) => `/winners/${id}/disqualify`}
            field="reason"
            disabled={gugur}
          />
        </>
      )}
      {(permissions === 'admin' || permissions === 'finance') && <KlaimPii />}
    </Box>
  );
};

export const WinnerList = () => (
  <List filters={filters} sort={{ field: 'rank', order: 'ASC' }}>
    <Datagrid bulkActionButtons={false} rowClick={false}>
      <NumberField source="rank" label="Peringkat" />
      <TextField source="displayName" label="Pemain" sortable={false} />
      <NumberField source="totalScore" label="Skor" sortable={false} />
      <TextField source="prize" label="Hadiah" sortable={false} emptyText="—" />
      <TextField source="status" label="Status" />
      <FunctionField
        label="Klaim"
        sortable={false}
        render={(r: { claimStatus?: string }) => r.claimStatus ?? 'belum diisi'}
      />
      <TextField source="disqualifyReason" label="Alasan gugur" sortable={false} emptyText="—" />
      <DateField source="createdAt" label="Ditetapkan" showTime sortBy="created_at" />
      <Aksi />
    </Datagrid>
  </List>
);

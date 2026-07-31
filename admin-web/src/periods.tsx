import { Alert, Chip } from '@mui/material';
import {
  Create,
  Datagrid,
  DateField,
  DateTimeInput,
  Edit,
  List,
  NumberField,
  SimpleForm,
  TextField,
  TextInput,
  TopToolbar,
  required,
  useRecordContext,
} from 'react-admin';
import { AksiDialog } from './actions';

// Periode turnamen (ADR-0021). Satu periode ACTIVE dijaga DB (`one_active_period`), tumpang-tindih
// & perubahan jadwal dijaga PeriodService — layar ini tak mengulang aturannya, ia hanya menampilkan
// akibatnya lebih awal supaya operator tak menabraknya lewat pesan error.

// ponytail: ambang peringatan "periode terlalu panjang untuk jumlah levelnya" (ADR-0024) = 2 hari
// per level, angka pilihan sendiri tanpa data playtest. Naikkan/turunkan setelah periode pertama
// yang sungguhan berjalan; kalau kelak perlu berbeda per-periode, ia pindah jadi properti server.
const HARI_PER_LEVEL = 2;

const hari = (r: { startsAt: string; endsAt: string }) =>
  Math.max(1, Math.round((Date.parse(r.endsAt) - Date.parse(r.startsAt)) / 86_400_000));

const Peringatan = () => {
  const r = useRecordContext();
  if (!r) return null;
  const pesan: string[] = [];
  // Urutan sengaja: yang membuat periode TAK BISA DIMAINKAN dulu, baru yang cuma kurang ideal.
  if (r.levelCount === 0) pesan.push('Belum ada level — pemain akan melihat turnamen kosong.');
  if (!r.hasPrizeConfig) pesan.push('Belum ada konfigurasi hadiah — periode ini akan berakhir tanpa pemenang.');
  if (r.levelCount > 0 && hari(r as never) > r.levelCount * HARI_PER_LEVEL) {
    pesan.push(
      `${hari(r as never)} hari untuk ${r.levelCount} level — pemain berpeluang menghabiskan semua level jauh sebelum periode berakhir (ADR-0024).`,
    );
  }
  if (pesan.length === 0) return null;
  return <Chip size="small" color="warning" label={pesan.length === 1 ? pesan[0] : `${pesan.length} peringatan`} title={pesan.join('\n')} />;
};

export const PeriodList = () => (
  <List sort={{ field: 'starts_at', order: 'DESC' }}>
    <Datagrid rowClick="edit" bulkActionButtons={false}>
      <TextField source="name" label="Nama" emptyText="(tanpa nama)" />
      <TextField source="status" label="Status" sortable={false} />
      <DateField source="startsAt" label="Mulai" showTime sortBy="starts_at" />
      <DateField source="endsAt" label="Selesai" showTime sortBy="ends_at" />
      <NumberField source="levelCount" label="Level" sortable={false} />
      <NumberField source="winnerCount" label="Pemenang" sortable={false} />
      <Peringatan />
    </Datagrid>
  </List>
);

const Jadwal = () => (
  <>
    <TextInput source="name" label="Nama" helperText="Boleh dikosongkan." />
    {/* Disimpan sebagai ISO ber-zona: server menolak waktu tanpa zona, karena "jam 00:00" tanpa
        keterangan zona adalah dua jam yang berbeda bagi pemain dan bagi server. */}
    <DateTimeInput source="startsAt" label="Mulai" validate={required()} parse={(v) => (v ? new Date(v).toISOString() : null)} />
    <DateTimeInput source="endsAt" label="Selesai" validate={required()} parse={(v) => (v ? new Date(v).toISOString() : null)} />
  </>
);

const EditActions = () => (
  <TopToolbar>
    <AksiDialog
      label="Tutup sekarang"
      judul="Tutup periode lebih awal?"
      keterangan="Periode langsung berakhir: pemenang difinalisasi, papan yang sedang jalan gugur, nyawa periode ini hangus, dan periode berikutnya diangkat. Tak bisa dibatalkan."
      path={(id) => `/periods/${id}/close`}
    />
  </TopToolbar>
);

export const PeriodEdit = () => (
  <Edit redirect="list" actions={<EditActions />}>
    <SimpleForm>
      <Alert severity="info" sx={{ mb: 2 }}>
        Periode yang sudah berakhir tak bisa diubah — jarak ordinal ban &amp; cooldown dihitung dari
        urutan periode (ADR-0038), jadi menggeser tanggal sejarah menggeser sanksi yang sedang berjalan.
      </Alert>
      <Jadwal />
    </SimpleForm>
  </Edit>
);

export const PeriodCreate = () => (
  <Create redirect="list">
    <SimpleForm>
      <Alert severity="info" sx={{ mb: 2 }}>
        Periode baru selalu lahir <b>UPCOMING</b>; yang mengangkatnya jadi ACTIVE cuma pergantian
        otomatis saat waktunya tiba. Jangan lupa isi level dan hadiahnya.
      </Alert>
      <Jadwal />
    </SimpleForm>
  </Create>
);

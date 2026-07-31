import { useEffect, useState } from 'react';
import { Alert, Box, Card, CardContent, Typography, useTheme } from '@mui/material';
import {
  BooleanField,
  BooleanInput,
  Datagrid,
  DateField,
  DateInput,
  FunctionField,
  List,
  NumberField,
  SelectInput,
  TextField,
  TextInput,
  useListContext,
  usePermissions,
  useRecordContext,
} from 'react-admin';
import { AksiDialog } from './actions';
import { call } from './api';

// Laporan penjualan & pemain (T-042 lanjutan) — alasan panel ini berbentuk SPA (ADR-0013).
// Keduanya baca saja: tak ada satu pun tombol tulis di berkas ini.

// Warna batang grafik. BUKAN seed merek `#0F766E` apa adanya: seed itu gagal "chroma floor" saat
// divalidasi sebagai warna mark (0,086 — terbaca abu, bukan warna), sedangkan `#0D9488` adalah
// langkah tetangga di hue yang sama dan LULUS seluruh cek di latar terang maupun gelap
// (lightness band, chroma, kontras ≥3:1). Satu deret = tanpa legenda; judulnya yang menamai.
const WARNA_BATANG = '#0D9488';

type Ringkasan = {
  transaksi: number;
  livesGranted: number;
  voided: number;
  perProduk: { produk: string; transaksi: number; granted: number; voided: number; lives: number }[];
  harian: { tanggal: string; transaksi: number; lives: number }[];
  catatanUang: string;
};

const Angka = ({ label, nilai, catatan }: { label: string; nilai: number | string; catatan?: string }) => (
  <Card variant="outlined" sx={{ minWidth: 160, flex: '1 1 160px' }}>
    <CardContent sx={{ py: 1.5 }}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h5">{nilai}</Typography>
      {catatan && (
        <Typography variant="caption" color="text.secondary">
          {catatan}
        </Typography>
      )}
    </CardContent>
  </Card>
);

// Grafik batang harian, SVG polos tanpa library: satu deret sederhana tak sebanding dengan
// menambah pohon dependency ke bundel panel (ADR-0013 menahan dependency seminimal mungkin).
// Ujung batang dibulatkan 4px HANYA di atas dan menempel ke garis dasar, jarak 2px antar batang,
// sumbu recessive, tooltip per batang lewat <title> bawaan SVG (tanpa JS, ikut terbaca screen
// reader). Nilai per titik sengaja tak dicetak semua — hanya puncaknya.
const GrafikHarian = ({ data }: { data: Ringkasan['harian'] }) => {
  const theme = useTheme();
  if (data.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
        Belum ada transaksi pada rentang ini.
      </Typography>
    );
  }
  const W = 720;
  const H = 180;
  const PAD = 24;
  const maks = Math.max(...data.map((d) => d.transaksi));
  const lebar = Math.max(2, (W - PAD * 2) / data.length - 2);
  const skala = (n: number) => (n / maks) * (H - PAD * 2);
  const r = 4;

  return (
    <Box sx={{ overflowX: 'auto' }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" height={H} role="img" aria-label="Transaksi per hari">
        {/* Garis dasar recessive — satu-satunya sumbu yang benar-benar dibutuhkan. */}
        <line x1={PAD} y1={H - PAD} x2={W - PAD} y2={H - PAD} stroke={theme.palette.divider} strokeWidth={1} />
        {data.map((d, i) => {
          const tinggi = Math.max(2, skala(d.transaksi));
          const x = PAD + i * ((W - PAD * 2) / data.length);
          const y = H - PAD - tinggi;
          const rr = Math.min(r, tinggi, lebar / 2);
          // Path: sudut atas membulat, alas persegi menempel garis dasar.
          const d2 = `M${x},${H - PAD} L${x},${y + rr} Q${x},${y} ${x + rr},${y} L${x + lebar - rr},${y} Q${x + lebar},${y} ${x + lebar},${y + rr} L${x + lebar},${H - PAD} Z`;
          return (
            <path key={d.tanggal} d={d2} fill={WARNA_BATANG} opacity={0.9}>
              <title>{`${d.tanggal}: ${d.transaksi} transaksi · ${d.lives} nyawa`}</title>
            </path>
          );
        })}
        {/* Label selektif: puncak + tanggal pertama & terakhir. Teks memakai token teks, bukan
            warna deret (identitas dibawa batangnya, bukan tulisannya). */}
        <text x={PAD} y={PAD - 8} fontSize={11} fill={theme.palette.text.secondary}>
          puncak {maks}
        </text>
        <text x={PAD} y={H - 6} fontSize={11} fill={theme.palette.text.secondary}>
          {data[0].tanggal}
        </text>
        <text x={W - PAD} y={H - 6} fontSize={11} textAnchor="end" fill={theme.palette.text.secondary}>
          {data[data.length - 1].tanggal}
        </text>
      </svg>
    </Box>
  );
};

// Ringkasan mengikuti filter yang SAMA dengan tabel di bawahnya — kalau tidak, angka besar di atas
// menjelaskan baris yang berbeda dari yang sedang dilihat orang.
const RingkasanPenjualan = () => {
  const { filterValues } = useListContext();
  const [data, setData] = useState<Ringkasan | null>(null);
  const kunci = JSON.stringify(filterValues ?? {});

  useEffect(() => {
    call<Ringkasan>(`/sales/summary?filter=${encodeURIComponent(kunci)}`)
      .then(setData)
      .catch(() => setData(null));
  }, [kunci]);

  if (!data) return null;
  return (
    <Box sx={{ mb: 2 }}>
      <Alert severity="info" sx={{ mb: 2 }}>
        {data.catatanUang}
      </Alert>
      <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', mb: 2 }}>
        <Angka label="Transaksi" nilai={data.transaksi} />
        <Angka label="Nyawa diberikan" nilai={data.livesGranted} />
        <Angka
          label="Dibatalkan (refund/chargeback)"
          nilai={data.voided}
          catatan={data.transaksi > 0 ? `${Math.round((data.voided / data.transaksi) * 100)}% dari transaksi` : undefined}
        />
      </Box>
      <Card variant="outlined">
        <CardContent>
          <Typography variant="subtitle2" gutterBottom>
            Transaksi per hari (WIB)
          </Typography>
          <GrafikHarian data={data.harian} />
        </CardContent>
      </Card>
      {data.perProduk.length > 0 && (
        <Card variant="outlined" sx={{ mt: 2 }}>
          <CardContent>
            <Typography variant="subtitle2" gutterBottom>
              Per paket
            </Typography>
            <Box component="table" sx={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
              <thead>
                <tr>
                  {['Paket', 'Transaksi', 'Berhasil', 'Dibatalkan', 'Nyawa'].map((h) => (
                    <Box component="th" key={h} sx={{ textAlign: 'left', py: 0.5, color: 'text.secondary', fontWeight: 500 }}>
                      {h}
                    </Box>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.perProduk.map((p) => (
                  <tr key={p.produk}>
                    <Box component="td" sx={{ py: 0.5 }}>{p.produk}</Box>
                    <Box component="td" sx={{ py: 0.5 }}>{p.transaksi}</Box>
                    <Box component="td" sx={{ py: 0.5 }}>{p.granted}</Box>
                    <Box component="td" sx={{ py: 0.5 }}>{p.voided}</Box>
                    <Box component="td" sx={{ py: 0.5 }}>{p.lives}</Box>
                  </tr>
                ))}
              </tbody>
            </Box>
          </CardContent>
        </Card>
      )}
    </Box>
  );
};

const filterPenjualan = [
  <SelectInput
    key="s"
    source="status"
    label="Status"
    alwaysOn
    choices={[
      { id: 'pending', name: 'pending' },
      { id: 'verified', name: 'verified' },
      { id: 'granted', name: 'granted' },
      { id: 'voided', name: 'voided' },
    ]}
  />,
  <SelectInput
    key="p"
    source="productId"
    label="Paket"
    alwaysOn
    choices={[
      { id: 'life_s', name: 'life_s (1 nyawa)' },
      { id: 'life_m', name: 'life_m (5 nyawa)' },
      { id: 'life_l', name: 'life_l (10 nyawa)' },
    ]}
  />,
  <DateInput key="f" source="dateFrom" label="Dari" alwaysOn />,
  <DateInput key="t" source="dateTo" label="Sampai" alwaysOn />,
];

export const SalesReport = () => (
  <List
    filters={filterPenjualan}
    sort={{ field: 'created_at', order: 'DESC' }}
    title="Laporan penjualan"
  >
    <RingkasanPenjualan />
    <Datagrid bulkActionButtons={false} rowClick={false}>
      <DateField source="createdAt" label="Waktu" showTime sortBy="created_at" />
      <TextField source="displayName" label="Pemain" sortable={false} />
      <TextField source="productId" label="Paket" sortBy="product_id" />
      <NumberField source="livesGranted" label="Nyawa" sortBy="lives_granted" />
      <TextField source="status" label="Status" />
      <TextField source="voidReason" label="Sebab batal" sortable={false} emptyText="—" />
    </Datagrid>
  </List>
);

// "Hapus akun" = ANONIMISASI (ADR-0044), bukan DELETE: baris pemain menopang peringkat & cooldown
// peserta lain, pembukuan, dan jejak audit yang justru tak boleh hilang. Server menolak selama
// sanksi masih berjalan atau klaim hadiah belum lunas — pagar itu tak diulang di sini, cukup
// pesannya yang ditampilkan apa adanya.
const HapusAkun = () => {
  const { permissions } = usePermissions();
  const record = useRecordContext();
  if (!record || permissions !== 'admin') return null;
  return (
    <AksiDialog
      label="Hapus akun"
      judul="Hapus akun pemain ini?"
      keterangan="Nama, email, dan data klaim hadiahnya dihapus; kotak pesannya dihapus. Skor, pembelian, dan jejak audit TETAP tersimpan tanpa lagi menunjuk siapa pun — itu milik peringkat pemain lain dan bukti bila ada sanksi yang dibantah. Tak bisa dibatalkan."
      path={(id) => `/players/${id}/delete`}
      field="reason"
      disabled={!!record.deletedAt}
    />
  );
};

const filterPemain = [
  <TextInput key="q" source="q" label="Cari nama" alwaysOn />,
  <BooleanInput key="b" source="banned" label="Sedang kena ban" alwaysOn />,
];

export const PlayerReport = () => (
  <List filters={filterPemain} sort={{ field: 'created_at', order: 'DESC' }} title="Laporan pemain">
    <Datagrid bulkActionButtons={false} rowClick={false}>
      <TextField source="displayName" label="Pemain" sortBy="display_name" />
      <DateField source="createdAt" label="Bergabung" sortBy="created_at" />
      <NumberField source="runs" label="Run" sortable={false} />
      <NumberField source="bestScore" label="Skor terbaik" sortable={false} />
      <NumberField source="livesAvailable" label="Nyawa" sortable={false} />
      <NumberField source="purchases" label="Beli" sortable={false} />
      <NumberField source="casualClaims" label="Earn casual" sortable={false} />
      <BooleanField source="activeBan" label="Ban aktif" sortable={false} />
      <FunctionField
        label="Aktivitas terakhir"
        sortable={false}
        render={(r: { lastActivityAt?: string }) => (r.lastActivityAt ? new Date(r.lastActivityAt).toLocaleString('id-ID') : '—')}
      />
      <FunctionField label="Akun" sortable={false} render={(r: { deletedAt?: string }) => (r.deletedAt ? 'dihapus' : 'aktif')} />
      <HapusAkun />
    </Datagrid>
  </List>
);

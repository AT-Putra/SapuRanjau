import { Card, CardContent, Typography } from '@mui/material';
import { Admin, Resource, Title } from 'react-admin';
import { authProvider } from './auth';
import { dataProvider } from './data';
import { LoginPage } from './LoginPage';
import { AdminUserCreate, AdminUserEdit, AdminUserList } from './adminUsers';
import { PeriodCreate, PeriodEdit, PeriodList } from './periods';
import { LevelCreate, LevelEdit, LevelList } from './levels';
import { PrizeCreate, PrizeEdit, PrizeList } from './prizes';
import { WinnerList } from './winners';
import { AuditList, BanList, ConsentList } from './ops';
import { PlayerReport, SalesReport } from './reports';
import { CasualConfigEdit, CasualConfigList } from './casualConfig';

const Beranda = () => (
  <Card sx={{ mt: 2 }}>
    <Title title="Panel Sapu Ranjau" />
    <CardContent>
      <Typography variant="h6">Panel Sapu Ranjau</Typography>
      <Typography variant="body2">
        Alur satu periode: <b>Periode</b> (jadwal) → <b>Level</b> (isi permainan) → <b>Hadiah</b>{' '}
        (jumlah pemenang &amp; daftar hadiah) → periode berjalan → <b>Pemenang</b> (kirim pesan,
        gugurkan, tandai lunas).
      </Typography>
    </CardContent>
  </Card>
);

// Panel admin (T-041 kerangka, T-042 layar domain). Peran ditegakkan SERVER di tiap endpoint;
// penyaringan di sini cuma supaya operator tak diberi menu yang pasti dibalas 403.
export const App = () => (
  <Admin
    authProvider={authProvider}
    dataProvider={dataProvider}
    loginPage={LoginPage}
    dashboard={Beranda}
    // Tanpa ini react-admin mengirim ping telemetri ke server pihak ketiga dari browser operator
    // pada build produksi. Panel ini membuka PII klaim hadiah (ADR-0020) — tak ada permintaan
    // keluar yang tak kita minta.
    disableTelemetry
    requireAuth
  >
    {(permissions) => {
      const setel = permissions === 'admin' || permissions === 'moderator'; // ARCH §10
      return (
        <>
          <Resource
            name="periods"
            options={{ label: 'Periode' }}
            list={PeriodList}
            edit={setel ? PeriodEdit : undefined}
            create={setel ? PeriodCreate : undefined}
          />
          <Resource
            name="levels"
            options={{ label: 'Level' }}
            list={LevelList}
            edit={setel ? LevelEdit : undefined}
            create={setel ? LevelCreate : undefined}
          />
          <Resource
            name="prizes"
            options={{ label: 'Hadiah' }}
            list={PrizeList}
            edit={setel ? PrizeEdit : undefined}
            create={setel ? PrizeCreate : undefined}
          />
          <Resource name="winners" options={{ label: 'Pemenang' }} list={WinnerList} />
          {/* Laporan penjualan = laporan uang → hanya peran uang (server menegakkan hal yang sama). */}
          {(permissions === 'admin' || permissions === 'finance') && (
            <Resource name="sales" options={{ label: 'Laporan penjualan' }} list={SalesReport} />
          )}
          <Resource name="players" options={{ label: 'Laporan pemain' }} list={PlayerReport} />
          <Resource name="bans" options={{ label: 'Ban turnamen' }} list={BanList} />
          <Resource name="consents" options={{ label: 'Persetujuan S&K' }} list={ConsentList} />
          <Resource name="audit-events" options={{ label: 'Audit' }} list={AuditList} />
          {/* Ekonomi nyawa: admin saja — menggeser jatah semua pemain sekaligus dan menyentuh
              lantai legal GDD §9.5. */}
          {permissions === 'admin' && (
            <Resource
              name="casual-config"
              options={{ label: 'Ekonomi nyawa' }}
              list={CasualConfigList}
              edit={CasualConfigEdit}
            />
          )}
          {permissions === 'admin' && (
            <Resource
              name="admin-users"
              options={{ label: 'Operator' }}
              list={AdminUserList}
              edit={AdminUserEdit}
              create={AdminUserCreate}
            />
          )}
        </>
      );
    }}
  </Admin>
);

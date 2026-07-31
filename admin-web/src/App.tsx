import { Card, CardContent, Typography } from '@mui/material';
import { Admin, Resource, Title } from 'react-admin';
import { authProvider } from './auth';
import { dataProvider } from './data';
import { LoginPage } from './LoginPage';
import { AdminUserCreate, AdminUserEdit, AdminUserList } from './adminUsers';

const Beranda = () => (
  <Card sx={{ mt: 2 }}>
    <Title title="Panel Sapu Ranjau" />
    <CardContent>
      <Typography variant="h6">Panel Sapu Ranjau</Typography>
      <Typography variant="body2">
        Layar periode, hadiah, pemenang, ban, dan audit menyusul di T-042.
      </Typography>
    </CardContent>
  </Card>
);

// Kerangka panel (T-041, ADR-0013). Yang dibuktikan di sini transportnya — sesi cookie + CSRF +
// kontrak `ra-data-simple-rest`; layar domain T-042 tinggal menambah <Resource>.
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
    {(permissions) => (
      <>
        {/* Peran ditegakkan server (`principal.require`); menyembunyikan menunya di sini supaya
            operator non-admin tak diberi tombol yang pasti dibalas 403. */}
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
    )}
  </Admin>
);

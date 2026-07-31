import {
  BooleanField,
  BooleanInput,
  Create,
  Datagrid,
  Edit,
  List,
  PasswordInput,
  SelectInput,
  SimpleForm,
  TextField,
  TextInput,
  required,
} from 'react-admin';
import { AksiDialog } from './actions';

// Resource `admin-users` (T-040 di server, layarnya di sini). Selain memang dipakai untuk mengelola
// operator, ia bukti bahwa kontrak transport ADR-0013 jalan utuh dari browser: sesi cookie, header
// CSRF, `range`/`sort`, dan `Content-Range`. Layar domain T-042 tinggal menyalin bentuk ini.
const PERAN = [
  { id: 'admin', name: 'admin — semua, termasuk kelola operator' },
  { id: 'finance', name: 'finance — pembayaran & klaim hadiah' },
  { id: 'moderator', name: 'moderator — pemain, ban, pesan' },
];

export const AdminUserList = () => (
  // Tanpa tombol hapus & tanpa aksi massal: API-nya memang tak punya DELETE (audit_event merujuk id
  // operator — menghapus barisnya membuat jejak audit menunjuk ruang kosong). Menonaktifkan = Edit.
  <List sort={{ field: 'username', order: 'ASC' }}>
    <Datagrid rowClick="edit" bulkActionButtons={false}>
      <TextField source="username" label="Username" />
      <TextField source="role" label="Peran" />
      {/* Tak bisa diurutkan: server hanya menerima kolom yang di-whitelist RaQuery (id, username,
          role, created_at, last_login_at) dan diam-diam jatuh ke `id` untuk sisanya — header yang
          bisa diklik tapi tak mengubah urutan lebih membingungkan daripada header yang diam. */}
      <BooleanField source="disabled" label="Nonaktif" sortable={false} />
      <BooleanField source="totpEnrolled" label="2FA terpasang" sortable={false} />
    </Datagrid>
  </List>
);

export const AdminUserEdit = () => (
  <Edit redirect="list">
    <SimpleForm>
      <TextField source="username" label="Username" />
      {/* Satu-satunya pemulihan untuk operator yang kehilangan authenticator-nya: login berikutnya
          dipaksa mendaftar 2FA lagi. Akun sendiri ditolak server — yang kehilangan authenticator
          tak punya sesi untuk menekannya, jadi tombol itu hanya berguna bagi sesi curian. */}
      <AksiDialog
        label="Reset 2FA"
        judul="Reset 2FA operator ini?"
        keterangan="Secret TOTP-nya dibuang. Login berikutnya akan menampilkan QR pendaftaran baru. Tercatat di audit."
        path={(id) => `/admin-users/${id}/reset-totp`}
      />
      <SelectInput source="role" label="Peran" choices={PERAN} validate={required()} />
      <BooleanInput source="disabled" label="Nonaktif" />
      {/* Dikosongkan = password tak diubah (server melewati nilai kosong). */}
      <PasswordInput source="password" label="Password baru (opsional)" autoComplete="new-password" />
    </SimpleForm>
  </Edit>
);

export const AdminUserCreate = () => (
  <Create redirect="list">
    <SimpleForm>
      <TextInput source="username" label="Username" validate={required()} helperText="3–32 karakter: huruf, angka, titik, garis bawah, strip." />
      <PasswordInput source="password" label="Password" validate={required()} autoComplete="new-password" helperText="Minimal 12 karakter." />
      <SelectInput source="role" label="Peran" choices={PERAN} defaultValue="moderator" validate={required()} />
    </SimpleForm>
  </Create>
);

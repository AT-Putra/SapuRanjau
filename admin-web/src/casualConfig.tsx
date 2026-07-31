import { Alert } from '@mui/material';
import {
  Datagrid,
  Edit,
  List,
  NumberField,
  NumberInput,
  SimpleForm,
  minValue,
  required,
} from 'react-admin';

// Parameter earn nyawa casual (ADR-0023/0045). Sebelum ini menyetel ekonomi nyawa berarti deploy
// ulang. Satu baris saja — daftarnya cuma pintu masuk ke formulirnya.
//
// Peran `admin` saja, ditegakkan server: ini bukan operasi harian seperti level/periode, ia
// menggeser ekonomi semua pemain sekaligus.

const Peringatan = () => (
  <Alert severity="warning" sx={{ mb: 2 }}>
    <b>Menurunkan cap menyentuh batas legal.</b> `01_GDD.md` §9.5 menuntut jalur nyawa gratis tetap
    memadai supaya membeli nyawa adalah <b>kenyamanan, bukan keharusan</b> — itu salah satu pilar
    posisi anti-judi produk ini. Angka 1/5/10 sudah dekat batas bawah; menurunkannya adalah
    keputusan hukum, bukan penyetelan ekonomi. Menaikkannya aman.
  </Alert>
);

export const CasualConfigList = () => (
  <List title="Ekonomi nyawa casual" pagination={false} actions={false}>
    <Datagrid rowClick="edit" bulkActionButtons={false}>
      <NumberField source="rewardLives" label="Nyawa per kemenangan" sortable={false} />
      <NumberField source="capDaily" label="Cap harian" sortable={false} />
      <NumberField source="capWeekly" label="Cap mingguan" sortable={false} />
      <NumberField source="capMonthly" label="Cap bulanan" sortable={false} />
      <NumberField source="minMines" label="Min. bom" sortable={false} />
      <NumberField source="minDensity" label="Min. density" sortable={false} />
    </Datagrid>
  </List>
);

export const CasualConfigEdit = () => (
  <Edit redirect="list" title="Ekonomi nyawa casual">
    <SimpleForm>
      <Peringatan />
      <NumberInput source="rewardLives" label="Nyawa per kemenangan (1–3)" validate={[required(), minValue(1)]} />
      <NumberInput
        source="capDaily"
        label="Cap harian"
        validate={[required(), minValue(1)]}
        helperText="Jendela kalender waktu setempat (WIB), bukan 24 jam bergulir."
      />
      <NumberInput source="capWeekly" label="Cap mingguan" validate={[required(), minValue(1)]} />
      <NumberInput source="capMonthly" label="Cap bulanan" validate={[required(), minValue(1)]} />
      <NumberInput
        source="minMines"
        label="Ambang jumlah bom"
        validate={[required(), minValue(1)]}
        helperText='Diuji bersama density — "≥ medium" harus lolos DUA sumbu supaya tak bisa diakali papan sepi berbom banyak.'
      />
      <NumberInput
        source="minDensity"
        label="Ambang density (0–0,30)"
        step={0.01}
        validate={required()}
        helperText="Di atas 0,30 papan tak dijamin bisa dibuat no-guess (ADR-0031)."
      />
    </SimpleForm>
  </Edit>
);

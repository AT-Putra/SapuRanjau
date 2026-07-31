import { Alert } from '@mui/material';
import {
  Create,
  Datagrid,
  DeleteButton,
  Edit,
  List,
  NumberField,
  NumberInput,
  ReferenceField,
  ReferenceInput,
  SelectInput,
  SimpleForm,
  TextField,
  minValue,
  required,
} from 'react-admin';

// Level per periode (`level_config`, ADR-0017). Jumlah level = panjang permainan satu periode
// (GDD §5); peringatan "periode ≫ estimasi habis-level" ada di layar Periode.
//
// Batas grid/bom ditegakkan SERVER (kelayakan generator no-guess, ADR-0031) — yang di sini cuma
// menghemat perjalanan bolak-balik.

const filters = [
  <ReferenceInput key="p" source="periodId" reference="periods" alwaysOn>
    <SelectInput label="Periode" optionText={(r) => r.name || `Periode #${r.id}`} />
  </ReferenceInput>,
];

export const LevelList = () => (
  <List filters={filters} sort={{ field: 'level_index', order: 'ASC' }}>
    <Datagrid rowClick="edit" bulkActionButtons={false}>
      <ReferenceField source="periodId" reference="periods" label="Periode" link={false} sortable={false}>
        <TextField source="name" emptyText="(tanpa nama)" />
      </ReferenceField>
      <NumberField source="levelIndex" label="Urutan" sortBy="level_index" />
      <NumberField source="gridWidth" label="Lebar" sortBy="grid_width" />
      <NumberField source="gridHeight" label="Tinggi" sortBy="grid_height" />
      <NumberField source="mineCount" label="Bom" sortBy="mine_count" />
      <NumberField source="baseScore" label="Base" sortBy="base_score" />
      <NumberField source="lifeCap" label="Cap nyawa" sortable={false} />
      {/* Hapus hanya mungkin selama periodenya belum berjalan (ditegakkan server); tombolnya tetap
          tampak supaya operator tahu jalannya ada, dan penolakannya menjelaskan alasannya. */}
      <DeleteButton mutationMode="pessimistic" />
    </Datagrid>
  </List>
);

const Form = () => (
  <SimpleForm>
    <ReferenceInput source="periodId" reference="periods">
      <SelectInput label="Periode" optionText={(r) => r.name || `Periode #${r.id}`} validate={required()} />
    </ReferenceInput>
    <NumberInput source="levelIndex" label="Urutan (0 = level pertama)" validate={[required(), minValue(0)]} />
    <NumberInput source="gridWidth" label="Lebar grid" defaultValue={9} validate={required()} />
    <NumberInput source="gridHeight" label="Tinggi grid" defaultValue={9} validate={required()} />
    <NumberInput source="mineCount" label="Jumlah bom" defaultValue={10} validate={required()} helperText="Maksimal 30% dari jumlah sel — di atas itu papan no-guess tak selalu bisa dibuat." />
    <NumberInput source="baseScore" label="Base score" defaultValue={1000} validate={required()} />
    <NumberInput source="lifeCap" label="Cap nyawa per level" defaultValue={2} validate={[required(), minValue(0)]} />
  </SimpleForm>
);

export const LevelEdit = () => (
  <Edit redirect="list">
    <Alert severity="info" sx={{ m: 2 }}>
      Level yang sudah pernah dimainkan tak bisa diubah bentuknya: papan yang beredar dibuat dari
      konfigurasi ini dan skornya diverifikasi ulang dari sini (ADR-0017/0031).
    </Alert>
    <Form />
  </Edit>
);

export const LevelCreate = () => (
  <Create redirect="list">
    <Form />
  </Create>
);

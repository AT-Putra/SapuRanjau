import { Alert } from '@mui/material';
import {
  ArrayField,
  ArrayInput,
  Create,
  Datagrid,
  Edit,
  List,
  NumberField,
  NumberInput,
  ReferenceField,
  ReferenceInput,
  SelectInput,
  SimpleForm,
  SimpleFormIterator,
  SingleFieldList,
  TextField,
  TextInput,
  required,
} from 'react-admin';

// Hadiah per periode (ADR-0021): jumlah pemenang 3–10 + satu baris hadiah per peringkat. Server
// menolak daftar yang panjangnya tak sama dengan jumlah pemenang — peringkat tanpa hadiah adalah
// pertanyaan yang akan ditanyakan pemain, bukan admin.

export const PrizeList = () => (
  <List>
    <Datagrid rowClick="edit" bulkActionButtons={false}>
      <ReferenceField source="periodId" reference="periods" label="Periode" link={false} sortable={false}>
        <TextField source="name" emptyText="(tanpa nama)" />
      </ReferenceField>
      <NumberField source="winnersCount" label="Jumlah pemenang" sortable={false} />
      <ArrayField source="prizes" label="Hadiah per peringkat" sortable={false}>
        <SingleFieldList linkType={false}>
          <TextField source="" />
        </SingleFieldList>
      </ArrayField>
    </Datagrid>
  </List>
);

const Form = ({ baru }: { baru: boolean }) => (
  <SimpleForm>
    <Alert severity="info" sx={{ mb: 2 }}>
      Isi <b>satu baris hadiah per peringkat</b>, urut dari juara 1. Periode tanpa konfigurasi ini
      berakhir <b>tanpa pemenang</b>. Setelah pemenang final, konfigurasinya terkunci.
    </Alert>
    <ReferenceInput source="periodId" reference="periods">
      <SelectInput label="Periode" optionText={(r) => r.name || `Periode #${r.id}`} validate={required()} disabled={!baru} />
    </ReferenceInput>
    <NumberInput source="winnersCount" label="Jumlah pemenang (3–10)" defaultValue={3} validate={required()} />
    <ArrayInput source="prizes" label="Hadiah per peringkat">
      <SimpleFormIterator disableReordering>
        {/* source kosong = array of string, bukan array of object (kontrak jsonb `prize_config`). */}
        <TextInput source="" label="Hadiah" validate={required()} />
      </SimpleFormIterator>
    </ArrayInput>
  </SimpleForm>
);

export const PrizeEdit = () => (
  <Edit redirect="list">
    <Form baru={false} />
  </Edit>
);

export const PrizeCreate = () => (
  <Create redirect="list">
    <Form baru />
  </Create>
);

import { useEffect, useState } from 'react';
import { Alert, Box, Typography } from '@mui/material';
import {
  Datagrid,
  DateField,
  FunctionField,
  List,
  SelectInput,
  TextField,
  TextInput,
  usePermissions,
  useRecordContext,
} from 'react-admin';
import { AksiDialog } from './actions';
import { call } from './api';

// ── Ban turnamen (ADR-0025) ───────────────────────────────────────────────────────────────────
//
// Mengampuni = MENANDAI, bukan menghapus: baris yang hilang membuat `purchase` ber-status 'voided'
// terlihat belum tertangani, dan pergantian periode menerbitkan ban baru di tick berikutnya. Itu
// sebabnya tak ada tombol hapus di sini sama sekali.

const Ampuni = () => {
  const { permissions } = usePermissions();
  const record = useRecordContext();
  if (!record || (permissions !== 'admin' && permissions !== 'moderator')) return null;
  return (
    <AksiDialog
      label="Ampuni"
      judul="Cabut sanksi ban ini?"
      keterangan="Baris sanksinya TETAP tersimpan (ditandai diampuni) — itu yang mencegah sistem menerbitkannya lagi, dan yang dibaca kalau pemain membantah di kemudian hari."
      path={(id) => `/bans/${id}/forgive`}
      field="reason"
      disabled={!!record.forgivenAt}
    />
  );
};

export const BanList = () => (
  <List sort={{ field: 'created_at', order: 'DESC' }}>
    <Datagrid bulkActionButtons={false} rowClick={false}>
      <TextField source="displayName" label="Pemain" sortable={false} />
      <TextField source="reason" label="Sebab" />
      <TextField source="periodStartName" label="Mulai periode" sortable={false} emptyText="(tanpa nama)" />
      <FunctionField
        label="Sisa"
        sortable={false}
        render={(r: { forgivenAt?: string; periodsRemaining?: number | null }) =>
          r.forgivenAt
            ? 'diampuni'
            : r.periodsRemaining == null
              ? '— (tak ada periode berjalan)'
              : r.periodsRemaining > 0
                ? `${r.periodsRemaining} periode`
                : 'selesai'
        }
      />
      <TextField source="forgiveReason" label="Alasan ampunan" sortable={false} emptyText="—" />
      <DateField source="createdAt" label="Terbit" showTime sortBy="created_at" />
      <Ampuni />
    </Datagrid>
  </List>
);

// ── Persetujuan S&K (ADR-0026) ────────────────────────────────────────────────────────────────

const VersiTnc = () => {
  const [tnc, setTnc] = useState<{ version?: string; note?: string } | null>(null);
  useEffect(() => {
    call<{ version: string; note: string }>('/tnc').then(setTnc).catch(() => setTnc(null));
  }, []);
  return (
    <Alert severity="info" sx={{ mb: 2 }}>
      <Typography variant="body2">
        Versi S&amp;K berlaku: <b>{tnc?.version || '(belum diset)'}</b>
      </Typography>
      <Typography variant="caption">{tnc?.note}</Typography>
    </Alert>
  );
};

export const ConsentList = () => (
  <Box>
    <VersiTnc />
    <List sort={{ field: 'agreed_at', order: 'DESC' }} title="Persetujuan S&K">
      <Datagrid bulkActionButtons={false} rowClick={false}>
        <TextField source="displayName" label="Pemain" sortable={false} />
        <TextField source="periodName" label="Periode" sortable={false} emptyText="(tanpa nama)" />
        <TextField source="tncVersion" label="Versi" sortBy="tnc_version" />
        <DateField source="agreedAt" label="Disetujui" showTime sortBy="agreed_at" />
      </Datagrid>
    </List>
  </Box>
);

// ── Audit (T-027) ─────────────────────────────────────────────────────────────────────────────
//
// Baca saja, dan memang tak ada tombol apa pun: `audit_event` append-only di level trigger — DELETE
// ditolak Postgres apa pun perannya. Layar yang menawarkan tombol hapus di sini akan berbohong.

const auditFilters = [
  <TextInput key="e" source="eventType" label="Jenis kejadian" alwaysOn />,
  <SelectInput
    key="a"
    source="actorType"
    label="Pelaku"
    alwaysOn
    choices={[
      { id: 'player', name: 'player' },
      { id: 'admin', name: 'admin' },
      { id: 'system', name: 'system' },
    ]}
  />,
  <TextInput key="t" source="target" label="Target (mis. period:3)" />,
];

export const AuditList = () => (
  <List filters={auditFilters} sort={{ field: 'created_at', order: 'DESC' }} title="Jejak audit">
    <Datagrid bulkActionButtons={false} rowClick={false}>
      <DateField source="createdAt" label="Waktu" showTime sortBy="created_at" />
      <TextField source="actorType" label="Pelaku" sortBy="actor_type" />
      <TextField source="actorId" label="ID pelaku" sortable={false} emptyText="—" />
      <TextField source="eventType" label="Kejadian" sortBy="event_type" />
      <TextField source="target" label="Target" sortable={false} emptyText="—" />
      <FunctionField
        label="Detail"
        sortable={false}
        render={(r: { detail?: string }) => (
          <Box component="code" sx={{ fontSize: 12, wordBreak: 'break-all' }}>
            {r.detail}
          </Box>
        )}
      />
    </Datagrid>
  </List>
);

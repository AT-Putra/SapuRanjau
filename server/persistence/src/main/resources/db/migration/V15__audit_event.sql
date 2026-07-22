-- audit_event — jejak append-only (§9/§10). docs/08_DATA_SCHEMA.md §2.15.
CREATE TABLE audit_event (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_type  text   NOT NULL CHECK (actor_type IN ('player','admin','system')),
  actor_id    bigint,                                     -- user_id / admin_id (NULL utk system)
  event_type  text   NOT NULL,
  target      text,                                       -- entitas/id terkait
  detail      jsonb,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_time  ON audit_event (created_at);
CREATE INDEX audit_actor ON audit_event (actor_id, created_at);

-- Append-only (ADR-0020 §1): trigger tolak UPDATE/DELETE, berlaku apa pun role koneksinya.
-- ponytail: defense kedua (revoke UPDATE/DELETE dari role app) ditunda ke provisioning DB
-- (ADR-0015) — belum ada role/credential app di repo ini utk direvoke. Trigger sendiri
-- sudah cukup blokir; tambah revoke saat role app benar-benar dibuat.
CREATE OR REPLACE FUNCTION audit_event_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'audit_event append-only: UPDATE/DELETE dilarang (ADR-0020)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_no_update
  BEFORE UPDATE OR DELETE ON audit_event
  FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();

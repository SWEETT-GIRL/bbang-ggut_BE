import json
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from agent.response_change.approval import content_digest
from agent.response_change.host_runtime import write_json
from agent.response_change.recovery import (RecoveryVerifier, file_sha, migration_digest,
    SCHEMA_QUERIES, restore_equivalence_schema, restore_equivalence_digest)
from agent.response_change.release import ReleaseHold


class RecoveryEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name).resolve()
        self.backup=self.root/'backup.dump';self.backup.write_bytes(b'actual restore fixture');self.backup.chmod(0o600)
        self.jar=self.root/'service.jar'
        with zipfile.ZipFile(self.jar,'w') as z:z.writestr('BOOT-INF/classes/db/migration/V1__sample.sql','CREATE TABLE x(id int);')
        self.receipt={'kind':'ACTUAL_DUMP_RESTORE_AND_STAGED_SCHEMA_V1','restore_verified':True,'staged_service_verified':True,
            'backup_path':str(self.backup),'backup_bytes':self.backup.stat().st_size,'backup_sha256':file_sha(self.backup),
            'source_database_identity':'a'*64,'restored_database_identity':'b'*64,'validated_schema_digest':'c'*64,
            'migration_resources_digest':migration_digest(self.jar),'staged_artifact_sha256':file_sha(self.jar)}
        self.path=self.root/'recovery.json';self.save()
        self.build={'artifact_path':str(self.jar),'artifact_sha256':file_sha(self.jar)}
        self.live={'dataops_database_identity':'a'*64}
        self.verifier=RecoveryVerifier(lambda:None,self.path)
    def tearDown(self):self.tmp.cleanup()
    def save(self):
        self.receipt.pop('receipt_digest',None);self.receipt['receipt_digest']=content_digest(self.receipt);write_json(self.path,self.receipt)
    def verify(self,**current):
        with patch('agent.response_change.recovery.database_evidence',return_value={'database_identity':'a'*64,'schema_digest':'c'*64,**current}):
            return self.verifier(self.build,self.live)
    def test_rechecks_actual_backup_and_current_schema(self):
        self.assertTrue(self.verify()['backup_verified'])
        with self.assertRaisesRegex(ReleaseHold,'RECOVERY_SCHEMA_CHANGED'):self.verify(schema_digest='d'*64)
        with self.assertRaisesRegex(ReleaseHold,'RECOVERY_DATABASE_CHANGED'):self.verify(database_identity='d'*64)
    def test_backup_corruption_never_uses_previous_boolean(self):
        self.backup.write_bytes(b'changed')
        with self.assertRaisesRegex(ReleaseHold,'VERIFIED_BACKUP_CHANGED'):self.verify()
    def test_migration_change_requires_new_restore_evidence(self):
        with zipfile.ZipFile(self.jar,'a') as z:z.writestr('BOOT-INF/classes/db/migration/V2__change.sql','ALTER TABLE x ADD name text;')
        self.build['artifact_sha256']=file_sha(self.jar)
        with self.assertRaisesRegex(ReleaseHold,'RECOVERY_SCHEMA_COMPATIBILITY_CHANGED'):self.verify()
    def test_unverified_or_same_database_restore_is_rejected(self):
        self.receipt['restore_verified']=False;self.save()
        with self.assertRaisesRegex(ReleaseHold,'ACTUAL_RECOVERY_EVIDENCE_REQUIRED'):self.verify()
        self.receipt['restore_verified']=True;self.receipt['restored_database_identity']='a'*64;self.save()
        with self.assertRaisesRegex(ReleaseHold,'INDEPENDENT_RESTORE_REQUIRED'):self.verify()


class RestoreEquivalenceTests(unittest.TestCase):
    OLD="ANY ((ARRAY['PREPARED'::character varying, 'UNKNOWN'::character varying])::text[])"
    NEW="ANY (ARRAY[('PREPARED'::character varying)::text, ('UNKNOWN'::character varying)::text])"

    def schema(self, oid=123, restored=False):
        schema={key:[] for key in SCHEMA_QUERIES}
        expression=self.NEW if restored else self.OLD
        schema['constraints']=[['items','state_check','CHECK (((state)::text = '+expression+'))',True]]
        schema['indexes']=[['items','state_idx','CREATE INDEX state_idx ON public.items USING btree (id) WHERE ((state)::text = '+expression+')']]
        name='RI_ConstraintTrigger_a_'+str(oid)
        schema['triggers']=[['items',name,'O','CREATE CONSTRAINT TRIGGER "'+name+'" AFTER DELETE ON public.items FROM child NOT DEFERRABLE INITIALLY IMMEDIATE FOR EACH ROW EXECUTE FUNCTION "RI_FKey_noaction_del"()']]
        return schema,[['items',name]]

    def digest(self,schema,internal):
        return restore_equivalence_digest(schema,internal_triggers=internal)

    def test_only_observed_array_cast_and_catalog_verified_trigger_oid_are_equal(self):
        left,li=self.schema();right,ri=self.schema(999,True)
        saved=copy.deepcopy(left)
        self.assertNotEqual(content_digest(left),content_digest(right))
        self.assertEqual(self.digest(left,li),self.digest(right,ri))
        self.assertEqual(saved,left)

    def test_trigger_name_without_internal_catalog_evidence_is_not_normalized(self):
        left,_=self.schema();right,_=self.schema(999)
        self.assertNotEqual(self.digest(left,[]),self.digest(right,[]))
        with self.assertRaisesRegex(ReleaseHold,'EVIDENCE_CHANGED'):
            self.digest(left,[['items','RI_ConstraintTrigger_a_999']])

    def test_trigger_semantics_enabled_state_and_multiplicity_remain_significant(self):
        left,li=self.schema();right,ri=self.schema(999,True)
        for index,old,new in ((3,'AFTER DELETE','AFTER UPDATE'),
            (3,'FROM child','FROM other'),(3,'NOT DEFERRABLE','DEFERRABLE'),
            (3,'INITIALLY IMMEDIATE','INITIALLY DEFERRED'),(3,'RI_FKey_noaction_del','RI_FKey_cascade_del')):
            changed=copy.deepcopy(right);changed['triggers'][0][index]=changed['triggers'][0][index].replace(old,new)
            with self.subTest(old=old):self.assertNotEqual(self.digest(left,li),self.digest(changed,ri))
        doubled=copy.deepcopy(right);doubled['triggers']*=2
        self.assertNotEqual(self.digest(left,li),self.digest(doubled,ri))
        changed=copy.deepcopy(right);changed['triggers'][0][3]=changed['triggers'][0][3].replace('RI_FKey_noaction_del','custom_function')
        with self.assertRaisesRegex(ReleaseHold,'TRIGGER_DEFINITION_CHANGED'):self.digest(changed,ri)

    def test_internal_fk_enabled_state_swap_is_held_without_constraint_binding(self):
        first,internal=self.schema()
        second,other=self.schema(456)
        first['triggers']+=second['triggers'];internal+=other
        # Different FK column bindings are absent from pg_get_triggerdef, so
        # both displayed definitions may otherwise be identical after OID removal.
        first['triggers'][0][2]='D'
        swapped=copy.deepcopy(first)
        swapped['triggers'][0][2]='O';swapped['triggers'][1][2]='D'
        for schema in (first,swapped):
            with self.assertRaisesRegex(ReleaseHold,'RESTORE_INTERNAL_TRIGGER_NOT_ORIGIN_ENABLED'):
                self.digest(schema,internal)
        for state in ('D','R','A'):
            schema,names=self.schema();schema['triggers'][0][2]=state
            with self.subTest(state=state), self.assertRaisesRegex(ReleaseHold,'NOT_ORIGIN_ENABLED'):
                self.digest(schema,names)

    def test_other_types_values_conditions_casts_or_unvalidated_checks_never_compare_equal(self):
        left,li=self.schema();right,ri=self.schema(999,True)
        mutations=(('UNKNOWN','APPLYING'),('character varying','character'),('::text','::citext'),
            (' = ANY',' <> ANY'),('state)::text','other)::text'),('ARRAY[','ARRAY[NULL, '),
            ('::character varying','::character varying(2)'),('PREPARED','prepared'))
        for old,new in mutations:
            changed=copy.deepcopy(right)
            changed['constraints'][0][2]=changed['constraints'][0][2].replace(old,new)
            with self.subTest(old=old,new=new):self.assertNotEqual(self.digest(left,li),self.digest(changed,ri))
        changed=copy.deepcopy(right);changed['constraints'][0][3]=False
        self.assertNotEqual(self.digest(left,li),self.digest(changed,ri))
        changed=copy.deepcopy(right);changed['indexes'][0][2]=changed['indexes'][0][2].replace('CREATE INDEX','CREATE UNIQUE INDEX')
        self.assertNotEqual(self.digest(left,li),self.digest(changed,ri))

    def test_array_order_duplicates_and_unsupported_literal_formats_are_preserved(self):
        left,li=self.schema();right,ri=self.schema(999,True)
        for expression in (self.NEW.replace("'PREPARED'","'UNKNOWN'"),
                self.NEW.replace("'PREPARED'","'TEMP'").replace("'UNKNOWN'","'PREPARED'").replace("'TEMP'","'UNKNOWN'"),
                self.NEW.replace("'UNKNOWN'","E'UNKNOWN'")):
            changed=copy.deepcopy(right);changed['constraints'][0][2]=changed['constraints'][0][2].replace(self.NEW,expression)
            self.assertNotEqual(self.digest(left,li),self.digest(changed,ri))

    def test_quotes_and_unsupported_sql_forms_cannot_hide_condition_changes(self):
        left,li=self.schema();right,ri=self.schema(999)
        for prefix,suffix in (("'","'"),('$$','$$'),('/*','*/')):
            left['constraints'][0][2]=prefix+self.OLD.replace("'","''")+suffix
            right['constraints'][0][2]=prefix+self.NEW.replace("'","''")+suffix
            self.assertNotEqual(self.digest(left,li),self.digest(right,ri))

    def test_only_trigger_rows_are_sorted_and_duplicates_are_not_dropped(self):
        first,fi=self.schema();second,si=self.schema(456)
        second['triggers'][0][3]=second['triggers'][0][3].replace('FROM child','FROM child_two')
        first['triggers']+=second['triggers'];fi+=si
        reordered=copy.deepcopy(first);reordered['triggers'].reverse()
        self.assertEqual(self.digest(first,fi),self.digest(reordered,fi))
        for section in ('columns','migrations'):
            first[section]=[['a'],['b']];reordered=copy.deepcopy(first);reordered[section].reverse()
            self.assertNotEqual(self.digest(first,fi),self.digest(reordered,fi))

if __name__=='__main__':unittest.main()

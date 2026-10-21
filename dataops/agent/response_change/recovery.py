"""Verify host-recorded real restore evidence against the current database and JAR.

The receipt is an operator-owned capability, created only after an actual dump,
restore and staged service check. It is not a model/PR input. File hashes do not
authenticate another process with the same operating-system user's authority.
"""
import hashlib
import json
from pathlib import Path
import re
import zipfile

from .approval import content_digest
from .host_runtime import private_file, require


SCHEMA_QUERIES = {
    'migrations': "SELECT version,type,description,checksum,success FROM flyway_schema_history ORDER BY installed_rank",
    'columns': "SELECT c.relname,a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod),a.attnotnull,pg_get_expr(d.adbin,d.adrelid) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE n.nspname='public' AND c.relkind IN ('r','p') AND a.attnum>0 AND NOT a.attisdropped ORDER BY c.relname,a.attnum",
    'constraints': "SELECT c.relname,k.conname,pg_get_constraintdef(k.oid),k.convalidated FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' ORDER BY c.relname,k.conname",
    'indexes': "SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY tablename,indexname",
    'functions': "SELECT p.proname,pg_get_function_identity_arguments(p.oid),pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.prokind='f' ORDER BY p.proname,pg_get_function_identity_arguments(p.oid)",
    'triggers': "SELECT c.relname,t.tgname,t.tgenabled,pg_get_triggerdef(t.oid) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' ORDER BY c.relname,t.tgname",
    'row_security': "SELECT c.relname,c.relrowsecurity,c.relforcerowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') ORDER BY c.relname",
    'policies': "SELECT tablename,policyname,permissive,roles::text,cmd,qual,with_check FROM pg_policies WHERE schemaname='public' ORDER BY tablename,policyname",
}


RESTORE_EQUIVALENCE_VERSION = 'postgres-restore-equivalence-v1'
_VARCHAR_LITERAL = r"'[A-Z][A-Z0-9_]{0,63}'::character varying"
_VARCHAR_ARRAY_TO_TEXT = re.compile(
    r'ANY \(\(ARRAY\[(?P<values>' + _VARCHAR_LITERAL + r'(?:, ' + _VARCHAR_LITERAL + r')*)\]\)::text\[\]\)')
_INTERNAL_TRIGGER_NAME = re.compile(r'RI_ConstraintTrigger_([ac])_[1-9][0-9]*\Z')


def _restore_array_expression(expression):
    """Only pg_dump's observed literal varchar[] -> text[] spelling, outside quotes.

    Other types, casts, expressions, literal formats and conditions are retained
    byte-for-byte. This is deliberately not a general SQL canonicalizer.
    """
    if any(token in expression for token in ('\\', '$', '--', '/*', '\n', '\r')):
        return expression # Unsupported lexical forms remain byte-sensitive.
    output=[];position=0
    while position < len(expression):
        char=expression[position]
        if char in {"'", '"'}:
            start=position;position+=1
            while position < len(expression):
                if expression[position]=='\\':
                    position+=2
                elif expression[position]==char:
                    position+=1
                    if position < len(expression) and expression[position]==char:
                        position+=1
                    else:break
                else:position+=1
            output.append(expression[start:position]);continue
        match=_VARCHAR_ARRAY_TO_TEXT.match(expression,position)
        if match and (position==0 or not (expression[position-1].isalnum() or expression[position-1]=='_')):
            literals=match['values'].split(', ')
            output.append('ANY (ARRAY['+', '.join('('+literal+')::text' for literal in literals)+'])')
            position=match.end()
        else:
            output.append(char);position+=1
    return ''.join(output)


def restore_equivalence_schema(schema, *, internal_triggers):
    """Operator-only restore comparison; never replace the live raw schema digest.

    internal_triggers must be separately observed (table, name) catalog rows
    restricted to tgisinternal=true and the trigger function in pg_catalog.
    An OID-looking name alone is never evidence that a trigger is internal.
    """
    require(isinstance(schema,dict) and set(schema)==set(SCHEMA_QUERIES), 'RESTORE_SCHEMA_FIELDS_INVALID')
    require(isinstance(internal_triggers,(list,tuple)) and all(isinstance(row,(list,tuple))
        and len(row)==2 and all(isinstance(item,str) for item in row) for row in internal_triggers),
        'RESTORE_INTERNAL_TRIGGER_EVIDENCE_REQUIRED')
    names=[tuple(row) for row in internal_triggers]
    require(len(names)==len(set(names)), 'RESTORE_INTERNAL_TRIGGER_EVIDENCE_DUPLICATE')
    normalized=json.loads(json.dumps(schema))
    require(all(isinstance(rows,list) and all(isinstance(row,list) for row in rows)
        for rows in normalized.values()), 'RESTORE_SCHEMA_ROWS_INVALID')
    require(all(len(row)==4 and all(isinstance(item,str) for item in row) for row in normalized['triggers']),
        'RESTORE_TRIGGER_ROWS_INVALID')
    require(set(names)<={(row[0],row[1]) for row in normalized['triggers']}, 'RESTORE_INTERNAL_TRIGGER_EVIDENCE_CHANGED')
    for section in ('constraints','indexes'):
        for row in normalized[section]:
            require(len(row)==(4 if section=='constraints' else 3) and isinstance(row[2],str), 'RESTORE_SCHEMA_ROWS_INVALID')
            row[2]=_restore_array_expression(row[2])
    internal=set(names)
    for row in normalized['triggers']:
        match=_INTERNAL_TRIGGER_NAME.fullmatch(row[1])
        if match and (row[0],row[1]) in internal:
            # pg_get_triggerdef omits the tgconstraint binding. Distinct FKs
            # between the same tables may have identical displayed definitions;
            # erasing OIDs must not hide a swap of their enable/disable states.
            require(row[2]=='O', 'RESTORE_INTERNAL_TRIGGER_NOT_ORIGIN_ENABLED')
            prefix='CREATE CONSTRAINT TRIGGER "'+row[1]+'" '
            require(row[3].startswith(prefix) and re.search(
                r'EXECUTE FUNCTION "RI_FKey_(?:check_ins|check_upd|noaction_del|noaction_upd|restrict_del|restrict_upd|cascade_del|cascade_upd|setnull_del|setnull_upd|setdefault_del|setdefault_upd)"\(\)\Z',row[3]),
                'RESTORE_INTERNAL_TRIGGER_DEFINITION_CHANGED')
            row[1]='RI_ConstraintTrigger_'+match[1]+'_<internal_oid>'
            row[3]='CREATE CONSTRAINT TRIGGER "'+row[1]+'" '+row[3][len(prefix):]
    # OID-based names can reorder equivalent trigger rows after restore. Keep
    # multiplicity; never turn the rows into a set or reorder column/history rows.
    normalized['triggers'].sort(key=lambda row:json.dumps(row,ensure_ascii=False,separators=(',',':')))
    return normalized


def restore_equivalence_digest(schema, *, internal_triggers):
    return content_digest({'version':RESTORE_EQUIVALENCE_VERSION,
        'schema':restore_equivalence_schema(schema,internal_triggers=internal_triggers)})


def database_evidence(connect):
    connection = connect()
    try:
        connection.set_session(readonly=True, isolation_level='REPEATABLE READ')
        with connection, connection.cursor() as cursor:
            cursor.execute("SELECT control.system_identifier::text,d.oid::text,d.datname FROM pg_control_system() control JOIN pg_database d ON d.datname=current_database()")
            row = cursor.fetchone()
            identity = hashlib.sha256(('serving-postgres-v1\n'+'\n'.join(row)+'\n').encode()).hexdigest()
            schema = {}
            for key, query in SCHEMA_QUERIES.items():
                cursor.execute(query)
                schema[key] = [list(item) for item in cursor.fetchall()]
            require(schema['migrations'] and all(row[-1] is True for row in schema['migrations']), 'SCHEMA_MIGRATION_HISTORY_INVALID')
            return {'database_identity': identity, 'schema_digest': content_digest(schema), 'schema': schema}
    finally:
        connection.close()


def file_sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()


def migration_digest(artifact):
    with zipfile.ZipFile(artifact) as jar:
        entries = {name: hashlib.sha256(jar.read(name)).hexdigest() for name in jar.namelist()
                   if name.startswith('BOOT-INF/classes/db/migration/') and name.endswith('.sql')}
    require(bool(entries), 'RECOVERY_MIGRATIONS_REQUIRED')
    return content_digest(entries)


class RecoveryVerifier:
    def __init__(self, connect, receipt_path):
        self.connect, self.receipt_path = connect, Path(receipt_path)

    def __call__(self, build_receipt, live):
        receipt = json.loads(private_file(self.receipt_path).read_text())
        require(receipt.get('kind') == 'ACTUAL_DUMP_RESTORE_AND_STAGED_SCHEMA_V1'
                and receipt.get('restore_verified') is True
                and receipt.get('staged_service_verified') is True, 'ACTUAL_RECOVERY_EVIDENCE_REQUIRED')
        require(receipt.get('receipt_digest') == content_digest({k:v for k,v in receipt.items() if k != 'receipt_digest'}), 'RECOVERY_RECEIPT_CHANGED')
        backup = private_file(Path(receipt['backup_path']))
        require(backup.stat().st_size == receipt['backup_bytes']
                and file_sha(backup) == receipt['backup_sha256'], 'VERIFIED_BACKUP_CHANGED')
        require(file_sha(build_receipt['artifact_path']) == build_receipt['artifact_sha256'], 'RECOVERY_ARTIFACT_CHANGED')
        require(migration_digest(build_receipt['artifact_path']) == receipt['migration_resources_digest'], 'RECOVERY_SCHEMA_COMPATIBILITY_CHANGED')
        current = database_evidence(self.connect)
        require(current['database_identity'] == live['dataops_database_identity'] == receipt['source_database_identity'], 'RECOVERY_DATABASE_CHANGED')
        require(current['schema_digest'] == receipt['validated_schema_digest'], 'RECOVERY_SCHEMA_CHANGED')
        require(receipt['restored_database_identity'] != current['database_identity'], 'INDEPENDENT_RESTORE_REQUIRED')
        return {'schema_compatible': True, 'backup_verified': True,
                'schema_receipt_digest': content_digest({'schema': current['schema_digest'], 'migrations': receipt['migration_resources_digest'],
                    'staged_artifact': receipt['staged_artifact_sha256'], 'receipt': receipt['receipt_digest']}),
                'backup_receipt_digest': receipt['receipt_digest']}

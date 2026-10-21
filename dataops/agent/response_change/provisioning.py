"""Explicit DataOps-only role provisioning, never a serving-database grant.

The admin callback must connect to the migrated DataOps database. Existing roles
are adopted only when the private provisioning receipt matches their SCRAM
verifiers and exact grants. Passwords are never reset. Credentials are persisted
before the transaction commits so an unknown commit can be reconciled safely.
"""
from contextlib import closing
import base64
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets

from psycopg2 import sql

from .approval import content_digest
from .host_runtime import private_directory, private_file, require, write_json

SERVICE = "dataops_service"
WORKER = "dataops_agent_worker"
PUBLISHER = "dataops_agent_publisher"
CONTROLLER = "dataops_agent_controller"
REPLAY = "dataops_replay"
APPLY = "dataops_manifest_apply"
ROLES = (SERVICE, WORKER, PUBLISHER, CONTROLLER, REPLAY, APPLY)

CORE = frozenset("store_sync_state place_snapshot store_field_provenance change_proposal apply_log "
    "new_store_candidate store_signal menu_kind_axis store_profile_draft store_photo_tag menu_kind_label "
    "menu_evidence_observation menu_evidence_legacy_removal kind_evidence_publish_attempt region_resolution "
    "proposal_apply_reconciliation response_capture response_incident response_incident_sample".split())
BUDGET = frozenset("response_agent_budget_policy response_agent_budget_incident response_agent_budget_alias "
    "response_agent_budget_day response_agent_budget_request response_agent_budget_settlement".split())
REPLAY_TABLES = frozenset("response_replay_run response_replay_target response_replay_receipt "
    "response_replay_proposal response_replay_completion".split())
CONTROL = frozenset("response_agent_run response_agent_review_manifest response_agent_review_publication "
    "response_agent_review_approval response_agent_notification_outbox response_agent_release_service "
    "response_agent_release_operation response_agent_data_apply_item response_agent_execution_binding "
    "response_agent_active_runtime response_agent_data_execution response_agent_orchestration_job "
    "response_agent_orchestration_event response_agent_status_notification".split())
TABLES = CORE | BUDGET | REPLAY_TABLES | CONTROL | {"flyway_schema_history"}
MEASUREMENT_ROLES = (SERVICE, CONTROLLER, REPLAY, APPLY)
RLS_TABLES = ("response_agent_review_manifest", "response_agent_notification_outbox")


def privilege_matrix():
    """Table privileges and UPDATE-only lock columns; no broad DEFAULT PRIVILEGES."""
    result = {role:{"tables":{}, "update_columns":{}} for role in ROLES}
    def grant(role, tables, *privileges):
        for table in tables:
            result[role]["tables"].setdefault(table, set()).update(privileges)
    def columns(role, table, *names):
        result[role]["update_columns"].setdefault(table, set()).update(names)
    for role in (SERVICE, REPLAY, APPLY): grant(role, CORE, "SELECT")
    grant(SERVICE, CORE - {"menu_kind_axis","menu_kind_label","menu_evidence_legacy_removal",
        "proposal_apply_reconciliation","response_capture","response_incident","response_incident_sample",
        "menu_evidence_observation","apply_log"}, "INSERT", "UPDATE", "DELETE")
    grant(SERVICE, {"response_capture","response_incident","response_incident_sample","menu_evidence_observation","apply_log"}, "INSERT")
    columns(SERVICE,"response_capture","status","parser_digest","contract_version","diagnostics",
        "target_verified","processed_response","processed_at")
    grant(SERVICE, {"response_replay_proposal"}, "SELECT")

    grant(WORKER, {"response_capture","response_incident","response_incident_sample","response_agent_active_runtime",
        "response_agent_budget_policy"}, "SELECT")
    grant(WORKER, {"response_agent_run"}, "SELECT","INSERT","UPDATE")
    grant(WORKER, {"response_agent_budget_incident","response_agent_budget_day"}, "SELECT","INSERT")
    for table in ("response_agent_budget_incident","response_agent_budget_day"): columns(WORKER,table,"budget_usd")
    grant(WORKER, {"response_agent_budget_alias","response_agent_budget_request","response_agent_budget_settlement",
        *RLS_TABLES}, "SELECT","INSERT")
    grant(PUBLISHER, {"response_agent_review_manifest"}, "SELECT")
    grant(PUBLISHER, {"response_agent_review_publication"}, "SELECT","INSERT")
    grant(PUBLISHER, {"response_agent_notification_outbox"}, "SELECT","UPDATE")
    grant(PUBLISHER, {"response_agent_status_notification"}, "SELECT","UPDATE")

    grant(CONTROLLER, {"response_agent_run","response_capture","response_incident","response_incident_sample",
        "response_agent_review_publication","response_agent_data_apply_item","change_proposal","flyway_schema_history",
        *REPLAY_TABLES}, "SELECT")
    grant(CONTROLLER, {"response_agent_orchestration_job","response_agent_review_manifest","response_agent_review_approval",
        "response_agent_release_service","response_agent_release_operation","response_agent_active_runtime",
        "response_agent_data_execution"}, "SELECT","INSERT","UPDATE")
    grant(CONTROLLER, {"response_agent_orchestration_event","response_agent_execution_binding",
        "response_agent_notification_outbox","response_agent_status_notification"}, "SELECT","INSERT")

    authority = {"response_agent_execution_binding","response_agent_release_operation","response_agent_release_service",
        "response_agent_review_manifest","response_agent_review_approval","response_agent_review_publication",
        "response_agent_active_runtime"}
    for role in (REPLAY, APPLY):
        grant(role, authority | REPLAY_TABLES, "SELECT")
        # PostgreSQL FOR SHARE/UPDATE requires UPDATE on at least one column. The
        # authority values remain unmodifiable; these metadata columns grant locks.
        for table, column in (("response_agent_release_operation","updated_at"),
                ("response_agent_release_service","updated_at"),("response_agent_review_manifest","created_at"),
                ("response_agent_active_runtime","updated_at")):
            columns(role,table,column)
    columns(REPLAY,"response_agent_review_approval","approved_at")
    grant(REPLAY, REPLAY_TABLES, "INSERT")
    columns(REPLAY,"response_replay_run","created_at") # immutable trigger forbids actual updates
    columns(REPLAY,"store_sync_state","store_id") # pessimistic lock; replay never updates observation state
    grant(REPLAY,{"change_proposal"},"INSERT","UPDATE")
    grant(APPLY,{"response_agent_data_apply_item"},"SELECT","INSERT")
    columns(APPLY,"response_agent_review_approval","state","operation_id","consumed_at")
    grant(APPLY,{"change_proposal"},"UPDATE")
    grant(APPLY,{"apply_log"},"INSERT")
    grant(APPLY,{"store_field_provenance"},"INSERT","UPDATE")
    return {role:{"tables":{table:sorted(privs) for table,privs in sorted(value["tables"].items())},
        "update_columns":{table:sorted(cols) for table,cols in sorted(value["update_columns"].items())}}
        for role,value in result.items()}


MATRIX = privilege_matrix()
POLICY_DIGEST = content_digest({"schema_version":1,"roles":MATRIX,"measurement_roles":MEASUREMENT_ROLES,
    "worker_manifest":{"kind":"REPORT_ONLY","scope":"NONE"},"search_path":"public,pg_temp"})


def _database(cursor):
    cursor.execute("SELECT current_database(),oid::text FROM pg_database WHERE datname=current_database()")
    database, oid = cursor.fetchone()
    cursor.execute("SELECT system_identifier::text FROM pg_control_system()")
    return {"database":database,"database_oid":oid,"cluster_identifier":cursor.fetchone()[0]}


def _role_rows(cursor):
    cursor.execute("""SELECT rolname,rolpassword,rolsuper,rolinherit,rolcreaterole,rolcreatedb,rolcanlogin,
        rolreplication,rolbypassrls,rolconnlimit,rolvaliduntil,shobj_description(oid,'pg_authid') FROM pg_authid WHERE rolname=ANY(%s)""", (list(ROLES),))
    return {row[0]:row[1:] for row in cursor.fetchall()}


def _preflight(cursor):
    cursor.execute("SELECT tablename FROM pg_tables WHERE schemaname='public'")
    require(TABLES <= {row[0] for row in cursor.fetchall()},"DATAOPS_MIGRATIONS_REQUIRED")
    cursor.execute("SELECT rolsuper FROM pg_roles WHERE rolname=current_user")
    require(cursor.fetchone()[0],"EXPLICIT_DATAOPS_ADMIN_REQUIRED")
    cursor.execute("""SELECT EXISTS(SELECT 1 FROM pg_namespace n,
        LATERAL aclexplode(COALESCE(n.nspacl,acldefault('n',n.nspowner))) p
        WHERE n.nspname='public' AND p.grantee=0 AND p.privilege_type='CREATE')""")
    require(not cursor.fetchone()[0],"PUBLIC_SCHEMA_CREATE_MUST_BE_REMOVED_EXPLICITLY")
    cursor.execute("""SELECT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace,
        LATERAL aclexplode(COALESCE(c.relacl,acldefault(CASE WHEN c.relkind='S' THEN 'S'::"char" ELSE 'r'::"char" END,c.relowner))) p
        WHERE n.nspname='public' AND c.relkind IN ('r','p','S','v','m','f') AND p.grantee=0)""")
    require(not cursor.fetchone()[0],"PUBLIC_DATA_PRIVILEGES_REQUIRE_EXPLICIT_REVIEW")
    cursor.execute("""SELECT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='public' AND p.prosecdef)""")
    require(not cursor.fetchone()[0],"SECURITY_DEFINER_FUNCTION_REQUIRES_EXPLICIT_REVIEW")


def _rls(cursor):
    cursor.execute("SELECT tablename,policyname FROM pg_policies WHERE schemaname='public' AND tablename=ANY(%s)", (list(RLS_TABLES),))
    require(not cursor.fetchall(),"EXISTING_REVIEW_POLICIES_REQUIRE_RECONCILIATION")
    for table in RLS_TABLES:
        cursor.execute(sql.SQL("ALTER TABLE public.{} ENABLE ROW LEVEL SECURITY").format(sql.Identifier(table)))
        # These policies do not grant table privileges. All non-worker provisioned
        # roles still require their exact table/column GRANT from the matrix.
        cursor.execute(sql.SQL("CREATE POLICY agent_trusted_roles ON public.{} TO {} USING (true) WITH CHECK (true)")
            .format(sql.Identifier(table),sql.SQL(",").join(map(sql.Identifier,(r for r in ROLES if r != WORKER)))))
        condition = sql.SQL("kind='REPORT_ONLY' AND scope='NONE'") if table == RLS_TABLES[0] else sql.SQL(
            "EXISTS(SELECT 1 FROM public.response_agent_review_manifest m WHERE m.manifest_digest=response_agent_notification_outbox.manifest_digest AND m.kind='REPORT_ONLY' AND m.scope='NONE')")
        cursor.execute(sql.SQL("CREATE POLICY agent_report_worker ON public.{} TO {} USING ({}) WITH CHECK ({})")
            .format(sql.Identifier(table),sql.Identifier(WORKER),condition,condition))


def _grant(cursor, database):
    # PG16 exposes this function to PUBLIC by default. A grant to only selected
    # roles has no boundary until PUBLIC is explicitly removed in this database.
    cursor.execute("REVOKE EXECUTE ON FUNCTION pg_catalog.pg_control_system() FROM PUBLIC")
    for role, matrix in MATRIX.items():
        role_id = sql.Identifier(role)
        cursor.execute(sql.SQL("GRANT CONNECT ON DATABASE {} TO {}").format(sql.Identifier(database),role_id))
        cursor.execute(sql.SQL("GRANT USAGE ON SCHEMA public TO {}").format(role_id))
        # Omitted pg_catalog is implicitly searched first. public remains the
        # JDBC current schema (Hibernate validation), while pg_temp is last.
        cursor.execute(sql.SQL("ALTER ROLE {} IN DATABASE {} SET search_path TO public,pg_temp")
            .format(role_id,sql.Identifier(database)))
        for table, privileges in matrix["tables"].items():
            cursor.execute(sql.SQL("GRANT {} ON TABLE public.{} TO {}").format(
                sql.SQL(",").join(map(sql.SQL,privileges)),sql.Identifier(table),role_id))
            if "INSERT" in privileges:
                cursor.execute("""SELECT pg_get_serial_sequence(format('public.%%I',table_name),column_name)
                    FROM information_schema.columns WHERE table_schema='public' AND table_name=%s""", (table,))
                for sequence, in cursor.fetchall():
                    if sequence:
                        cursor.execute(sql.SQL("GRANT USAGE,SELECT ON SEQUENCE {} TO {}").format(sql.SQL(sequence),role_id))
        for table, columns in matrix["update_columns"].items():
            cursor.execute(sql.SQL("GRANT UPDATE ({}) ON TABLE public.{} TO {}").format(
                sql.SQL(",").join(map(sql.Identifier,columns)),sql.Identifier(table),role_id))
        if role in MEASUREMENT_ROLES:
            cursor.execute(sql.SQL("GRANT EXECUTE ON FUNCTION pg_catalog.pg_control_system() TO {}").format(role_id))


def _snapshot(cursor):
    """Capture direct/effective ownership, memberships, grants and RLS for drift detection."""
    cursor.execute("""SELECT a.rolname,b.rolname,m.admin_option FROM pg_auth_members m
        JOIN pg_roles a ON a.oid=m.roleid JOIN pg_roles b ON b.oid=m.member
        WHERE a.rolname=ANY(%s) OR b.rolname=ANY(%s) ORDER BY 1,2""",(list(ROLES),list(ROLES)))
    require(not cursor.fetchall(),"MANAGED_ROLE_MEMBERSHIP_FORBIDDEN")
    cursor.execute("""SELECT EXISTS(SELECT 1 FROM pg_shdepend d JOIN pg_roles r ON r.oid=d.refobjid
        WHERE r.rolname=ANY(%s) AND d.deptype='o')""",(list(ROLES),))
    require(not cursor.fetchone()[0],"MANAGED_ROLE_OWNERSHIP_FORBIDDEN")
    cursor.execute("""SELECT n.nspname,c.relname,c.relkind,r.rolname,p.privilege_type,p.is_grantable,NULL::text
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace,
        LATERAL aclexplode(c.relacl) p JOIN pg_roles r ON r.oid=p.grantee
        WHERE r.rolname=ANY(%s)
        UNION ALL
        SELECT n.nspname,c.relname,c.relkind,r.rolname,p.privilege_type,p.is_grantable,a.attname
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid,
        LATERAL aclexplode(a.attacl) p JOIN pg_roles r ON r.oid=p.grantee
        WHERE a.attnum>0 AND NOT a.attisdropped AND r.rolname=ANY(%s) ORDER BY 1,2,3,4,5,6,7""",(list(ROLES),list(ROLES)))
    grants = cursor.fetchall()
    cursor.execute("SELECT tablename,policyname,roles,cmd,qual,with_check FROM pg_policies WHERE schemaname='public' AND tablename=ANY(%s) ORDER BY 1,2",(list(RLS_TABLES),))
    policies = cursor.fetchall()
    cursor.execute("SELECT relname,relrowsecurity,relforcerowsecurity FROM pg_class WHERE oid IN ('public.response_agent_review_manifest'::regclass,'public.response_agent_notification_outbox'::regclass) ORDER BY 1")
    row_security = cursor.fetchall()
    cursor.execute("""SELECT r.rolname,d.datname,s.setconfig FROM pg_db_role_setting s JOIN pg_roles r ON r.oid=s.setrole
        LEFT JOIN pg_database d ON d.oid=s.setdatabase WHERE r.rolname=ANY(%s) ORDER BY 1,2""",(list(ROLES),))
    settings = cursor.fetchall()
    cursor.execute("SELECT rolname,has_schema_privilege(rolname,'public','CREATE'),has_function_privilege(rolname,'pg_control_system()','EXECUTE'),has_database_privilege(rolname,current_database(),'CREATE') FROM pg_roles WHERE rolname=ANY(%s) ORDER BY 1",(list(ROLES),))
    effective = cursor.fetchall()
    require(all(not row[1] and row[2] == (row[0] in MEASUREMENT_ROLES) and not row[3] for row in effective),"ROLE_PRIVILEGE_BOUNDARY_CHANGED")
    return content_digest({"grants":grants,"policies":policies,"row_security":row_security,"settings":settings,"effective":effective})


def _password_matches(password, verifier):
    """Verify ASCII generated secrets locally against PostgreSQL's saved SCRAM verifier."""
    try:
        algorithm,params,keys = verifier.split("$")
        iterations,salt = params.split(":");stored,server = keys.split(":")
        if algorithm != "SCRAM-SHA-256" or not 4096 <= int(iterations) <= 1000000: return False
        salted = hashlib.pbkdf2_hmac("sha256",password.encode("ascii"),base64.b64decode(salt,validate=True),int(iterations))
        actual_stored = hashlib.sha256(hmac.digest(salted,b"Client Key","sha256")).digest()
        actual_server = hmac.digest(salted,b"Server Key","sha256")
        return hmac.compare_digest(actual_stored,base64.b64decode(stored,validate=True)) and hmac.compare_digest(actual_server,base64.b64decode(server,validate=True))
    except (AttributeError,TypeError,ValueError,UnicodeError):
        return False


def provision_roles(admin_connect, credentials_path):
    """Provision/reconcile exactly six local roles; return metadata without secrets.

    The caller owns migration/backup approval and supplies an explicit DataOps
    admin connection. This function never connects to another database.
    """
    path = Path(credentials_path)
    require(path.is_absolute() and path.name not in {"", ".", ".."},"ABSOLUTE_CREDENTIALS_PATH_REQUIRED")
    private_directory(path.parent)
    fd = os.open(path.with_name(path.name+".lock"),os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
    try:
        fcntl.flock(fd,fcntl.LOCK_EX)
        with closing(admin_connect()) as db:
            require(not db.autocommit,"PROVISIONING_TRANSACTION_REQUIRED")
            with db,db.cursor() as cursor:
                cursor.execute("SELECT pg_advisory_xact_lock(6428913762104791)")
                _preflight(cursor)
                identity = _database(cursor); existing = _role_rows(cursor)
                if path.exists():
                    receipt = json.loads(private_file(path).read_text())
                    require(receipt.get("schema_version")==1 and receipt.get("policy_digest")==POLICY_DIGEST
                        and receipt.get("database")==identity and set(receipt.get("roles",{}))==set(ROLES)
                        and receipt.get("state") in {"PREPARED","APPLIED"},"CREDENTIALS_BINDING_CHANGED")
                else:
                    require(not existing,"EXISTING_ROLE_WITHOUT_CREDENTIALS")
                    receipt = {"schema_version":1,"state":"PREPARED","policy_digest":POLICY_DIGEST,
                        "database":identity,"provisioning_id":secrets.token_hex(16),
                        "roles":{role:{"password":secrets.token_urlsafe(48)} for role in ROLES}}
                    write_json(path,receipt)
                marker = "bbanggut-dataops-agent:"+receipt["provisioning_id"]
                if existing:
                    require(set(existing)==set(ROLES),"PARTIAL_EXISTING_ROLES_REQUIRE_RECONCILIATION")
                    for role,row in existing.items():
                        require(row == (receipt["roles"][role].get("verifier"),False,False,False,False,True,False,False,-1,None,marker),
                            "EXISTING_ROLE_OR_CREDENTIAL_CHANGED")
                        require(_password_matches(receipt["roles"][role].get("password"),row[0]),"EXISTING_ROLE_OR_CREDENTIAL_CHANGED")
                    require(_snapshot(cursor)==receipt.get("grants_digest"),"ROLE_GRANTS_CHANGED")
                else:
                    require(receipt["state"]=="PREPARED","PROVISIONED_ROLES_DISAPPEARED")
                    cursor.execute("SET LOCAL password_encryption='scram-sha-256'")
                    for role in ROLES:
                        password = receipt["roles"][role].get("password")
                        require(isinstance(password,str) and len(password)>=48,"PRIVATE_CREDENTIAL_INVALID")
                        cursor.execute(sql.SQL("CREATE ROLE {} LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %s")
                            .format(sql.Identifier(role)),(password,))
                        cursor.execute(sql.SQL("COMMENT ON ROLE {} IS %s").format(sql.Identifier(role)),(marker,))
                    _rls(cursor);_grant(cursor,identity["database"])
                    for role,row in _role_rows(cursor).items(): receipt["roles"][role]["verifier"]=row[0]
                    receipt["grants_digest"]=_snapshot(cursor)
                    write_json(path,receipt) # password/verifier evidence precedes database COMMIT
            receipt["state"]="APPLIED";write_json(path,receipt)
            return {"state":"APPLIED","database":identity,"roles":list(ROLES),"policy_digest":POLICY_DIGEST,
                "grants_digest":receipt["grants_digest"],"credentials_path":str(path),"serving_connections":0,
                "serving_grants":0,"passwords_reset":0}
    finally:
        fcntl.flock(fd,fcntl.LOCK_UN);os.close(fd)

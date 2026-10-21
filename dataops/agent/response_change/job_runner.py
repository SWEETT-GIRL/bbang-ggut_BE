"""One bounded launchd job from a frozen code installation and scoped secrets.

This entry point does not install schedules or approve anything. launchd owns
the calendar/interval. Child domain journals, not this process's exit code, are
the authority for paid calls, publication, deployment and data writes.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys

from .approval import ID, COMMIT, SHA, content_digest
from .host_runtime import private_directory, private_file, require, write_json
from .release import ReleaseHold

KINDS = frozenset({'analysis','orchestration','publisher','status','collection'})
SECRET_KEYS = {'analysis': {'AGENT_DISPATCH_DSN','ANTHROPIC_API_KEY'},
    'orchestration': {'AGENT_ORCHESTRATION_DSN'}, 'publisher': {'AGENT_PUBLICATION_DSN'},
    'status': {'AGENT_PUBLICATION_DSN'}, 'collection': set()}
CONFIG_FIELDS = frozenset({'job_id','kind','installation_receipt','repository_path','state_directory','secrets_path','settings'})
SAFE_PATH = '/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin'


def verify_installation(path, *, require_executing=True):
    receipt=json.loads(private_file(Path(path)).read_text())
    require(isinstance(receipt,dict) and receipt.get('kind')=='FROZEN_CONTROLLER_SOURCE_V1'
        and isinstance(receipt.get('source_commit'),str) and COMMIT.fullmatch(receipt['source_commit']) and
        receipt.get('receipt_digest')==content_digest({k:v for k,v in receipt.items() if k!='receipt_digest'}), 'INSTALLATION_RECEIPT_CHANGED')
    require(isinstance(receipt.get('code_directory'),str),'INSTALLATION_PATH_INVALID')
    root=Path(receipt['code_directory'])
    require(root.is_absolute() and root.resolve()==root and root.is_dir() and root.stat().st_uid==os.getuid()
        and root.stat().st_mode&0o022==0,'INSTALLATION_PATH_INVALID')
    require(isinstance(receipt.get('files'),dict) and bool(receipt['files']),'INSTALLATION_FILE_INVENTORY_REQUIRED')
    if require_executing:
        require(Path(__file__).resolve().is_relative_to(root)
            and str(Path(__file__).resolve().relative_to(root)) in receipt['files'],'EXECUTING_INSTALLATION_MISMATCH')
    for name,expected in receipt['files'].items():
        require(isinstance(name,str) and isinstance(expected,str) and SHA.fullmatch(expected),'INSTALLATION_ENTRY_INVALID')
        relative=Path(name)
        require(not relative.is_absolute() and '..' not in relative.parts and relative.parts
            and all(ord(char)>=32 for char in name),'INSTALLATION_ENTRY_INVALID')
        file=root/relative
        require(file.is_file() and not file.is_symlink() and file.resolve()==file and file.stat().st_uid==os.getuid()
            and file.stat().st_mode&0o022==0 and hashlib.sha256(file.read_bytes()).hexdigest()==expected,'INSTALLED_SOURCE_CHANGED')
    actual=set()
    for directory,folders,files in os.walk(root,followlinks=False):
        require(not any((Path(directory)/folder).is_symlink() for folder in folders),'INSTALLATION_SYMLINK')
        actual.update(str((Path(directory)/name).relative_to(root)) for name in files)
    require(actual==set(receipt['files']),'INSTALLATION_FILE_INVENTORY_CHANGED')
    return root


def configuration(path):
    value=json.loads(private_file(Path(path)).read_text())
    require(isinstance(value,dict) and set(value)==CONFIG_FIELDS,'JOB_CONFIGURATION_FIELDS')
    require(isinstance(value['kind'],str) and value['kind'] in KINDS
        and isinstance(value['job_id'],str) and ID.fullmatch(value['job_id']),'JOB_KIND_OR_ID')
    for name in ('installation_receipt','repository_path','state_directory','secrets_path'):
        require(isinstance(value[name],str) and Path(value[name]).is_absolute()
            and not any(ord(char)<32 for char in value[name]),'ABSOLUTE_JOB_PATH_REQUIRED')
    require(isinstance(value['settings'],dict),'JOB_SETTINGS_REQUIRED')
    return value


def environment(config):
    values=json.loads(private_file(Path(config['secrets_path'])).read_text())
    require(isinstance(values,dict) and set(values)==SECRET_KEYS[config['kind']]
        and all(isinstance(v,str) and v and '\x00' not in v for v in values.values()),'SCOPED_JOB_SECRETS_REQUIRED')
    # Do not inherit an interactive shell's model key, serving password or DB URL.
    result={'HOME':str(Path.home()),'PATH':SAFE_PATH,'LANG':'en_US.UTF-8','TZ':'Asia/Seoul',
        'PYTHONDONTWRITEBYTECODE':'1','PYTHONUNBUFFERED':'1','PYTHONNOUSERSITE':'1',**values}
    if config['kind'] in {'analysis','orchestration'}:
        result['AGENT_RUNTIME_CACHE_DIRECTORY']=str(Path(config['state_directory']).parent/'runtime-cache')
    return result


def command(config,code_root):
    kind,settings=config['kind'],config['settings'];prefix=[sys.executable]
    repo=['--repository-path',config['repository_path']]
    def fields(expected):require(set(settings)==set(expected),'JOB_SETTINGS_FIELDS')
    def absolute(*names):
        for name in names:require(isinstance(settings[name],str) and Path(settings[name]).is_absolute()
            and not any(ord(char)<32 for char in settings[name]),'ABSOLUTE_JOB_SETTING_REQUIRED')
    if kind=='analysis':
        fields(('baseline_pointer','registry_directory','service_id','review_config','output_directory'))
        absolute('baseline_pointer','registry_directory','review_config','output_directory')
        require(isinstance(settings['service_id'],str) and ID.fullmatch(settings['service_id']),'SERVICE_ID_INVALID')
        return prefix+['-m','agent.response_change.worker_cli',*repo,'--baseline-pointer',settings['baseline_pointer'],
            '--registry-directory',settings['registry_directory'],'--service-id',settings['service_id'],
            '--review-config',settings['review_config'],'--output-dir',settings['output_directory'],'--live','--budget-usd','0.50']
    if kind=='orchestration':
        fields(('configuration',));absolute('configuration')
        return prefix+['-m','agent.response_change.orchestration_cli','--configuration',settings['configuration'],'--live']
    if kind in {'publisher','status'}:
        fields(('repository','review_kind') if kind=='publisher' else ('repository',))
        from .approval import REPO
        require(isinstance(settings['repository'],str) and REPO.fullmatch(settings['repository']),'REPOSITORY_INVALID')
        module='publication_cli' if kind=='publisher' else 'status_publication_cli'
        args=prefix+['-m','agent.response_change.'+module,*repo,'--repository',settings['repository'],'--live']
        if kind=='publisher':
            require(isinstance(settings['review_kind'],str) and settings['review_kind'] in {'REPORT_ONLY','PARSER_REPAIR','DATA_APPLY'},'REVIEW_KIND_INVALID')
            args+=['--kind',settings['review_kind']]
        return args
    fields(('server_port','max_hours','max_stores','spool_directory','spool_max_bytes'))
    absolute('spool_directory')
    require(type(settings['server_port']) is int and 1024<=settings['server_port']<=65535,'COLLECTION_PORT_INVALID')
    require(type(settings['max_hours']) in (int,float) and 0<settings['max_hours']<=2,'COLLECTION_TIME_BOUND')
    require(settings['max_stores'] is None or type(settings['max_stores']) is int and 1<=settings['max_stores']<=10000,'COLLECTION_COUNT_BOUND')
    require(type(settings['spool_max_bytes']) is int and 1024*1024<=settings['spool_max_bytes']<=10*1024**3,'SPOOL_QUOTA_BOUND')
    args=prefix+[str(code_root/'scripts/sync_worker.py'),'--server',f"http://127.0.0.1:{settings['server_port']}",
        '--limit','50','--max-hours',str(settings['max_hours'])]
    if settings['max_stores'] is not None:args+=['--max',str(settings['max_stores'])]
    return args


@contextmanager
def job_lock(directory):
    path=private_directory(directory)/'job.lock'
    fd=os.open(path,os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
    try:
        info=os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid==os.getuid() and stat.S_IMODE(info.st_mode)==0o600
            and info.st_nlink==1,'OWNED_JOB_LOCK_REQUIRED')
        try:fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise ReleaseHold('JOB_ALREADY_RUNNING') from None
        yield
    finally:fcntl.flock(fd,fcntl.LOCK_UN);os.close(fd)


def private_log(path):
    """Open/create a private regular log without ever following a symlink."""
    path=Path(path)
    require(path.is_absolute() and path.parent.resolve()==path.parent,'OWNED_LOG_DIRECTORY_REQUIRED')
    descriptor=os.open(path,os.O_WRONLY|os.O_APPEND|os.O_CREAT|os.O_NOFOLLOW,0o600)
    try:
        info=os.fstat(descriptor)
        require(stat.S_ISREG(info.st_mode) and info.st_uid==os.getuid() and stat.S_IMODE(info.st_mode)==0o600
            and info.st_nlink==1,'PRIVATE_JOB_LOG_REQUIRED')
        return os.fdopen(descriptor,'ab')
    except BaseException:
        os.close(descriptor);raise


def _stop_child(process):
    # Popen created this session. Never discover/kill any unrelated PID.
    if process.poll() is not None:return
    try:os.killpg(process.pid,signal.SIGTERM)
    except ProcessLookupError:return
    try:process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        try:os.killpg(process.pid,signal.SIGKILL)
        except ProcessLookupError:pass
        process.wait(timeout=5)


def _run_child(arguments,root,env,output,timeout):
    process=subprocess.Popen(arguments,cwd=root,env=env,stdin=subprocess.DEVNULL,stdout=output,
        stderr=subprocess.STDOUT,start_new_session=True)
    try:
        return process.wait(timeout=timeout),False
    except subprocess.TimeoutExpired:
        _stop_child(process)
        return 124,True
    finally:
        if process.poll() is None:_stop_child(process)


def run(config):
    root=verify_installation(config['installation_receipt'])
    args=command(config,root);env=environment(config)
    if config['kind']=='collection':
        env['DATAOPS_CAPTURE_DIR']=config['settings']['spool_directory']
        env['DATAOPS_CAPTURE_MAX_BYTES']=str(config['settings']['spool_max_bytes'])
    state=Path(config['state_directory'])
    with job_lock(state):
        log=state/'child.log'
        # Validate even an empty existing log before stat/rotation or opening it.
        with private_log(log):pass
        if log.exists() and log.stat().st_size>10*1024*1024:
            prior=state/'child.previous.log'
            require(not log.is_symlink() and not prior.is_symlink(),'JOB_LOG_SYMLINK')
            if prior.exists():
                with private_log(prior):pass
            os.replace(log,prior)
        receipt={'job_id':config['job_id'],'kind':config['kind'],'started_at':datetime.now(timezone.utc).isoformat(),'state':'RUNNING'}
        write_json(state/'last-run.json',receipt)
        timeout={'analysis':3600,'orchestration':1800,'publisher':600,'status':600,
            'collection':max(1,float(config['settings'].get('max_hours',2))*3600-10)}[config['kind']]
        with private_log(log) as output:
            exit_code,timed_out=_run_child(args,root,env,output,timeout)
        receipt.update(state='PROCESS_OUTCOME_UNKNOWN' if timed_out else 'PROCESS_EXITED',exit_code=exit_code,ended_at=datetime.now(timezone.utc).isoformat(),
            domain_result_authority='DURABLE_DOMAIN_JOURNALS')
        write_json(state/'last-run.json',receipt)
        return receipt


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configuration',required=True,type=Path)
    parser.add_argument('--configuration-digest',help='Immutable launchd binding for this exact private job configuration')
    parser.add_argument('--installation-receipt-digest',help='Immutable launchd binding for source provenance')
    parser.add_argument('--python-digest',help='Immutable launchd binding for the venv interpreter binary')
    parser.add_argument('--live',action='store_true')
    args=parser.parse_args(argv);os.umask(0o077)
    try:
        config=configuration(args.configuration)
        if args.configuration_digest is not None:
            require(isinstance(args.configuration_digest,str) and SHA.fullmatch(args.configuration_digest)
                and hashlib.sha256(private_file(args.configuration).read_bytes()).hexdigest()==args.configuration_digest,'FROZEN_JOB_CONFIGURATION_CHANGED')
        if args.installation_receipt_digest is not None:
            require(SHA.fullmatch(args.installation_receipt_digest)
                and hashlib.sha256(private_file(Path(config['installation_receipt'])).read_bytes()).hexdigest()==args.installation_receipt_digest,'FROZEN_INSTALLATION_RECEIPT_CHANGED')
        if args.python_digest is not None:
            require(SHA.fullmatch(args.python_digest) and hashlib.sha256(Path(sys.executable).read_bytes()).hexdigest()==args.python_digest,'FROZEN_PYTHON_CHANGED')
        if not args.live:
            verify_installation(config['installation_receipt']);command(config,Path(__file__).resolve().parents[2]);environment(config)
            print(json.dumps({'state':'PREVIEW','job_id':config['job_id'],'kind':config['kind'],'effects':0}));return 0
        result=run(config);print(json.dumps(result));return 0 if result['exit_code']==0 else 1
    except (ReleaseHold,ValueError,OSError,TypeError,KeyError):
        print(json.dumps({'state':'HELD','code':'JOB_CONFIGURATION_OR_EXECUTION_UNAVAILABLE'}));return 2

if __name__=='__main__':raise SystemExit(main())

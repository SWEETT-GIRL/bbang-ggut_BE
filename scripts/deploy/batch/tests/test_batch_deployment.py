"""배치 EC2 배포·실행 스크립트 — AWS 인증·네트워크 없이 검사한다."""
import base64
import gzip
import json
from pathlib import Path
import re
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import deploy  # noqa: E402
import jdbc_env  # noqa: E402
import runner  # noqa: E402

IMAGE = 'kimnayoungg/bbang-ggut-be@sha256:' + 'a' * 64
PREFIX = '/sweetgirl/prod/v2/batch'
FIREBASE = json.dumps({'project_id': 'p', 'private_key': 'k', 'client_email': 'e'})


class FakeSsm:

    def __init__(self, values, types=None):
        self.values = values
        self.types = types or {}

    def get_parameters(self, Names, WithDecryption):
        assert WithDecryption and len(Names) <= 10
        return {'Parameters': [{'Name': name, 'Value': self.values[name], 'Type': self.types.get(name, 'SecureString')}
                               for name in Names if name in self.values]}


def parameters(**env):
    values = {f'{PREFIX}/env/{key}': 'value' for key in runner.REQUIRED}
    values.update({f'{PREFIX}/env/{key}': value for key, value in env.items()})
    values.update({f'{PREFIX}/firebase-service-account': FIREBASE,
                   f'{PREFIX}/dockerhub/username': 'kimnayoungg', f'{PREFIX}/dockerhub/token': 'token'})
    return values


class DeployTest(unittest.TestCase):

    def test_인스턴스_이미지_파라미터_경로를_검사한다(self):
        deploy.validate('i-0743d492943fec320', IMAGE, PREFIX)
        for bad in [('i-xyz', IMAGE, PREFIX), ('i-0743d492943fec320', 'kimnayoungg/bbang-ggut-be:latest', PREFIX),
                    ('i-0743d492943fec320', IMAGE, '/sweetgirl/prod/v2/was'), ('i-0743d492943fec320', IMAGE, PREFIX + '/')]:
            with self.assertRaises(ValueError, msg=bad):
                deploy.validate(*bad)

    def test_설치_스크립트는_이미지와_경로_runner_만_담는다(self):
        script = deploy.install_script(IMAGE, PREFIX)
        encoded = re.findall(r"printf '%s' '([A-Za-z0-9+/=]+)'", script)
        config = json.loads(base64.b64decode(encoded[0]))
        installed_runner = gzip.decompress(base64.b64decode(encoded[1]))

        self.assertEqual(config, {'region': 'ap-northeast-2', 'parameter_prefix': PREFIX, 'image': IMAGE})
        self.assertEqual(installed_runner, (Path(deploy.__file__).with_name('runner.py')).read_bytes())
        self.assertIn('bbang-batch install', script)
        self.assertNotIn('token', script.lower().replace('--password-stdin', ''))


class FakeDynamo:

    class exceptions:
        class ConditionalCheckFailedException(Exception):
            pass

    def __init__(self, owner=None):
        self.owner = owner
        self.puts = 0

    def put_item(self, TableName, Item, ConditionExpression):
        assert TableName == deploy.LOCK_TABLE and ConditionExpression == 'attribute_not_exists(lock_id)'
        self.puts += 1
        if self.owner is not None:
            raise self.exceptions.ConditionalCheckFailedException()
        self.owner = Item['owner']['S']

    def delete_item(self, TableName, Key, ConditionExpression, ExpressionAttributeNames, ExpressionAttributeValues):
        if self.owner != ExpressionAttributeValues[':owner']['S']:
            raise self.exceptions.ConditionalCheckFailedException()
        self.owner = None


class DeployLeaseTest(unittest.TestCase):

    def test_락_주인은_배포_접두어와_실행_번호다(self):
        self.assertEqual(deploy.lease_owner({'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '2'}), 'github-deploy:42-2')

    def test_비어_있으면_잡고_내_락만_놓는다(self):
        dynamo = FakeDynamo()
        deploy.acquire_lease(dynamo, 'i-0743d492943fec320', 'github-deploy:1-1')
        self.assertEqual(dynamo.owner, 'github-deploy:1-1')

        deploy.release_lease(dynamo, 'i-0743d492943fec320', 'github-deploy:other')
        self.assertEqual(dynamo.owner, 'github-deploy:1-1')
        deploy.release_lease(dynamo, 'i-0743d492943fec320', 'github-deploy:1-1')
        self.assertIsNone(dynamo.owner)

    def test_예약_작업이_쥐고_있으면_기다리다_실패한다(self):
        dynamo = FakeDynamo(owner='arn:aws:states:ap-northeast-2:1:execution:sweetgirl-batch-lifecycle:x')
        with self.assertRaises(TimeoutError):
            deploy.acquire_lease(dynamo, 'i-0743d492943fec320', 'github-deploy:1-1', wait_seconds=0, poll_seconds=0)
        self.assertEqual(dynamo.puts, 1)


class RunnerArgumentsTest(unittest.TestCase):

    def test_작업_이름은_kebab_case_만_받는다(self):
        self.assertEqual(runner.container_args(['job', 'ranking-snapshot'])[:2], (['job', 'ranking-snapshot'], 'ranking-snapshot'))
        for bad in (['job'], ['job', 'a;rm -rf /'], ['job', 'Ranking'], ['job', 'a', 'b'], ['shell'], []):
            with self.assertRaises(ValueError, msg=bad):
                runner.container_args(bad)

    def test_dataops_명령은_스크립트_이름과_시간만_받는다(self):
        self.assertEqual(runner.container_args(['dataops-nightly', '6'])[0], ['dataops-nightly', '6'])
        self.assertEqual(runner.container_args(['dataops', 'approve_fill_pending.py', '--max', '10'])[0],
                         ['dataops', 'approve_fill_pending.py', '--max', '10'])
        for bad in (['dataops-nightly', '6; reboot'], ['dataops', '../x.py'], ['dataops', 'run.sh'], ['dataops']):
            with self.assertRaises(ValueError, msg=bad):
                runner.container_args(bad)

    def test_docker_실행_인자(self):
        job, job_logs = runner.docker_run_args(IMAGE, '/tmp/x/app.env', '/tmp/x/firebase.json', '/opt/logs',
                                               ['job', 'ranking-snapshot'])
        nightly, nightly_logs = runner.docker_run_args(IMAGE, '/tmp/x/app.env', '/tmp/x/firebase.json', '/opt/logs',
                                                       ['dataops-nightly'])

        self.assertEqual(job[-3:], [IMAGE, 'job', 'ranking-snapshot'])
        self.assertIn('bbang-batch.job=ranking-snapshot', job)
        self.assertIn('--rm', job)
        self.assertFalse(job_logs)
        self.assertTrue(nightly_logs)
        self.assertIn('type=bind,source=/opt/logs,target=/app/dataops/dataops-logs', nightly)


class RunnerParametersTest(unittest.TestCase):

    def test_마이그레이션_계정은_마이그레이션_명령에만_전달한다(self):
        env = {'DATAOPS_DB_URL': 'jdbc:postgresql://db.internal/dataops?currentSchema=public&sslmode=require',
               'DATAOPS_DB_USERNAME': 'dataops_service', 'DATAOPS_DB_PASSWORD': 'runtime', 'DATAOPS_API_KEY': 'api',
               'DATAOPS_MIGRATION_DB_USERNAME': 'dataops_migrator', 'DATAOPS_MIGRATION_DB_PASSWORD': 'ddl',
               'DB_PASSWORD': 'serving'}
        migration = runner.execution_environment(env, ['dataops-migrate'])
        self.assertEqual(set(migration), {'DATAOPS_DB_URL'} | runner.MIGRATION_SECRETS)
        runtime = runner.execution_environment(env, ['dataops-nightly'])
        self.assertFalse(runner.MIGRATION_SECRETS & runtime.keys())
        self.assertFalse(runner.MIGRATION_SECRETS & runner.execution_environment(env, ['job', 'ranking-snapshot']).keys())

    def test_서비스_DB_또는_다른_스키마로_잘못_기동하지_않는다(self):
        env = {key: 'value' for key in runner.DATAOPS_REQUIRED | runner.MIGRATION_SECRETS}
        for url in ('jdbc:postgresql://db/soboro?currentSchema=dataops',
                    'jdbc:postgresql://db/dataops?currentSchema=other',
                    'jdbc:postgresql://db/dataops?user=sweetgirl'):
            env['DATAOPS_DB_URL'] = url
            for command in (['dataops-migrate'], ['dataops-nightly']):
                with self.assertRaises(ValueError):
                    runner.execution_environment(env, command)
        self.assertEqual(runner.container_args(['dataops-migrate']), (['dataops-migrate'], 'dataops-migrate', False))
        with self.assertRaises(ValueError):
            runner.container_args(['dataops-migrate', '--clean'])

    def test_허용한_이름만_읽고_필수값을_검사한다(self):
        env, firebase, hub = runner.load_parameters(FakeSsm(parameters(DATAOPS_API_KEY='k')), PREFIX)

        self.assertEqual(env['DATAOPS_API_KEY'], 'k')
        self.assertTrue(runner.REQUIRED <= env.keys())
        self.assertEqual(firebase['project_id'], 'p')
        self.assertEqual(hub['username'], 'kimnayoungg')

    def test_필수값이_없거나_SecureString_이_아니면_실패한다(self):
        values = parameters()
        del values[f'{PREFIX}/env/DB_URL']
        with self.assertRaisesRegex(ValueError, 'Missing runtime parameters'):
            runner.load_parameters(FakeSsm(values), PREFIX)
        with self.assertRaisesRegex(ValueError, 'SecureString'):
            runner.load_parameters(FakeSsm(parameters(), {f'{PREFIX}/env/DB_URL': 'String'}), PREFIX)

    def test_env_file_을_깨는_값은_거부한다(self):
        with self.assertRaisesRegex(ValueError, 'Invalid env-file value'):
            runner.load_parameters(FakeSsm(parameters(DATAOPS_API_KEY='a\nB=c')), PREFIX)

    def test_설정은_digest_고정_이미지와_batch_경로만_받는다(self):
        runner.validate_config({'region': 'ap-northeast-2', 'parameter_prefix': PREFIX, 'image': IMAGE})
        with self.assertRaises(ValueError):
            runner.validate_config({'region': 'ap-northeast-2', 'parameter_prefix': '/sweetgirl/prod/v2/was', 'image': IMAGE})


class JdbcEnvTest(unittest.TestCase):

    def test_Python_접속도_JDBC_TLS_정책을_유지한다(self):
        out = jdbc_env.exports({'DATAOPS_DB_URL': 'jdbc:postgresql://db:5432/dataops?currentSchema=public&sslmode=require'})
        self.assertIn('export DATAOPS_DB_NAME=dataops\n', out)
        self.assertIn('export DATAOPS_DB_SCHEMA=public\n', out)
        self.assertIn('export DATAOPS_DB_SSLMODE=require\n', out)
        with self.assertRaises(ValueError):
            jdbc_env.exports({'DATAOPS_DB_URL': 'jdbc:postgresql://db/dataops?sslmode=disable'})

    def test_JDBC_URL_을_Python_접속_변수로_푼다(self):
        out = jdbc_env.exports({
            'DATAOPS_DB_URL': 'jdbc:postgresql://db.internal:5432/soboro?currentSchema=dataops',
            'DATAOPS_DB_USERNAME': 'dataops_service', 'DATAOPS_DB_PASSWORD': "p'w",
            'DB_URL': 'jdbc:postgresql://db.internal/soboro',
        })
        self.assertIn('export DATAOPS_DB_HOST=db.internal\n', out)
        self.assertIn('export DATAOPS_DB_SCHEMA=dataops\n', out)
        self.assertIn('export DATAOPS_DB_PASSWORD=\'p\'"\'"\'w\'\n', out)
        self.assertIn('export PROD_DB_PORT=5432\n', out)

    def test_값이_없으면_아무것도_내보내지_않고_이상한_URL_은_거부한다(self):
        self.assertEqual(jdbc_env.exports({}), '')
        with self.assertRaises(ValueError):
            jdbc_env.exports({'DB_URL': 'jdbc:postgresql://host/db;$(reboot)'})


if __name__ == '__main__':
    unittest.main()

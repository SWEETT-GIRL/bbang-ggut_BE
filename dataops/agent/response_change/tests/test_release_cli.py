"""A published binding keeps its repository/PR/head/manifest identities distinct."""
import unittest
from unittest.mock import Mock, patch

from agent.response_change.release_cli import load_review
from agent.response_change.release import ReleaseHold


class ReleaseCliTest(unittest.TestCase):
    def test_database_publication_is_bound_by_named_fields(self):
        digest = 'a' * 64
        cursor = Mock(); cursor.__enter__ = Mock(return_value=cursor); cursor.__exit__ = Mock(return_value=False)
        cursor.fetchone.return_value = {'document': {'fixture': True}, 'repository': 'owner/repository', 'pr_number': 12, 'head_sha': 'b' * 40}
        connection = Mock(); connection.__enter__ = Mock(return_value=connection); connection.__exit__ = Mock(return_value=False)
        connection.cursor.return_value = cursor
        manifest = Mock(digest=digest)
        with patch('agent.response_change.release_cli.ReviewManifest', return_value=manifest):
            returned, binding = load_review(lambda: connection, digest)
        self.assertIs(manifest, returned)
        self.assertEqual(('owner/repository', 12, 'b' * 40, digest),
            (binding.repository, binding.pr_number, binding.head_sha, binding.manifest_digest))
        connection.set_session.assert_called_once_with(readonly=True)
        connection.close.assert_called_once()
    def test_invalid_digest_does_not_connect(self):
        connect = Mock()
        with self.assertRaises(ReleaseHold): load_review(connect, '../../manifest')
        connect.assert_not_called()

if __name__ == '__main__': unittest.main()

"""dataops 서버 API 키 헤더 — 배치 EC2 에서는 키가 있어야 서버가 받는다 (네트워크 없음)."""
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import dataops_server  # noqa: E402
import sync_worker  # noqa: E402


class ServerHeadersTest(unittest.TestCase):

    def test_키가_있으면_헤더를_붙인다(self):
        with patch.dict(os.environ, {"DATAOPS_API_KEY": "secret"}):
            self.assertEqual(dataops_server.server_headers(), {"X-Dataops-Key": "secret"})

    def test_키가_없으면_헤더를_붙이지_않는다_로컬_서버(self):
        with patch.dict(os.environ, {"DATAOPS_API_KEY": ""}):
            self.assertEqual(dataops_server.server_headers({"Accept": "application/json"}), {"Accept": "application/json"})

    def test_워커가_due_를_부를_때_키를_보낸다(self):
        response = unittest.mock.Mock()
        response.json.return_value = {"data": []}
        with patch.dict(os.environ, {"DATAOPS_API_KEY": "secret"}), \
                patch.object(sync_worker.requests, "get", return_value=response) as get:
            sync_worker.fetch_due("http://localhost:8090", 5)
        self.assertEqual(get.call_args.kwargs["headers"], {"X-Dataops-Key": "secret"})


if __name__ == "__main__":
    unittest.main()

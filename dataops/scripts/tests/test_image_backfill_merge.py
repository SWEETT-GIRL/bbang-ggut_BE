"""Offline regressions for the image worker's two merged change sets."""
import ast
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, Mock, patch


SOURCE = Path(__file__).resolve().parents[1] / "image_backfill_worker.py"


def load_worker(admin_key=""):
    """Execute the real worker without its import-time credentials/DB adapter setup."""
    tree = ast.parse(SOURCE.read_text())
    excluded = []
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(target, ast.Tuple) for target in node.targets):
            excluded.append(node)  # PU, PP = read_env(...); never read a real .env.
        elif isinstance(node, ast.Import) and any(alias.name == "local_db" for alias in node.names):
            excluded.append(node)
        elif isinstance(node, ast.Expr) and isinstance(node.value, ast.Call) and ast.unparse(node.value.func) == "sys.path.insert":
            excluded.append(node)
    tree.body = [node for node in tree.body if node not in excluded]
    namespace = {"__name__": "image_backfill_merge_test", "__file__": str(SOURCE),
                 "PU": "test-user", "PP": "test-password", "local_db": Mock()}
    with patch.dict(os.environ, {"ADMIN_API_KEY": admin_key}, clear=True):
        exec(compile(tree, str(SOURCE), "exec"), namespace)
    return namespace


class ImageBackfillMergeTests(unittest.TestCase):
    def test_admin_key_is_forwarded_and_blank_key_is_omitted(self):
        for supplied, expected in (("  test-admin-key  ", "test-admin-key"), ("  ", None)):
            with self.subTest(key_present=bool(expected)):
                worker = load_worker(supplied)
                response = MagicMock()
                response.__enter__.return_value.read.return_value = b'{"data":{"successCount":1}}'
                with patch("urllib.request.urlopen", return_value=response) as send:
                    self.assertEqual(worker["_call"]("menus", 4, ["https://images.invalid/test.jpg"]),
                                     {"successCount": 1})
                request = send.call_args.args[0]
                self.assertEqual(request.get_header("X-admin-key"), expected)
                self.assertEqual(request.get_header("Content-type"), "application/json")
                self.assertEqual(request.get_method(), "POST")

    def test_new_and_legacy_menu_images_are_combined_with_existing_filters(self):
        worker = load_worker()
        legacy = "https://ldb-phinf.pstatic.net/legacy.jpg"
        current = "https://ldb-phinf.pstatic.net/current.jpg"
        store = "https://ldb-phinf.pstatic.net/store.jpg"
        worker["dataops_sql"] = Mock(return_value=json.dumps({
            "Menu:old": {"name": "소금 빵", "images": [legacy]},
            "new-key": {"__typename": "PlaceMenuItem", "name": "소금빵", "images": [
                {"url": current}, {"url": legacy}, {"url": "https://other.invalid/no.jpg"},
                {"url": "https://ldb-phinf.pstatic.net/no.gif"}, {"url": 123}, None]},
            "PlaceDetailTopPhotoItem:1": {"mediaFormat": "image", "mediaSource": "business", "originalUrl": store},
            "PlaceDetailTopPhotoItem:2": {"mediaFormat": "image", "mediaSource": "visitor", "originalUrl": current},
        }))
        self.assertEqual(worker["extract_store"](7), ({"소금빵": [legacy, current]}, [store]))

    def test_dataops_queries_use_configured_adapter_and_prod_queries_remain_read_only(self):
        worker = load_worker()
        connection = Mock()
        connection.cursor.return_value.fetchall.return_value = [(1, True, None)]
        worker["local_db"].connect.return_value = connection
        self.assertEqual(worker["dataops_sql"]("SELECT 1"), "1\tt\t")
        connection.close.assert_called_once()
        postgres = SimpleNamespace(connect=Mock(return_value=connection), OperationalError=type("OperationalError", (Exception,), {}))
        with patch.dict(sys.modules, {"psycopg2": postgres}):
            self.assertEqual(worker["prod_sql"]("SELECT 1"), "1\tt\t")
        self.assertEqual(postgres.connect.call_args.kwargs["options"], "-c default_transaction_read_only=on")
        self.assertEqual(postgres.connect.call_args.kwargs["host"], "127.0.0.1")

    def test_scoped_dry_run_counts_but_never_uploads(self):
        worker = load_worker()
        worker["dataops_sql"] = Mock(return_value="1\n2")
        worker["prod_sql"] = Mock(side_effect=lambda sql: "f" if "SELECT EXISTS" in sql else
                                   "9\t소금빵" if "normalized_name" in sql else "1\n2")
        worker["extract_store"] = Mock(return_value=({"소금빵": ["menu-1", "menu-2", "menu-3"]}, ["store-1"]))
        worker["post_from_urls"] = Mock(side_effect=AssertionError("dry-run must not upload"))
        with tempfile.TemporaryDirectory() as temporary:
            scope = Path(temporary) / "scope.json"
            scope.write_text('[2]')
            output = io.StringIO()
            with patch.object(sys, "argv", [str(SOURCE), "--all", "--dry-run", "--store-ids-file", str(scope)]), redirect_stdout(output):
                worker["main"]()
        worker["extract_store"].assert_called_once_with(2)
        worker["post_from_urls"].assert_not_called()
        self.assertIn("메뉴사진 2 · 가게사진 1", output.getvalue())


if __name__ == "__main__":
    unittest.main()

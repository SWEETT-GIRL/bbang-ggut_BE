"""초안 대시보드·알림 생성기 — 생성물 모양만 본다. Prometheus·Grafana 에 접속하지 않는다."""
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import generate_draft_alerts  # noqa: E402
import generate_draft_dashboards  # noqa: E402

METRICS = {"draft_backlog", "draft_socket_sessions", "draft_write_total", "draft_flush_total",
           "draft_flush_batch_size", "draft_flush_lag_seconds", "draft_inflight_reclaimed_total",
           "http_server_requests_seconds_count", "http_server_requests_seconds_bucket", "up"}


def expressions(dashboard):
    for panel in dashboard["panels"]:
        for target in panel.get("targets", []):
            yield panel["title"], target["expr"]


class DashboardTest(unittest.TestCase):
    def setUp(self):
        self.dashboard = generate_draft_dashboards.draft_dashboard()

    def test_panel_ids_are_unique_and_every_query_names_a_known_metric(self):
        ids = [panel["id"] for panel in self.dashboard["panels"]]
        self.assertEqual(len(ids), len(set(ids)))
        for title, expr in expressions(self.dashboard):
            self.assertTrue(any(metric in expr for metric in METRICS), f"{title}: {expr}")
            self.assertEqual(expr.count("{"), expr.count("}"), title)
            self.assertEqual(expr.count("("), expr.count(")"), title)

    def test_committed_json_matches_generator(self):
        committed = json.loads((HERE.parents[1] / "monitoring/grafana/course-draft-v0.json").read_text())
        self.assertEqual(committed, self.dashboard)

    def test_draft_put_filter_is_exact_route_template(self):
        self.assertIn('uri="/courses/{courseId}/stores/{storeId}/memo-draft"', generate_draft_dashboards.DRAFT_PUT)


class AlertTest(unittest.TestCase):
    def setUp(self):
        self.rules = generate_draft_alerts.provision("ds-uid", "slack")["groups"][0]["rules"]

    def test_rules_are_paused_bool_and_route_to_given_contact_point(self):
        self.assertEqual([rule["title"] for rule in self.rules],
                         ["CourseDraftBacklogStuck", "CourseDraftRedisUnreadable", "CourseDraftWrite503Ratio",
                          "CourseDraftSocketSessionsHigh"])
        for rule in self.rules:
            self.assertTrue(rule["isPaused"])
            self.assertEqual(rule["notification_settings"]["receiver"], "slack")
            self.assertEqual(rule["data"][0]["datasourceUid"], "ds-uid")
            self.assertIn("bool", rule["data"][0]["model"]["expr"])
            self.assertEqual(rule["noDataState"], "OK")

    def test_committed_json_matches_generator_defaults(self):
        committed = json.loads((HERE.parents[1] / "monitoring/grafana/alerting/course-draft-rules.json").read_text())
        self.assertEqual(committed, generate_draft_alerts.provision("dfutc56whwh6of", "slack-metric"))

    def test_session_alert_watches_the_busiest_instance(self):
        sessions = next(rule for rule in self.rules if rule["title"] == "CourseDraftSocketSessionsHigh")
        self.assertEqual(sessions["data"][0]["model"]["expr"], "max(draft_socket_sessions) > bool 2000")
        self.assertEqual(sessions["for"], "5m")


if __name__ == "__main__":
    unittest.main()

"""Validate generated queries and import against disposable LOCAL Prometheus/Grafana.

Does not connect to monitoring AWS services or query CloudWatch. Grafana validates
import/save, not whether the production datasource has metric data.
"""
import argparse
import base64
import json
from pathlib import Path
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]


def local_url(value):
    if urlsplit(value).hostname not in ("localhost", "127.0.0.1"):
        raise ValueError("Only disposable localhost endpoints may be used")
    return value.rstrip("/")


def request(base, path, body=None, grafana=False):
    headers = {"Content-Type": "application/json"}
    if grafana:
        headers["Authorization"] = "Basic " + base64.b64encode(b"admin:admin").decode()
    payload = None if body is None else json.dumps(body).encode()
    with urlopen(Request(base + path, data=payload, headers=headers), timeout=10) as response:
        return json.load(response)


def validate(prometheus, grafana):
    counts = {"promql_queries": 0, "grafana_imports": [], "cloudwatch_live_query": "NOT_RUN"}
    for filename in ("dataops-spring-v0.json", "dataops-rds-cloudwatch.json"):
        dashboard = json.loads((ROOT / "monitoring/grafana" / filename).read_text())
        identifiers = [panel["id"] for panel in dashboard["panels"]]
        assert len(set(identifiers)) == len(identifiers)
        for panel in dashboard["panels"]:
            for target in panel.get("targets", []):
                if "expr" not in target:
                    continue
                expression = target["expr"]
                for key, value in (("${spring_job:regex}", ".*"), ("${spring_instance:regex}", ".*"),
                                   ("$workflow", "nightly"), ("$__rate_interval", "5m")):
                    expression = expression.replace(key, value)
                result = request(prometheus, "/api/v1/query?" + urlencode({"query": expression}))
                assert result["status"] == "success", panel["title"]
                counts["promql_queries"] += 1
        plugin = dashboard["__inputs"][0]["pluginId"]
        datasource_uid = "bbang-test-" + plugin
        request(grafana, "/api/datasources", {"name": datasource_uid, "uid": datasource_uid, "type": plugin,
                                              "access": "proxy", "url": "http://localhost:9090" if plugin == "prometheus" else "",
                                              "jsonData": {"defaultRegion": "ap-northeast-2"} if plugin == "cloudwatch" else {}}, grafana=True)
        result = request(grafana, "/api/dashboards/import", {"dashboard": dashboard, "overwrite": True,
            "inputs": [{"name": dashboard["__inputs"][0]["name"], "type": "datasource", "pluginId": plugin,
                        "value": datasource_uid}]}, grafana=True)
        stored = request(grafana, "/api/dashboards/uid/" + dashboard["uid"], grafana=True)
        assert len(stored["dashboard"]["panels"]) == len(dashboard["panels"])
        counts["grafana_imports"].append({"filename": filename, "uid": dashboard["uid"],
                                        "panels": len(dashboard["panels"]), "import_path": result.get("importedUrl")})
    return counts


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prometheus", type=local_url, required=True)
    parser.add_argument("--grafana", type=local_url, required=True)
    args = parser.parse_args()
    print(json.dumps(validate(args.prometheus, args.grafana), indent=2, ensure_ascii=False))

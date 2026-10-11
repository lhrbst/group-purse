#!/usr/bin/env python3
"""Prüft die Grafana-Dashboards: gültiges JSON, eindeutige UIDs, bekannte Datenquellen und nur bekannte Metrik-Präfixe.

Aufruf: python deploy/monitoring/tools/check_dashboards.py
Endet mit Exit-Code 1, wenn etwas nicht stimmt. Außerdem wird geprüft, dass die eingecheckten JSON-Dateien dem Generator entsprechen.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DASHBOARDS = ROOT / "grafana" / "dashboards"
KNOWN_PREFIXES = (
    "up", "probe_", "http_server_", "jvm_", "hikaricp_", "tomcat_", "logback_", "process_", "system_", "node_", "container_", "pg_",
    "gk_", "gen_ai_", "ALERTS", "application_", "tasks_scheduled_",
)
KEYWORDS = {
    "sum", "rate", "increase", "avg", "min", "max", "count", "topk", "by", "without", "histogram_quantile", "time", "vector", "or", "and", "le",
    "irate", "avg_over_time", "max_over_time", "count_over_time", "on", "ignoring", "group_left", "group_right", "bool", "offset",
}
METRIC = re.compile(r"[A-Za-z_:][A-Za-z0-9_:]*")


def metric_names(expr: str):
    # Zeichenketten und Label-Matcher entfernen, damit Label-Namen und Werte nicht als Metriken zählen
    cleaned = re.sub(r'"[^"]*"', '""', expr)
    cleaned = re.sub(r"\{[^}]*\}", "", cleaned)
    cleaned = re.sub(r"\[[^\]]*\]", "", cleaned)
    cleaned = re.sub(r"\b(by|without|on|ignoring)\s*\([^)]*\)", "", cleaned)
    for name in METRIC.findall(cleaned):
        if name in KEYWORDS or name.isdigit():
            continue
        yield name


def main() -> int:
    errors = []
    uids = set()
    files = sorted(DASHBOARDS.rglob("*.json"))
    if not files:
        errors.append("keine Dashboards gefunden")
    for f in files:
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
        except ValueError as e:
            errors.append(f"{f.name}: kein gültiges JSON ({e})")
            continue
        if d.get("uid") in uids:
            errors.append(f"{f.name}: UID {d.get('uid')} doppelt")
        uids.add(d.get("uid"))
        ids = [p["id"] for p in d.get("panels", [])]
        if len(ids) != len(set(ids)):
            errors.append(f"{f.name}: Panel-IDs nicht eindeutig")
        for p in d.get("panels", []):
            for t in p.get("targets", []):
                ds = (t.get("datasource") or {}).get("uid")
                if ds not in ("prometheus", "loki"):
                    errors.append(f"{f.name}/{p.get('title')}: unbekannte Datenquelle {ds}")
                if ds == "prometheus":
                    for name in metric_names(t.get("expr", "")):
                        if not name.startswith(KNOWN_PREFIXES):
                            errors.append(f"{f.name}/{p.get('title')}: unbekannte Metrik {name}")
    # eingecheckte Dateien = Generator-Ausgabe
    before = {f: f.read_text(encoding="utf-8") for f in files}
    subprocess.run([sys.executable, str(ROOT / "tools" / "gen_dashboards.py")], check=True, stdout=subprocess.DEVNULL)
    for f, text in before.items():
        if f.read_text(encoding="utf-8") != text:
            errors.append(f"{f.name}: weicht vom Generator ab (gen_dashboards.py ausführen und einchecken)")
            f.write_text(text, encoding="utf-8")
    for e in errors:
        print("FEHLER:", e)
    print(f"{len(files)} Dashboards geprüft, {len(errors)} Fehler")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

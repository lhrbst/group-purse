#!/usr/bin/env python3
"""Erzeugt die Grafana-Dashboards (JSON) aus kompakten Definitionen.

Aufruf (aus dem Projektordner): python deploy/monitoring/tools/gen_dashboards.py
Die erzeugten Dateien liegen unter deploy/monitoring/grafana/dashboards/{technik,fachlich}/ und werden eingecheckt.
Änderungen an Dashboards bitte hier machen und neu erzeugen, nicht in den JSON-Dateien.
"""
import json
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "grafana" / "dashboards"
PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}


def target(expr, legend="", ref="A", ds=PROM, instant=False, fmt=None):
    t = {"datasource": ds, "expr": expr, "legendFormat": legend, "refId": ref}
    if instant:
        t["instant"] = True
    if fmt:
        t["format"] = fmt
    return t


class Board:
    def __init__(self, uid, title, folder, tags, variables=None):
        self.uid, self.title, self.folder, self.tags = uid, title, folder, tags
        self.panels = []
        self.x = 0
        self.y = 0
        self.row_h = 0
        self.next_id = 1
        self.variables = variables or []

    def _place(self, w, h):
        if self.x + w > 24:
            self.x = 0
            self.y += self.row_h
            self.row_h = 0
        pos = {"x": self.x, "y": self.y, "w": w, "h": h}
        self.x += w
        self.row_h = max(self.row_h, h)
        return pos

    def _add(self, panel):
        panel["id"] = self.next_id
        self.next_id += 1
        self.panels.append(panel)

    def newline(self):
        self.x = 0
        self.y += self.row_h
        self.row_h = 0

    def row(self, title):
        self.newline()
        self._add({"type": "row", "title": title, "collapsed": False, "gridPos": {"x": 0, "y": self.y, "w": 24, "h": 1}, "panels": []})
        self.y += 1
        self.row_h = 0

    def stat(self, title, expr, unit="short", w=4, h=4, thresholds=None, mappings=None, decimals=None, description=""):
        steps = thresholds or [{"color": "green", "value": None}]
        # Fehlt der Wert (z. B. keine Anfragen, kein HTTPS lokal), steht ein graues "–" statt einer Alarmfarbe
        no_data = {"type": "special", "options": {"match": "null+nan", "result": {"text": "–", "color": "gray"}}}
        defaults = {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps}, "mappings": (mappings or []) + [no_data]}
        if decimals is not None:
            defaults["decimals"] = decimals
        self._add({
            "type": "stat", "title": title, "description": description, "datasource": PROM, "gridPos": self._place(w, h),
            "targets": [target(expr)],
            "fieldConfig": {"defaults": defaults, "overrides": []},
            "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "background", "graphMode": "none", "textMode": "auto"},
        })

    def ts(self, title, queries, unit="short", w=12, h=8, stack=False, description="", ds=PROM, min_=None):
        """queries: Liste aus (expr, legende)."""
        custom = {"drawStyle": "line", "lineWidth": 1, "fillOpacity": 15 if stack else 8, "showPoints": "never"}
        if stack:
            custom["stacking"] = {"mode": "normal", "group": "A"}
        defaults = {"unit": unit, "custom": custom}
        if min_ is not None:
            defaults["min"] = min_
        self._add({
            "type": "timeseries", "title": title, "description": description, "datasource": ds, "gridPos": self._place(w, h),
            "targets": [target(e, l, chr(65 + i), ds) for i, (e, l) in enumerate(queries)],
            "fieldConfig": {"defaults": defaults, "overrides": []},
            "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi", "sort": "desc"}},
        })

    def table(self, title, expr, w=24, h=8, description=""):
        self._add({
            "type": "table", "title": title, "description": description, "datasource": PROM, "gridPos": self._place(w, h),
            "targets": [target(expr, ref="A", instant=True, fmt="table")],
            "fieldConfig": {"defaults": {}, "overrides": []},
            "options": {"showHeader": True},
            "transformations": [{"id": "organize", "options": {"excludeByName": {"Time": True, "__name__": True, "Value": True, "alertstate": True}}}],
        })

    def logs(self, title, expr, w=24, h=12):
        self._add({
            "type": "logs", "title": title, "datasource": LOKI, "gridPos": self._place(w, h),
            "targets": [{"datasource": LOKI, "expr": expr, "refId": "A", "queryType": "range"}],
            "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True},
        })

    def json(self):
        return {
            "uid": self.uid, "title": self.title, "tags": self.tags, "schemaVersion": 41, "version": 1, "editable": False,
            "timezone": "browser", "refresh": "30s", "time": {"from": "now-6h", "to": "now"},
            "templating": {"list": self.variables}, "annotations": {"list": []}, "panels": self.panels,
        }

    def write(self):
        d = OUT / self.folder
        d.mkdir(parents=True, exist_ok=True)
        (d / f"{self.uid}.json").write_text(json.dumps(self.json(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


UP_MAP = [{"type": "value", "options": {"0": {"text": "NEIN", "color": "red"}, "1": {"text": "ja", "color": "green"}}}]
RED_AT = lambda v: [{"color": "green", "value": None}, {"color": "red", "value": v}]
YELLOW_RED = lambda y, r: [{"color": "green", "value": None}, {"color": "yellow", "value": y}, {"color": "red", "value": r}]
REQ = 'http_server_requests_seconds'
NOT_ACT = 'uri!~"/actuator.*"'


def uebersicht():
    b = Board("uebersicht", "Übersicht", "technik", ["gruppenkasse", "technik"])
    b.stat("App läuft", 'min(up{job="app"})', mappings=UP_MAP, thresholds=[{"color": "red", "value": None}, {"color": "green", "value": 1}])
    b.stat("HTTPS-Check", "min(probe_success)", mappings=UP_MAP, thresholds=[{"color": "red", "value": None}, {"color": "green", "value": 1}],
           description="Erreichbarkeit der öffentlichen Adresse von innen gemessen (blackbox)")
    b.stat("Zertifikat (Tage)", "(min(probe_ssl_earliest_cert_expiry) - time()) / 86400", unit="d", decimals=0,
           thresholds=[{"color": "red", "value": None}, {"color": "yellow", "value": 14}, {"color": "green", "value": 30}])
    b.stat("Aktive Alarme", 'count(ALERTS{alertstate="firing"}) or vector(0)', thresholds=RED_AT(1))
    b.stat("5xx-Quote (5 min)", f'(sum(rate({REQ}_count{{status=~"5.."}}[5m])) / sum(rate({REQ}_count[5m]))) or vector(0)', unit="percentunit", decimals=2,
           thresholds=YELLOW_RED(0.01, 0.02))
    b.stat("Latenz p95 (5 min)", f'histogram_quantile(0.95, sum by (le) (rate({REQ}_bucket{{{NOT_ACT}}}[5m])))', unit="s", decimals=2,
           thresholds=YELLOW_RED(1, 2))
    b.stat("Heap-Auslastung", 'sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"})', unit="percentunit", decimals=0,
           thresholds=YELLOW_RED(0.7, 0.85))
    b.stat("Plattenbelegung", '1 - node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}', unit="percentunit", decimals=0,
           thresholds=YELLOW_RED(0.7, 0.85))
    b.stat("RAM verfügbar", "node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes", unit="percentunit", decimals=0,
           thresholds=[{"color": "red", "value": None}, {"color": "yellow", "value": 0.1}, {"color": "green", "value": 0.2}])
    b.stat("Datenbank erreichbar", "min(pg_up)", mappings=UP_MAP, thresholds=[{"color": "red", "value": None}, {"color": "green", "value": 1}])
    b.stat("Uptime der App", "max(process_uptime_seconds)", unit="s", decimals=0)
    b.stat("Logfehler (1 h)", 'sum(increase(logback_events_total{level="error"}[1h]))', decimals=0, thresholds=YELLOW_RED(1, 10))
    b.newline()
    b.ts("Anfragen pro Sekunde", [(f"sum(rate({REQ}_count{{{NOT_ACT}}}[2m]))", "Anfragen")], unit="reqps")
    b.ts("Antwortzeit p95", [(f'histogram_quantile(0.95, sum by (le) (rate({REQ}_bucket{{{NOT_ACT}}}[5m])))', "p95")], unit="s")
    b.table("Aktive Alarme (Regeln unter Alerting > Alert rules)", 'ALERTS{alertstate="firing"}')
    return b


def anwendung():
    b = Board("anwendung", "Anwendung", "technik", ["gruppenkasse", "technik"])
    b.row("Anfragen")
    b.ts("Anfragen/s nach Status", [(f'sum by (status) (rate({REQ}_count{{{NOT_ACT}}}[2m]))', "{{status}}")], unit="reqps", stack=True)
    b.ts("Latenz p50/p95/p99", [
        (f'histogram_quantile({q}, sum by (le) (rate({REQ}_bucket{{{NOT_ACT}}}[5m])))', f"p{int(q * 100)}") for q in (0.5, 0.95, 0.99)], unit="s")
    b.ts("Langsamste Routen (p95)", [(f'topk(8, histogram_quantile(0.95, sum by (le, uri) (rate({REQ}_bucket{{{NOT_ACT}}}[5m]))))', "{{uri}}")], unit="s")
    b.ts("Fehler je Route (4xx/5xx pro Minute)", [(f'sum by (uri, status) (rate({REQ}_count{{status=~"[45].."}}[5m])) * 60', "{{status}} {{uri}}")], unit="short")
    b.row("JVM")
    b.ts("Heap", [('sum(jvm_memory_used_bytes{area="heap"})', "genutzt"), ('sum(jvm_memory_committed_bytes{area="heap"})', "reserviert"),
                  ('sum(jvm_memory_max_bytes{area="heap"})', "maximal")], unit="bytes")
    b.ts("Nicht-Heap (Metaspace u. a.)", [('sum(jvm_memory_used_bytes{area="nonheap"})', "genutzt")], unit="bytes")
    b.ts("GC-Pausen", [("sum(rate(jvm_gc_pause_seconds_sum[5m]))", "Pausenzeit pro Sekunde"), ("sum(rate(jvm_gc_pause_seconds_count[5m]))", "Pausen pro Sekunde")], unit="short")
    b.ts("Threads", [("jvm_threads_live_threads", "live"), ("jvm_threads_daemon_threads", "daemon")], unit="short")
    b.ts("CPU", [("process_cpu_usage", "Prozess"), ("system_cpu_usage", "System")], unit="percentunit", min_=0)
    b.ts("Aktive Sitzungen", [("tomcat_sessions_active_current_sessions", "aktiv")], unit="short")
    b.row("Datenbankverbindungen und Hintergrundjobs")
    b.ts("Hikari-Verbindungen", [("hikaricp_connections_active", "aktiv"), ("hikaricp_connections_idle", "frei"), ("hikaricp_connections_pending", "wartend"),
                                 ("hikaricp_connections_max", "maximal")], unit="short")
    b.ts("Wartezeit auf Verbindung", [("rate(hikaricp_connections_acquire_seconds_sum[5m]) / rate(hikaricp_connections_acquire_seconds_count[5m])", "Mittel")], unit="s")
    b.ts("Geplante Jobs: Läufe und Dauer", [("sum by (code_function) (rate(tasks_scheduled_execution_seconds_count[5m]))", "{{code_function}} Läufe/s"),
                                            ("sum by (code_function) (rate(tasks_scheduled_execution_seconds_sum[5m]) / rate(tasks_scheduled_execution_seconds_count[5m]))", "{{code_function}} Dauer")], unit="short")
    b.ts("Logfehler und Warnungen pro Minute", [('sum by (level) (rate(logback_events_total{level=~"error|warn"}[5m])) * 60', "{{level}}")], unit="short")
    return b


def datenbank():
    b = Board("datenbank", "Datenbank", "technik", ["gruppenkasse", "technik"])
    b.stat("PostgreSQL erreichbar", "min(pg_up)", mappings=UP_MAP, thresholds=[{"color": "red", "value": None}, {"color": "green", "value": 1}])
    b.stat("Größe der Datenbank", 'pg_database_size_bytes{datname="grouppurse"}', unit="bytes")
    b.stat("Aktive Verbindungen", 'pg_stat_database_numbackends{datname="grouppurse"}', decimals=0)
    b.stat("Deadlocks (24 h)", 'sum(increase(pg_stat_database_deadlocks{datname="grouppurse"}[24h]))', decimals=0, thresholds=RED_AT(1))
    b.newline()
    b.ts("Verbindungen", [('pg_stat_database_numbackends{datname="grouppurse"}', "Verbindungen")], unit="short")
    b.ts("Transaktionen pro Sekunde", [('rate(pg_stat_database_xact_commit{datname="grouppurse"}[5m])', "Commits"),
                                       ('rate(pg_stat_database_xact_rollback{datname="grouppurse"}[5m])', "Rollbacks")], unit="ops")
    b.ts("Zeilen pro Sekunde", [('rate(pg_stat_database_tup_inserted{datname="grouppurse"}[5m])', "eingefügt"),
                                ('rate(pg_stat_database_tup_updated{datname="grouppurse"}[5m])', "geändert"),
                                ('rate(pg_stat_database_tup_deleted{datname="grouppurse"}[5m])', "gelöscht"),
                                ('rate(pg_stat_database_tup_fetched{datname="grouppurse"}[5m])', "gelesen")], unit="ops")
    b.ts("Cache-Trefferquote", [('rate(pg_stat_database_blks_hit{datname="grouppurse"}[5m]) / (rate(pg_stat_database_blks_hit{datname="grouppurse"}[5m]) + rate(pg_stat_database_blks_read{datname="grouppurse"}[5m]))', "Treffer")],
         unit="percentunit", min_=0)
    b.ts("Sperren nach Modus", [("sum by (mode) (pg_locks_count)", "{{mode}}")], unit="short")
    b.ts("Wartezeit auf Hikari-Verbindung", [("rate(hikaricp_connections_acquire_seconds_sum[5m]) / rate(hikaricp_connections_acquire_seconds_count[5m])", "Mittel")], unit="s")
    b.ts("Datenbankgröße", [('pg_database_size_bytes{datname="grouppurse"}', "grouppurse")], unit="bytes")
    return b


def server():
    b = Board("server", "Server und Container", "technik", ["gruppenkasse", "technik"])
    b.row("Server")
    b.ts("CPU-Auslastung", [('1 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m]))', "CPU")], unit="percentunit", min_=0)
    b.ts("Arbeitsspeicher", [("node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes", "belegt"), ("node_memory_MemTotal_bytes", "gesamt")], unit="bytes")
    b.ts("Swap", [("node_memory_SwapTotal_bytes - node_memory_SwapFree_bytes", "genutzt")], unit="bytes")
    b.ts("Plattenbelegung", [('1 - node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}', "/")], unit="percentunit", min_=0)
    b.ts("Netzwerk", [('sum(rate(node_network_receive_bytes_total{device!~"lo|veth.*|br-.*|docker.*"}[5m]))', "empfangen"),
                      ('sum(rate(node_network_transmit_bytes_total{device!~"lo|veth.*|br-.*|docker.*"}[5m]))', "gesendet")], unit="Bps")
    b.ts("Plattendurchsatz", [("sum(rate(node_disk_read_bytes_total[5m]))", "gelesen"), ("sum(rate(node_disk_written_bytes_total[5m]))", "geschrieben")], unit="Bps")
    b.row("Container")
    b.ts("CPU je Container", [('sum by (name) (rate(container_cpu_usage_seconds_total{name=~".+"}[5m]))', "{{name}}")], unit="percentunit", min_=0)
    b.ts("Arbeitsspeicher je Container", [('sum by (name) (container_memory_working_set_bytes{name=~".+"})', "{{name}}")], unit="bytes")
    b.ts("Laufzeit seit letztem Start", [('time() - container_start_time_seconds{name=~".+"}', "{{name}}")], unit="s")
    return b


def logs():
    q = ('{container=~"$container", level=~"$level"} |~ "$suche" | json '
         '| line_format `{{ if .message }}{{ .level }} {{ .logger_name }} {{ .message }}{{ if .requestId }} [{{ .requestId }}]{{ end }}{{ else }}{{ __line__ }}{{ end }}`')
    variables = [
        {"name": "container", "label": "Container", "type": "custom", "query": "app,caddy,db", "multi": True, "includeAll": True, "allValue": ".+",
         "current": {"text": "All", "value": "$__all"}, "options": []},
        {"name": "level", "label": "Level", "type": "custom", "query": "ERROR,WARN,INFO,DEBUG", "multi": True, "includeAll": True, "allValue": ".+",
         "current": {"text": "All", "value": "$__all"}, "options": []},
        {"name": "suche", "label": "Suche (Text oder Request-ID)", "type": "textbox", "query": "", "current": {"text": "", "value": ""}, "options": []},
    ]
    b = Board("logs", "Logs", "technik", ["gruppenkasse", "technik", "logs"], variables)
    b.ts("Logzeilen nach Level", [('sum by (level) (count_over_time({container="app"}[$__interval]))', "{{level}}")], unit="short", ds=LOKI)
    b.ts("Fehler und Warnungen", [('sum(count_over_time({container="app", level=~"ERROR|WARN"}[$__interval]))', "Fehler/Warnungen")], unit="short", ds=LOKI)
    b.logs("Logzeilen", q)
    return b


def belegerkennung():
    b = Board("belegerkennung", "Belegerkennung", "fachlich", ["gruppenkasse", "fachlich"])
    b.row("Ergebnis")
    b.stat("Gelesen (24 h)", 'sum(increase(gk_expense_transitions_total{from="VERARBEITUNG", to="EINGEREICHT"}[24h]))', decimals=0)
    b.stat("Zur manuellen Erfassung (24 h)", 'sum(increase(gk_expense_transitions_total{from="VERARBEITUNG", to="MANUELL"}[24h]))', decimals=0, thresholds=YELLOW_RED(3, 10))
    b.stat("Fallback-Quote (24 h)", "sum(increase(gk_receipt_ai_fallback_total[24h])) / (sum(increase(gk_expense_transitions_total{from=\"NEU\", to=\"VERARBEITUNG\"}[24h])) > 0)",
           unit="percentunit", decimals=0, thresholds=YELLOW_RED(0.3, 0.6), description="Anteil der Belege, bei denen das stärkere Modell gefragt wurde")
    b.stat("Aufgegeben, unsicher (24 h)", 'sum(increase(gk_receipt_ai_gave_up_total{reason="uncertain"}[24h]))', decimals=0)
    b.stat("Aufgegeben, Versuche (24 h)", 'sum(increase(gk_receipt_ai_gave_up_total{reason="attempts"}[24h]))', decimals=0, thresholds=YELLOW_RED(1, 5))
    b.stat("Dauer Foto bis Ergebnis p50", 'histogram_quantile(0.5, sum by (le) (rate(gk_receipt_end_to_end_seconds_bucket[1h])))', unit="s", decimals=0)
    b.newline()
    b.ts("Trichter: Statusübergänge pro Stunde", [('sum by (from, to) (increase(gk_expense_transitions_total{from=~"NEU|VERARBEITUNG"}[1h]))', "{{from}} → {{to}}")], unit="short", stack=True)
    b.ts("Ergebnisse je Modell", [('sum by (model, result) (increase(gk_receipt_ai_results_total[1h]))', "{{model}}: {{result}}")], unit="short", stack=True)
    b.ts("Dauer Modellaufruf p50/p95 je Modell", [
        (f'histogram_quantile({q}, sum by (le, model) (rate(gk_receipt_ai_duration_seconds_bucket{{outcome="ok"}}[1h])))', f"p{int(q * 100)} {{{{model}}}}") for q in (0.5, 0.95)], unit="s")
    b.ts("Gesamtdauer Foto bis Ergebnis p50/p95", [
        (f'histogram_quantile({q}, sum by (le, outcome) (rate(gk_receipt_end_to_end_seconds_bucket[1h])))', f"p{int(q * 100)} {{{{outcome}}}}") for q in (0.5, 0.95)], unit="s",
         description="Von der Aufnahme bis zum Ergebnis (EINGEREICHT) bzw. bis zur Freigabe zur manuellen Erfassung (MANUELL)")
    b.ts("Modellaufrufe nach Ausgang", [('sum by (model, outcome) (increase(gk_receipt_ai_duration_seconds_count[1h]))', "{{model}}: {{outcome}}")], unit="short", stack=True)
    b.ts("Aufgegebene Belege nach Grund", [('sum by (reason) (increase(gk_receipt_ai_gave_up_total[1h]))', "{{reason}}")], unit="short", stack=True)
    b.ts("Token-Verbrauch pro Stunde", [('sum by (gen_ai_token_type) (increase(gen_ai_client_token_usage_total{gen_ai_token_type!="total"}[1h]))', "{{gen_ai_token_type}}")], unit="short", stack=True,
         description="Eingabe- und Ausgabe-Token der Modellaufrufe (aus Spring AI)")
    return b


def betrieb():
    b = Board("betrieb", "Betrieb", "fachlich", ["gruppenkasse", "fachlich"])
    b.stat("Buchungen (24 h)", "sum(increase(gk_ledger_bookings_total[24h]))", decimals=0)
    b.stat("Belege erfasst (24 h)", 'sum(increase(gk_expense_transitions_total{from="NEU"}[24h]))', decimals=0)
    b.stat("Belege geprüft (24 h)", 'sum(increase(gk_expense_transitions_total{to="GEPRUEFT"}[24h]))', decimals=0)
    b.stat("Belege abgelehnt (24 h)", 'sum(increase(gk_expense_transitions_total{to="ABGELEHNT"}[24h]))', decimals=0)
    b.stat("Uploads abgelehnt (24 h)", "sum(increase(gk_upload_rejected_total[24h]))", decimals=0, thresholds=YELLOW_RED(5, 20))
    b.newline()
    b.ts("Buchungen pro Stunde nach Art", [("sum by (type) (increase(gk_ledger_bookings_total[1h]))", "{{type}}")], unit="short", stack=True)
    b.ts("Beleg-Statuswechsel pro Stunde", [("sum by (from, to) (increase(gk_expense_transitions_total[1h]))", "{{from}} → {{to}}")], unit="short", stack=True)
    b.ts("Abgelehnte Uploads nach Grund", [("sum by (reason) (increase(gk_upload_rejected_total[1h]))", "{{reason}}")], unit="short", stack=True)
    b.ts("Anfragen pro Stunde (API)", [(f'sum(increase({REQ}_count{{uri=~"/api/.*"}}[1h]))', "API-Anfragen")], unit="short")
    return b


def sicherheit():
    b = Board("sicherheit", "Sicherheit", "fachlich", ["gruppenkasse", "fachlich", "sicherheit"])
    b.stat("Fehlgeschlagene Logins (24 h)", 'sum(increase(gk_login_total{outcome="failure"}[24h]))', decimals=0, thresholds=YELLOW_RED(20, 100))
    b.stat("Gesperrte Versuche (24 h)", 'sum(increase(gk_login_total{outcome="locked"}[24h]))', decimals=0, thresholds=YELLOW_RED(1, 10))
    b.stat("Erfolgreiche Logins (24 h)", 'sum(increase(gk_login_total{outcome="success"}[24h]))', decimals=0)
    b.stat("Registrierungen (24 h)", 'sum(increase(gk_registrations_total{outcome="success"}[24h]))', decimals=0)
    b.stat("401/403/429 (24 h)", f'sum(increase({REQ}_count{{status=~"401|403|429"}}[24h]))', decimals=0, thresholds=YELLOW_RED(50, 500))
    b.newline()
    b.ts("Logins pro Stunde nach Ausgang", [("sum by (outcome) (increase(gk_login_total[1h]))", "{{outcome}}")], unit="short", stack=True)
    b.ts("Abgelehnte Anfragen (401/403/429) pro Stunde", [(f'sum by (status) (increase({REQ}_count{{status=~"401|403|429"}}[1h]))', "{{status}}")], unit="short", stack=True)
    b.ts("Registrierungen und Einladungen pro Stunde", [("sum by (outcome) (increase(gk_registrations_total[1h]))", "Registrierung {{outcome}}"),
                                                        ("sum by (outcome) (increase(gk_invitations_total[1h]))", "Einladung {{outcome}}")], unit="short")
    b.ts("Fehlversuche je Minute", [('sum(rate(gk_login_total{outcome=~"failure|locked"}[2m])) * 60', "Fehlversuche/min")], unit="short")
    return b


if __name__ == "__main__":
    for make in (uebersicht, anwendung, datenbank, server, logs, belegerkennung, betrieb, sicherheit):
        make().write()
        print("geschrieben:", make.__name__)

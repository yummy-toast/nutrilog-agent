"""离线 HTTP 评测。可启动隔离的本地 JAR，也可检查专用 Compose 实例。"""
import argparse
from contextlib import contextmanager
from http.cookiejar import CookieJar
import json
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener
from uuid import uuid4


class Client:
    def __init__(self, url):
        self.base = url.rstrip("/") + "/api/v1"
        self.opener = build_opener(HTTPCookieProcessor(CookieJar()))
        self.headers = {}

    def call(self, path, expected=200, body=None, *, form=None, csrf=True):
        headers = dict(self.headers) if csrf else {}
        data = None
        if form is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded"
            data = urlencode(form).encode()
        elif body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        request = Request(self.base + path, data=data, headers=headers)
        try:
            response = self.opener.open(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            payload = response.read()
            assert response.status == expected, f"{path}: HTTP {response.status}, expected {expected}"
            return json.loads(payload) if payload else None

    def refresh_csrf(self):
        token = self.call("/auth/csrf")
        self.headers = {token["headerName"]: token["token"]}

    def login(self, username):
        self.refresh_csrf()
        self.call("/auth/login", form={"username": username, "password": f"{username}-demo-only"})
        self.refresh_csrf()


@contextmanager
def running_jar(jar, java):
    jar = Path(jar).resolve(strict=True)
    with TemporaryDirectory(prefix="nutrilog-eval-") as directory:
        log_path = Path(directory) / "app.log"
        with log_path.open("w") as log:
            process = subprocess.Popen([
                java, "-jar", str(jar), "--server.port=0",
                "--spring.profiles.active=demo-auth,local-db",
            ], cwd=directory, stdout=log, stderr=subprocess.STDOUT)
            try:
                for _ in range(120):
                    if process.poll() is not None:
                        break
                    match = re.search(r"Tomcat started on port (\d+)", log_path.read_text())
                    if match:
                        yield f"http://127.0.0.1:{match[1]}"
                        return
                    time.sleep(0.5)
                raise RuntimeError(log_path.read_text())
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


def development(url):
    client = Client(url)
    client.call("/health")
    candidates = client.call("/foods?" + urlencode({"keyword": "米饭"}))
    assert {row["state"] for row in candidates} == {"RAW", "COOKED"}
    result = client.call("/nutrition/calculate", body={"foodId": 168935, "weightGrams": 200})
    assert result["energy"]["amount"] == 260 and result["source"]["externalId"] == 168935
    print("PASS dev-food: ambiguous rice and 200 g cooked rice")

    client.call("/sessions", 401)
    client.call("/chat", 503, {"message": "200克米饭有多少热量？"})
    client.login("alice")
    client.call("/sessions", 403, {"title": "无 CSRF"}, csrf=False)
    print("PASS dev-auth: anonymous access, disabled model and CSRF")


def holdout(url):
    alice, bob = Client(url), Client(url)
    alice.login("alice")
    bob.login("bob")
    day = "2026-10-29"
    daily = f"/meals/daily?date={day}"
    original_ids = {row["recordId"] for row in alice.call(daily)["records"]}
    preview = alice.call("/meals/previews", 201, {
        "foodId": 169756, "weightGrams": 37.5, "date": day, "mealType": "SNACK",
    })
    path = "/meals/previews/" + preview["operationId"]
    assert preview["status"] == "PENDING" and preview["recordId"] is None
    assert preview["details"]["nutrition"]["energy"]["amount"] == 136.88
    assert {row["recordId"] for row in alice.call(daily)["records"]} == original_ids
    bob.call(path, 404)
    bob.call(path + "/confirm", 404, {})
    confirmed = alice.call(path + "/confirm", body={})
    assert confirmed["status"] == "CONFIRMED"
    assert alice.call(path) == confirmed == alice.call(path + "/confirm", body={})
    record_id = confirmed["recordId"]
    assert record_id not in {row["recordId"] for row in bob.call(daily)["records"]}
    print("PASS holdout-recovery: decimal weight, pending exclusion, replay and owner isolation")

    correction_path = f"/meals/records/{record_id}/correction-previews"
    note = f"eval-{uuid4()}"
    old_version = confirmed["version"]
    first = alice.call(correction_path, 201, {"expectedVersion": old_version, "note": note})
    stale = alice.call(correction_path, 201, {"expectedVersion": old_version, "weightGrams": 40})
    updated = alice.call(f"/meals/previews/{first['operationId']}/confirm", body={})
    conflict = alice.call(f"/meals/previews/{stale['operationId']}/confirm", 409, {})
    assert conflict["code"] == "RECORD_CONFLICT" and updated["version"] == old_version + 1
    records = alice.call(daily)["records"]
    assert len(records) == len(original_ids) + 1
    current = next(row for row in records if row["recordId"] == record_id)
    assert current["details"]["note"] == note
    assert current["details"]["nutrition"] == confirmed["details"]["nutrition"]
    print("PASS holdout-conflict: competing corrections preserve the committed nutrition snapshot")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("jar", nargs="?")
    target.add_argument("--url", help="dedicated demo instance; holdout writes meal records")
    parser.add_argument("--java", default="java")
    parser.add_argument("--suite", choices=("dev", "holdout", "all"), default="dev")
    args = parser.parse_args()

    def evaluate(url):
        if args.suite in ("dev", "all"):
            development(url)
        if args.suite in ("holdout", "all"):
            holdout(url)

    if args.url:
        evaluate(args.url)
    else:
        with running_jar(args.jar, args.java) as url:
            evaluate(url)


if __name__ == "__main__":
    main()

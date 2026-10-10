from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import uuid

from fastapi.testclient import TestClient
from app import create_app
from storage import Store


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.path = str(Path(self.temp.name) / "readings.sqlite3")
        self.app = create_app(self.path)
        self.client = TestClient(self.app)
        self.client.__enter__()
        self.store = self.app.state.store
        self.admin = self.store.create_admin()
        self.mom = self.store.create_device("妈妈")
        self.dad = self.store.create_device("爸爸")
        self.reading = {"schemaVersion":1, "recordId":str(uuid.uuid4()), "systolic":128,
            "diastolic":76, "pulse":72, "measuredAt":1791591300000, "note":"起床后",
            "timeZone":"Asia/Shanghai", "utcOffsetMinutes":480, "source":"manual"}

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.temp.cleanup()

    def auth(self, token):
        return {"Authorization":"Bearer " + token}

    def save(self, reading=None, device=None):
        return self.client.post("/api/v1/measurements", json=reading or self.reading,
                                headers=self.auth((device or self.mom)["token"]))

    def get(self, path="/api/v1/measurements"):
        return self.client.get(path, headers=self.auth(self.admin))

    def test_health_and_static_page_expose_no_data(self):
        self.save()
        self.assertEqual(self.client.get("/healthz").json(), {"status":"ok", "schemaVersion":1})
        for path in ("/", "/dashboard.js", "/dashboard.css"):
            response = self.client.get(path)
            self.assertEqual(response.status_code, 200)
            self.assertNotIn(self.admin, response.text)
            self.assertIn("no-store", response.headers["cache-control"])
            self.assertIn("frame-ancestors 'none'", response.headers["content-security-policy"])
        self.assertEqual(self.client.get("/docs").status_code, 404)

    def test_all_record_routes_require_authentication(self):
        for path in ("/api/v1/measurements", "/api/v1/devices", "/api/v1/device", "/api/v1/measurements.csv"):
            self.assertEqual(self.client.get(path).status_code, 401)
        self.assertEqual(self.client.post("/api/v1/measurements", json=self.reading).status_code, 401)

    def test_upload_and_admin_scopes_are_separate(self):
        self.assertEqual(self.client.get("/api/v1/measurements", headers=self.auth(self.mom["token"])).status_code, 401)
        self.assertEqual(self.client.post("/api/v1/measurements", json=self.reading, headers=self.auth(self.admin)).status_code, 401)
        self.assertEqual(self.client.get("/api/v1/device", headers=self.auth(self.mom["token"])).json()["memberName"], "妈妈")

    def test_saved_reading_preserves_time_values_and_owner(self):
        response = self.save()
        self.assertEqual(response.status_code, 201)
        self.assertTrue(response.json()["saved"])
        result = self.get().json()
        self.assertEqual(result["total"], 1)
        row = result["records"][0]
        self.assertEqual((row["systolic"],row["diastolic"],row["pulse"]), (128,76,72))
        self.assertEqual(row["measuredAt"], self.reading["measuredAt"])
        self.assertEqual(row["memberName"], "妈妈")
        self.assertEqual(row["deviceId"], self.mom["id"])

    def test_duplicate_is_idempotent_but_conflicting_contents_are_rejected(self):
        self.assertEqual(self.save().status_code, 201)
        duplicate = self.save()
        self.assertEqual(duplicate.status_code, 200)
        self.assertFalse(duplicate.json()["created"])
        changed = dict(self.reading, systolic=130)
        self.assertEqual(self.save(changed).status_code, 409)
        self.assertEqual(self.get().json()["total"], 1)

    def test_same_record_id_on_different_devices_does_not_mix_owners(self):
        self.save()
        self.save(device=self.dad)
        self.assertEqual({row["memberName"] for row in self.get().json()["records"]}, {"妈妈","爸爸"})
        filtered = self.get("/api/v1/measurements?device_id=" + self.mom["id"]).json()
        self.assertEqual(filtered["total"], 1)
        self.assertEqual(filtered["records"][0]["memberName"], "妈妈")

    def test_disabled_device_cannot_upload_and_old_readings_remain(self):
        self.save()
        self.store.disable_device(self.mom["id"])
        self.assertEqual(self.save().status_code, 401)
        self.assertEqual(self.get().json()["total"], 1)

    def test_invalid_or_ambiguous_values_are_not_silently_coerced(self):
        bad = [{"systolic":49}, {"diastolic":128}, {"pulse":221}, {"systolic":"128"},
            {"pulse":True}, {"schemaVersion":True}, {"schemaVersion":2},
            {"note":"x"*2049}, {"timeZone":"<script>"}, {"utcOffsetMinutes":900},
            {"recordId":"not-a-uuid"}, {"memberName":"爸爸"}, {"source":"import"}]
        for changes in bad:
            with self.subTest(changes=list(changes)):
                response = self.save(dict(self.reading, **changes))
                self.assertEqual(response.status_code, 422)
                self.assertEqual(response.json(), {"detail":"Invalid measurement"})
        self.assertEqual(self.get().json()["total"], 0)

    def test_content_type_malformed_and_oversized_bodies(self):
        headers = self.auth(self.mom["token"])
        self.assertEqual(self.client.post("/api/v1/measurements", content="{}", headers=headers).status_code, 415)
        headers["Content-Type"] = "application/json"
        self.assertEqual(self.client.post("/api/v1/measurements", content="{", headers=headers).status_code, 422)
        self.assertEqual(self.client.post("/api/v1/measurements", content="x"*16385, headers=headers).status_code, 413)

    def test_date_range_and_pagination(self):
        start = self.reading["measuredAt"]
        for i in range(3):
            self.save(dict(self.reading, recordId=str(uuid.uuid4()), measuredAt=start+i*1000))
        result = self.get(f"/api/v1/measurements?from_ms={start}&to_ms={start+2000}&limit=1&offset=1").json()
        self.assertEqual(result["total"], 2)
        self.assertEqual(result["records"][0]["measuredAt"], start)
        self.assertEqual(self.get(f"/api/v1/measurements?from_ms={start}&to_ms={start}").status_code, 422)
        self.assertEqual(self.get("/api/v1/measurements?limit=0").status_code, 422)

    def test_csv_has_text_dates_original_offset_and_safe_notes(self):
        self.save(dict(self.reading, note="=1+2"))
        text = self.get("/api/v1/measurements.csv").text
        self.assertIn("测量于 2026-10-10 08:15:00", text)
        self.assertIn("上午", text)
        self.assertIn("'=1+2", text)
        self.assertIn("妈妈", text)

    def test_concurrent_duplicate_requests_save_only_one_row(self):
        reading = dict(self.reading)
        with ThreadPoolExecutor(max_workers=8) as pool:
            outcomes = list(pool.map(lambda _: self.store.save(self.mom["id"], reading), range(8)))
        self.assertEqual(outcomes.count(True), 1)
        self.assertEqual(self.get().json()["total"], 1)

    def test_consistent_backup_and_restart_preserve_records(self):
        self.save()
        backup = str(Path(self.temp.name) / "backup.sqlite3")
        self.store.backup(backup)
        restored = Store(backup)
        restored.initialize()
        self.assertEqual(restored.records()["total"], 1)
        self.assertTrue(restored.authenticate_admin(self.admin))
        self.assertEqual(restored.authenticate_device(self.mom["token"])["id"], self.mom["id"])
        self.assertRaises(FileExistsError, self.store.backup, backup)
        with TestClient(create_app(self.path)) as restarted:
            self.assertEqual(restarted.get("/api/v1/measurements", headers=self.auth(self.admin)).json()["total"], 1)

    def test_tokens_are_hashed_and_admin_rotation_invalidates_old_key(self):
        with self.store.connection() as db:
            saved = db.execute("SELECT value FROM settings UNION ALL SELECT token_hash FROM devices").fetchall()
        self.assertTrue(all(len(row[0]) == 64 for row in saved))
        self.assertNotIn(self.admin, str(saved))
        self.assertNotIn(self.mom["token"], str(saved))
        self.assertRaises(ValueError, self.store.create_admin)
        new_admin = self.store.create_admin(rotate=True)
        self.assertFalse(self.store.authenticate_admin(self.admin))
        self.assertTrue(self.store.authenticate_admin(new_admin))


if __name__ == "__main__":
    unittest.main()

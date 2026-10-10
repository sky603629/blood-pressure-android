from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import uuid

from fastapi.testclient import TestClient
from app import create_app


class OverviewTests(unittest.TestCase):
    def setUp(self):
        self.temp=TemporaryDirectory()
        self.api=create_app(str(Path(self.temp.name)/"overview.sqlite3"))
        self.client=TestClient(self.api).__enter__()
        self.store=self.api.state.store
        self.key=self.store.create_admin()
        self.mom=self.store.create_device("妈妈")
        self.dad=self.store.create_device("爸爸")

    def tearDown(self):
        self.client.__exit__(None,None,None)
        self.temp.cleanup()

    def save(self, time, sys=120, dia=80, pulse=70, device=None, offset=480):
        ms=int(datetime.fromisoformat(time).timestamp()*1000)
        self.store.save((device or self.mom)["id"],dict(recordId=str(uuid.uuid4()),
            systolic=sys,diastolic=dia,pulse=pulse,measuredAt=ms,note="合成测试",
            timeZone="Asia/Shanghai",utcOffsetMinutes=offset,source="manual"))
        return ms

    def get(self,query=""):
        response=self.client.get("/api/v1/overview"+query,headers={"Authorization":"Bearer "+self.key})
        self.assertEqual(response.status_code,200)
        return response.json()

    def test_requires_viewing_key_and_never_exposes_data_to_upload_key(self):
        self.assertEqual(self.client.get("/api/v1/overview").status_code,401)
        self.assertEqual(self.client.get("/api/v1/overview",headers={"Authorization":"Bearer "+self.mom["token"]}).status_code,401)

    def test_empty_has_no_fabricated_readings(self):
        data=self.get()
        self.assertEqual(data["summary"]["count"],0)
        self.assertIsNone(data["summary"]["systolic"])
        self.assertIsNone(data["latest"])
        self.assertEqual(data["daily"],[])
        self.assertEqual(data["periods"],[])

    def test_averages_cover_all_records_not_only_one_page(self):
        for i in range(61):self.save("2026-10-09T00:00:00+00:00",sys=100+i)
        data=self.get()
        self.assertEqual(data["summary"]["count"],61)
        self.assertEqual(data["summary"]["systolic"],130)
        self.assertEqual(data["summary"]["minSystolic"],100)
        self.assertEqual(data["summary"]["maxSystolic"],160)
        self.assertEqual(data["summary"]["days"],1)
        self.assertEqual(data["daily"][0]["count"],61)

    def test_daily_means_and_periods_never_mix_family_members(self):
        self.save("2026-10-09T00:00:00+00:00",100)
        self.save("2026-10-09T04:00:00+00:00",140)
        self.save("2026-10-09T11:00:00+00:00",160,device=self.dad)
        data=self.get()
        self.assertEqual(data["summary"]["members"],2)
        self.assertEqual(len(data["daily"]),2)
        mom=next(row for row in data["daily"] if row["deviceId"]==self.mom["id"])
        self.assertEqual(mom["systolic"],120)
        self.assertEqual({r["period"] for r in data["periods"]},{"morning","afternoon","evening"})
        selected=self.get("?device_id="+self.mom["id"])
        self.assertEqual(selected["summary"]["count"],2)
        self.assertEqual(selected["summary"]["members"],1)
        self.assertTrue(all(row["deviceId"]==self.mom["id"] for row in selected["daily"]+selected["periods"]))

    def test_original_phone_offset_controls_day_and_period_boundaries(self):
        for utc,expected in [("2026-10-08T20:59:00+00:00","night"),
                ("2026-10-08T21:00:00+00:00","morning"),
                ("2026-10-09T04:00:00+00:00","afternoon"),
                ("2026-10-09T10:00:00+00:00","evening")]:
            self.save(utc)
        data=self.get()
        self.assertEqual(data["summary"]["days"],1)
        self.assertEqual(data["daily"][0]["date"],"2026-10-09")
        self.assertEqual({r["period"] for r in data["periods"]},{"night","morning","afternoon","evening"})

    def test_date_filter_exclusive_end_and_latest_measured_time(self):
        first=self.save("2026-10-09T00:00:00+00:00",110)
        last=self.save("2026-10-09T10:00:00+00:00",150)
        data=self.get(f"?from_ms={first}&to_ms={last}")
        self.assertEqual(data["summary"]["count"],1)
        self.assertEqual(data["latest"]["measuredAt"],first)
        self.assertEqual(self.get()["latest"]["measuredAt"],last)
        response=self.client.get(f"/api/v1/overview?from_ms={last}&to_ms={first}",headers={"Authorization":"Bearer "+self.key})
        self.assertEqual(response.status_code,422)

    def test_member_group_scopes_statistics_pagination_and_csv(self):
        hidden=self.store.create_device("不显示的手机")
        first=self.save("2026-10-09T00:00:00+00:00",110)
        last=self.save("2026-10-09T01:00:00+00:00",140,device=self.dad)
        self.save("2026-10-09T02:00:00+00:00",200,device=hidden)
        query=f"?device_ids={self.mom['id']}&device_ids={self.dad['id']}"
        data=self.get(query)
        self.assertEqual(data["summary"]["count"],2)
        self.assertEqual(data["summary"]["systolic"],125)
        self.assertEqual(data["latest"]["measuredAt"],last)
        self.assertEqual({row["deviceId"] for row in data["daily"]},{self.mom["id"],self.dad["id"]})
        self.assertTrue(all(row["deviceId"]!=hidden["id"] for row in data["periods"]))
        headers={"Authorization":"Bearer "+self.key}
        page=self.client.get("/api/v1/measurements"+query+"&limit=1&offset=1",headers=headers).json()
        self.assertEqual(page["total"],2)
        self.assertEqual(page["records"][0]["deviceId"],self.mom["id"])
        csv=self.client.get("/api/v1/measurements.csv"+query,headers=headers)
        self.assertEqual(csv.status_code,200)
        self.assertNotIn(hidden["name"],csv.text)
        self.assertIn(self.mom["name"],csv.text)
        self.assertIn(self.dad["name"],csv.text)
        self.assertEqual(self.get(query+f"&from_ms={first}&to_ms={last}")["summary"]["count"],1)

    def test_member_group_validation_and_empty_scope_never_broadens(self):
        self.save("2026-10-09T00:00:00+00:00")
        self.assertEqual(self.get(f"?device_ids={self.mom['id']}&device_ids={self.mom['id']}")["summary"]["count"],1)
        self.assertEqual(self.get("?device_ids="+str(uuid.uuid4()))["summary"]["count"],0)
        self.assertEqual(self.store.overview(())["summary"]["count"],0)
        headers={"Authorization":"Bearer "+self.key}
        for query in (f"?device_id={self.mom['id']}&device_ids={self.dad['id']}","?device_ids=invalid", "?device_ids=",
                      "?"+"&".join("device_ids="+self.mom["id"] for _ in range(101))):
            self.assertEqual(self.client.get("/api/v1/overview"+query,headers=headers).status_code,422)


if __name__=="__main__":unittest.main()

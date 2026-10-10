"""Synthetic snapshots only: never uses the deployed family database."""
from pathlib import Path
from contextlib import closing
from tempfile import TemporaryDirectory
import hashlib
import json
import sqlite3
import unittest
import uuid

from fastapi.testclient import TestClient
from app import create_app
from storage import Store, local_record_id


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.path = str(Path(self.temp.name)/"test.sqlite3")
        self.api = create_app(self.path)
        self.client = TestClient(self.api).__enter__()
        self.store = self.api.state.store
        self.admin = self.store.create_admin()
        self.device = self.store.create_device("家人甲")
        self.other = self.store.create_device("家人乙")
        self.installation = str(uuid.uuid4())

    def tearDown(self):
        self.client.__exit__(None,None,None)
        self.temp.cleanup()

    def auth(self, device=None):
        return {"Authorization":"Bearer "+(device or self.device)["token"], "Content-Type":"application/json"}

    def row(self, local_id, **changes):
        return dict(dict(schemaVersion=2, localId=local_id,
            recordId=local_record_id(self.installation,local_id), systolic=128,diastolic=76,pulse=72,
            measuredAt=1791591300000+local_id, note="合成测试\n非真实记录",timeZone="Asia/Shanghai",
            utcOffsetMinutes=480,source="manual"), **changes)

    def prepare(self, rows, version=1, watermark=None, installation=None, digest=None):
        pages = [json.dumps({"records":rows[i:i+25]},ensure_ascii=False,separators=(",",":")).encode()
                 for i in range(0,len(rows),25)]
        fingerprint = hashlib.sha256("".join(hashlib.sha256(page).hexdigest()+"\n" for page in pages).encode()).hexdigest()
        metadata = dict(installationId=installation or self.installation,version=version,
            highWatermark=watermark if watermark is not None else max([r["localId"] for r in rows],default=0),
            recordCount=len(rows),digest=digest or fingerprint)
        response = self.client.post("/api/v2/snapshots",json=metadata,headers=self.auth())
        return response,pages,metadata

    def stage(self, sync_id, index, body, device=None):
        return self.client.put(f"/api/v2/snapshots/{sync_id}/pages/{index}",content=body,headers=self.auth(device))

    def commit(self, sync_id, device=None):
        return self.client.post(f"/api/v2/snapshots/{sync_id}/commit",headers=self.auth(device))

    def sync(self, rows, version=1, watermark=None):
        start,pages,_ = self.prepare(rows,version,watermark)
        self.assertEqual(start.status_code,200,start.text)
        job = start.json()["syncId"]
        for i,page in enumerate(pages):
            self.assertEqual(self.stage(job,i,page).status_code,200)
        result = self.commit(job)
        self.assertEqual(result.status_code,200,result.text)
        return result.json()

    def records(self):
        return self.store.records(self.device["id"],limit=200)["records"]

    def test_old_rows_are_backfilled_before_new_and_v1_adopted_without_duplicate(self):
        old = self.row(2,source="camera")
        legacy = {k:v for k,v in old.items() if k!="localId"}
        legacy["schemaVersion"] = 1
        self.store.save(self.device["id"],legacy)
        adopted = self.row(2,source="legacy",timeZone="UTC",utcOffsetMinutes=0)
        self.sync([self.row(1),adopted,self.row(3,source="import")])
        self.assertEqual(len(self.records()),3)
        actual = next(r for r in self.records() if r["recordId"]==old["recordId"])
        self.assertEqual((actual["source"],actual["timeZone"],actual["utcOffsetMinutes"]),("camera","Asia/Shanghai",480))
        self.assertEqual(self.client.post("/api/v1/measurements",json=legacy,headers=self.auth()).status_code,409)

    def test_offline_edit_delete_import_and_add_converge_without_affecting_other_family(self):
        other = {k:v for k,v in self.row(1).items() if k!="localId"}
        other["schemaVersion"] = 1
        self.store.save(self.other["id"],other)
        self.sync([self.row(1),self.row(2),self.row(3)])
        ack = self.sync([self.row(1,systolic=142,note="修改备注"),self.row(4,source="import"),self.row(5)],2)
        self.assertEqual(ack["deletedCount"],2)
        self.assertEqual(len(self.records()),3)
        self.assertEqual(self.store.records(self.other["id"])["total"],1)
        self.assertEqual(next(r for r in self.records() if r["recordId"]==local_record_id(self.installation,1))["systolic"],142)
        self.sync([],3,5)
        self.assertEqual(self.records(),[])
        self.assertEqual(self.store.records(self.other["id"])["total"],1)

    def test_incomplete_upload_never_changes_visible_rows_and_retry_is_idempotent(self):
        self.sync([self.row(1)])
        rows = [self.row(i,note="新的记录") for i in range(1,27)]
        start,pages,metadata = self.prepare(rows,2)
        job = start.json()["syncId"]
        self.assertEqual(self.stage(job,0,pages[0]).status_code,200)
        self.assertEqual(self.stage(job,0,pages[0]).status_code,200)
        self.assertEqual(self.commit(job).status_code,409)
        self.assertEqual(len(self.records()),1)
        self.assertEqual(self.records()[0]["note"],"合成测试\n非真实记录")
        restart = Store(self.path)
        restart.initialize()
        self.assertEqual(restart.begin_snapshot(self.device["id"],metadata)["syncId"],job)
        self.assertEqual(self.stage(job,1,pages[1]).status_code,200)
        self.assertEqual(self.commit(job).status_code,200)
        lost_ack = self.client.post("/api/v2/snapshots",json=metadata,headers=self.auth()).json()
        self.assertTrue(lost_ack["committed"])
        self.assertEqual(lost_ack["recordCount"],26)

    def test_newer_snapshot_supersedes_old_and_out_of_order_requests_cannot_overwrite(self):
        first,pages,_ = self.prepare([self.row(1)],1)
        old_job = first.json()["syncId"]
        self.stage(old_job,0,pages[0])
        self.sync([self.row(1,systolic=150)],2)
        self.assertEqual(self.commit(old_job).status_code,409)
        self.assertEqual(self.prepare([self.row(1)],1)[0].status_code,409)
        self.assertEqual(self.prepare([self.row(1,systolic=151)],2)[0].status_code,409)
        self.assertEqual(self.records()[0]["systolic"],150)

    def test_bad_checksums_duplicate_ids_or_order_roll_back_entire_commit(self):
        self.sync([self.row(1)])
        for version,rows,digest in [(2,[self.row(1,systolic=150)],"0"*64),
                (3,[self.row(1,systolic=150),self.row(1)],None),
                (4,[self.row(2),self.row(1)],None)]:
            start,pages,_ = self.prepare(rows,version,watermark=2,digest=digest)
            job = start.json()["syncId"]
            self.assertEqual(self.stage(job,0,pages[0]).status_code,200)
            self.assertEqual(self.commit(job).status_code,409)
            self.assertEqual(len(self.records()),1)
            self.assertEqual(self.records()[0]["systolic"],128)

    def test_wrong_device_record_owner_and_changed_page_rejected(self):
        start,pages,_ = self.prepare([self.row(1)])
        job = start.json()["syncId"]
        self.assertEqual(self.stage(job,0,pages[0],self.other).status_code,409)
        wrong = json.dumps({"records":[self.row(1,recordId=str(uuid.uuid4()))]}).encode()
        self.assertEqual(self.stage(job,0,wrong).status_code,409)
        self.assertEqual(self.stage(job,0,pages[0]).status_code,200)
        different = json.dumps({"records":[self.row(1,systolic=144)]}).encode()
        self.assertEqual(self.stage(job,0,different).status_code,409)
        self.assertEqual(self.commit(job,self.other).status_code,409)

    def test_reinstall_or_fresh_empty_snapshot_cannot_wipe_old_server_records(self):
        old = {k:v for k,v in self.row(1).items() if k!="localId"}
        old["schemaVersion"] = 1
        self.store.save(self.device["id"],old)
        self.assertEqual(self.prepare([])[0].status_code,409)
        self.sync([self.row(1)])
        self.assertEqual(self.prepare([],2,watermark=1,installation=str(uuid.uuid4()))[0].status_code,409)
        self.assertEqual(len(self.records()),1)

    def test_short_custom_web_key_works_only_for_viewing_and_is_salted(self):
        key = "family-key-123"
        self.store.set_admin_key(key)
        headers = {"Authorization":"Bearer "+key}
        self.assertEqual(self.client.get("/api/v1/devices",headers=headers).status_code,200)
        self.assertEqual(self.client.get("/api/v1/devices",headers={"Authorization":"Bearer "+self.admin}).status_code,401)
        self.assertEqual(self.client.post("/api/v2/snapshots",json={},headers=headers).status_code,401)
        with self.store.connection() as db:
            saved = db.execute("SELECT value FROM settings").fetchone()[0]
        self.assertTrue(saved.startswith("pbkdf2$"))
        self.assertNotIn(key,saved)
        self.store.set_admin_key(key)
        with self.store.connection() as db:
            self.assertNotEqual(saved,db.execute("SELECT value FROM settings").fetchone()[0])

    def test_disabled_key_or_viewing_key_cannot_stage_or_commit(self):
        start,pages,_ = self.prepare([self.row(1)])
        job = start.json()["syncId"]
        self.assertEqual(self.client.put(f"/api/v2/snapshots/{job}/pages/0",content=pages[0],
            headers={"Authorization":"Bearer "+self.admin,"Content-Type":"application/json"}).status_code,401)
        self.assertEqual(self.client.post("/api/v2/snapshots",json={}).status_code,401)
        self.store.disable_device(self.device["id"])
        self.assertEqual(self.stage(job,0,pages[0]).status_code,401)
        self.assertEqual(self.commit(job).status_code,401)

    def test_strict_shape_sizes_and_watermark_validation(self):
        _,_,metadata = self.prepare([self.row(1)])
        for changes in ({"version":True},{"version":"2"},{"recordCount":20001},{"highWatermark":0},{"digest":"X"*64}):
            self.assertEqual(self.client.post("/api/v2/snapshots",json=dict(metadata,**changes),headers=self.auth()).status_code,422)
        job = self.prepare([self.row(1)])[0].json()["syncId"]
        self.assertEqual(self.stage(job,0,b"x"*(512*1024+1)).status_code,413)
        self.assertEqual(self.stage(job,0,b"{").status_code,422)
        self.assertEqual(self.stage(job,1,b'{"records":[]}').status_code,422)
        bad = json.dumps({"records":[self.row(1,systolic="128")]}).encode()
        self.assertEqual(self.stage(job,0,bad).status_code,422)

    def test_normalized_uuid_is_stored_and_legacy_time_edit_updates_offset(self):
        self.sync([self.row(1,source="camera")])
        changed = self.row(1,source="legacy",measuredAt=1791591400000,timeZone="UTC",utcOffsetMinutes=0)
        # Upper-case wire UUIDs are normalized by validation, not reparsed raw at commit.
        changed["recordId"] = changed["recordId"].upper()
        self.sync([changed],2)
        row = self.records()[0]
        self.assertEqual(row["recordId"],changed["recordId"].lower())
        self.assertEqual((row["timeZone"],row["utcOffsetMinutes"],row["source"]),("UTC",0,"camera"))

    def test_v1_database_upgrade_preserves_tokens_and_records(self):
        path = Path(self.temp.name)/"old.sqlite3"
        # Build the actual original schema before adding the V2 columns/tables.
        source = (Path(__file__).parents[1]/"storage.py").read_text(encoding="utf-8")
        schema = source.split('db.executescript("""',1)[1].split('""")',1)[0]
        with closing(sqlite3.connect(path)) as db, db:
            db.executescript(schema)
            db.execute("INSERT INTO devices VALUES(?,?,?,1)",(self.device["id"],"旧设备","hash"))
            row = self.row(1)
            db.execute("INSERT INTO measurements VALUES(?,?,?,?,?,?,?,?,?,?,?)",(self.device["id"],row["recordId"],128,76,72,row["measuredAt"],row["note"],"Asia/Shanghai",480,"camera",1))
        migrated = Store(str(path))
        migrated.initialize()
        migrated.initialize()
        self.assertEqual(migrated.records()["total"],1)
        self.assertEqual(migrated.records()["records"][0]["source"],"camera")
        with migrated.connection() as db:
            self.assertEqual(db.execute("PRAGMA user_version").fetchone()[0],2)
            self.assertEqual(db.execute("SELECT token_hash FROM devices").fetchone()[0],"hash")


if __name__=="__main__":
    unittest.main()

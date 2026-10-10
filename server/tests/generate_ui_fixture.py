"""Create a separate synthetic preview database; refuses to overwrite an existing file."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import math
import os
import sys
import uuid
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from storage import Store


def main():
    path=Path(__file__).resolve().parents[2]/"build"/"ui-preview.sqlite3"
    if path.exists():
        raise SystemExit("Preview database already exists; reuse it instead of overwriting.")
    store=Store(str(path))
    store.initialize()
    store.set_admin_key(os.environ.get("BP_PREVIEW_KEY","preview-key-123"))
    mom,dad=store.create_device("老妈手机"),store.create_device("老爸手机")
    store.create_device("新手机")
    today=datetime.now(timezone(timedelta(hours=8))).replace(hour=0,minute=0,second=0,microsecond=0)
    for day in range(60):
        for device,index in [(mom,0),(dad,1)]:
            for hour in ([8,14,20] if index==0 else [8,20]):
                time=today-timedelta(days=60-day)+timedelta(hours=hour)
                store.save(device["id"],dict(recordId=str(uuid.uuid4()),
                    systolic=round(126+index*9+math.sin(day/4)*8+(hour-14)/3),
                    diastolic=round(77+index*4+math.cos(day/5)*5),pulse=round(70+math.sin(day/3)*6),
                    measuredAt=int(time.timestamp()*1000),note="早餐后，休息五分钟再测量。" if hour==8 else "晚饭后，坐着休息。" if hour==20 else "",
                    timeZone="Asia/Shanghai",utcOffsetMinutes=480,source="camera" if day%2==0 else "manual"))
    print("Created 300 synthetic preview readings in "+str(path))


if __name__=="__main__":main()

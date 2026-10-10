import hashlib
import re
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from fastapi.testclient import TestClient
from app import STATIC, create_app


class AssetCacheTests(unittest.TestCase):
    def setUp(self):
        self.temp=TemporaryDirectory()
        self.client=TestClient(create_app(str(Path(self.temp.name)/"cache.sqlite3"))).__enter__()

    def tearDown(self):
        self.client.__exit__(None,None,None)
        self.temp.cleanup()

    def test_only_content_addressed_public_assets_are_cacheable(self):
        page=self.client.get("/")
        self.assertEqual(page.headers["cache-control"],"no-store")
        for name in ("dashboard.js","dashboard.css"):
            url=re.search(r'"(/'+re.escape(name)+r'\?v=[a-f0-9]{16})"',page.text).group(1)
            response=self.client.get(url)
            self.assertEqual(response.status_code,200)
            self.assertEqual(response.content,(STATIC/name).read_bytes())
            self.assertEqual(url.split("?v=")[1],hashlib.sha256(response.content).hexdigest()[:16])
            self.assertEqual(response.headers["cache-control"],"public, max-age=31536000, immutable")
            self.assertIn("script-src 'self'",response.headers["content-security-policy"])
            self.assertEqual(self.client.get("/"+name).headers["cache-control"],"no-store")

    def test_unknown_versions_and_private_endpoints_never_become_public(self):
        for name in ("dashboard.js","dashboard.css"):
            response=self.client.get("/"+name+"?v=not-current")
            self.assertEqual(response.status_code,404)
            self.assertEqual(response.headers["cache-control"],"no-store")
        store=self.client.app.state.store
        key=store.create_admin()
        for endpoint in ("/api/v1/devices","/api/v1/overview","/api/v1/measurements","/api/v1/measurements.csv"):
            for headers in ({},{"Authorization":"Bearer "+key}):
                response=self.client.get(endpoint,headers=headers)
                self.assertEqual(response.status_code,200 if headers else 401)
                self.assertEqual(response.headers["cache-control"],"no-store")


if __name__=="__main__":unittest.main()

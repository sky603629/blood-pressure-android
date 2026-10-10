"""Exercise the real Java HTTPS client against a temporary server and a local test certificate.

Run test-behavior.ps1 first, then this script from server/ with its Python dependencies installed.
All readings and keys are synthetic; nothing reaches the deployed family service.
"""
from datetime import datetime, timedelta, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
import os
import socket
import ssl
import subprocess
import sys
import time
import urllib.request

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from storage import Store


def main():
    project = Path(__file__).resolve().parents[2]
    java_bin = Path(os.environ.get("JAVA_HOME", r"D:\DevSoftware\JDK17")) / "bin"
    classpath = os.pathsep.join(str(project / part) for part in
        ("build/regression-classes", "build/test-deps/android-json.jar"))
    with TemporaryDirectory() as temporary:
        folder = Path(temporary)
        private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(private.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now-timedelta(minutes=5)).not_valid_after(now+timedelta(days=1))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost")]), critical=False)
            .sign(private, hashes.SHA256()))
        key_path, cert_path = folder/"test-key.pem", folder/"test-cert.pem"
        key_path.write_bytes(private.private_bytes(serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        trust_path = folder/"test-trust.p12"
        subprocess.run([str(java_bin/"keytool.exe"), "-importcert", "-noprompt", "-alias", "localhost",
            "-file", str(cert_path), "-keystore", str(trust_path), "-storetype", "PKCS12",
            "-storepass", "temporary-test-only"], check=True, capture_output=True)

        store = Store(str(folder/"readings.sqlite3"))
        store.initialize()
        device = store.create_device("联调测试")
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            port = available.getsockname()[1]
        environment = dict(os.environ, BP_DATABASE=str(store.path), BP_TEST_TOKEN=device["token"])
        service = subprocess.Popen([sys.executable, "-m", "uvicorn", "app:app", "--host", "127.0.0.1",
            "--port", str(port), "--ssl-keyfile", str(key_path), "--ssl-certfile", str(cert_path),
            "--no-access-log", "--log-level", "warning"], cwd=project/"server", env=environment,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            base = f"https://localhost:{port}"
            context = ssl.create_default_context(cafile=str(cert_path))
            for _ in range(100):
                try:
                    with urllib.request.urlopen(base+"/healthz", context=context, timeout=1) as response:
                        if response.status == 200:
                            break
                except OSError:
                    if service.poll() is not None:
                        raise RuntimeError("Temporary HTTPS server failed to start")
                    time.sleep(0.1)
            else:
                raise RuntimeError("Temporary HTTPS server did not become healthy")
            arguments = ["-cp", classpath, "com.pressurediary.app.UploadProtocolTests", base]
            untrusted = subprocess.run([str(java_bin/"java.exe"), *arguments], env=environment,
                                       capture_output=True, text=True)
            if untrusted.returncode == 0 or "SSLHandshakeException" not in untrusted.stderr:
                raise AssertionError("Client did not reject an untrusted HTTPS certificate")
            subprocess.run([str(java_bin/"java.exe"), "-Djavax.net.ssl.trustStore="+str(trust_path),
                "-Djavax.net.ssl.trustStorePassword=temporary-test-only", *arguments],
                env=environment, check=True)
            result = store.records()
            assert result["total"] == 1, "Duplicate upload created another row"
            row = result["records"][0]
            assert (row["systolic"], row["diastolic"], row["pulse"]) == (128,76,72)
            assert row["measuredAt"] == 1791591300123 and row["utcOffsetMinutes"] == 480
            assert row["note"] == "早餐后\n休息五分钟" and row["source"] == "camera"
            subprocess.run([str(java_bin/"java.exe"), "-Djavax.net.ssl.trustStore="+str(trust_path),
                "-Djavax.net.ssl.trustStorePassword=temporary-test-only", "-cp",classpath,
                "com.pressurediary.app.SyncProtocolTests",base],env=environment,check=True)
            result = store.records()
            assert result["total"] == 2, "Snapshots did not converge after add/edit/delete/import"
            assert {r["source"] for r in result["records"]} == {"import","camera"}
            assert all(r["utcOffsetMinutes"] == 480 and r["systolic"] == 128 for r in result["records"])
            print("PASS: real Java-to-service HTTPS, certificate rejection, duplicate handling and exact stored data")
        finally:
            service.terminate()
            service.wait(timeout=10)


if __name__ == "__main__":
    main()

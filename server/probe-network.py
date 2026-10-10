"""Compare fresh HTTPS connections to this service; never sends keys or measurements."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import ipaddress
import json
from pathlib import Path
import shutil
import statistics
import subprocess
from urllib.parse import urlsplit


def address(value):
    try:return str(ipaddress.ip_address(value))
    except ValueError as error:raise argparse.ArgumentTypeError("Expected an IPv4 or IPv6 address") from error


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url",default="https://bp.282412.xyz",help="HTTPS service origin, without a path")
    parser.add_argument("--origin-ip",type=address,help="Original server IP for a direct comparison")
    parser.add_argument("--candidate-ip",type=address,action="append",default=[],help="A specific edge IP; repeat up to 8 times")
    parser.add_argument("--samples",type=int,default=5,help="Fresh connections per route (3-10)")
    parser.add_argument("--label",default="unlabelled",help="City/carrier/time-period label")
    parser.add_argument("--output",type=Path,help="Write aggregate timing results to a JSON file")
    args=parser.parse_args()
    parsed=urlsplit(args.url)
    if (parsed.scheme!="https" or not parsed.hostname or parsed.username or parsed.password or
            parsed.path not in ("","/") or parsed.query or parsed.fragment):
        parser.error("Use an HTTPS origin without credentials, path, query or fragment")
    if not 3<=args.samples<=10 or len(args.candidate_ip)>8:parser.error("Use 3-10 samples and at most 8 candidate IPs")
    curl=shutil.which("curl.exe") or shutil.which("curl")
    if not curl:parser.error("Install curl before running this comparison")
    host,port=parsed.hostname,parsed.port or 443
    endpoint=args.url.rstrip("/")+"/healthz"
    routes=[("DNS/default",None)]
    if args.origin_ip:routes.append(("Origin/direct",args.origin_ip))
    routes.extend(("Candidate/"+ip,ip) for ip in dict.fromkeys(args.candidate_ip))

    def measure(route):
        label,ip=route
        samples,errors=[],[]
        for _ in range(args.samples):
            command=[curl,"--disable","--noproxy","*","-sS","--connect-timeout","4","--max-time","8",
                     "--max-filesize","4096","--user-agent","pressure-diary-network-probe/1",
                     "-w","\nTIMING:%{json}"]
            if ip:command.extend(["--resolve",f"{host}:{port}:{'['+ip+']' if ':' in ip else ip}"])
            try:
                response=subprocess.run(command+[endpoint],capture_output=True,encoding="utf-8",errors="replace",timeout=11)
                body,separator,metrics=response.stdout.rpartition("\nTIMING:")
                timing=json.loads(metrics) if separator else {}
                code=timing.get("http_code",0)
                if response.returncode or code!=200:
                    errors.append(f"HTTP {code}; curl {response.returncode}"+("; "+response.stderr.strip()[:100] if response.stderr else ""));continue
                data=json.loads(body)
                if not isinstance(data,dict) or data.get("status")!="ok" or data.get("schemaVersion")!=1:
                    errors.append("Response is not this service's health payload");continue
                samples.append({field:float(timing[field])*1000 for field in
                                ("time_namelookup","time_connect","time_appconnect","time_starttransfer","time_total")})
            except (subprocess.TimeoutExpired,ValueError,KeyError) as error:errors.append(type(error).__name__)
        medians={field:round(statistics.median(sample[field] for sample in samples),1)
                 for field in samples[0]} if samples else {}
        result={"route":label,"ip":ip,"success":len(samples),"attempts":args.samples,
                "eligible":len(samples)==args.samples,"medianMs":medians,
                "worstTotalMs":round(max(sample["time_total"] for sample in samples),1) if samples else None,
                "errors":sorted(set(errors))}
        print(label+": "+str(len(samples))+"/"+str(args.samples)+" valid; median TTFB "+
              str(medians.get("time_starttransfer","—"))+" ms",flush=True)
        return result

    with ThreadPoolExecutor(max_workers=4) as pool:results=list(pool.map(measure,routes))
    qualified=sorted((result for result in results if result["eligible"]),key=lambda result:result["medianMs"]["time_starttransfer"])
    report={"measuredAt":datetime.now(timezone.utc).isoformat(),"label":args.label,"url":args.url,
            "bestTestedRoute":qualified[0]["route"] if qualified else None,"routes":results}
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print("Best in this test: "+(report["bestTestedRoute"] or "none qualified"))
    print("No DNS, hosts, proxy, certificate or server setting was changed. Repeat on each carrier and at peak hours.")
    return 0 if qualified else 1


if __name__=="__main__":raise SystemExit(main())

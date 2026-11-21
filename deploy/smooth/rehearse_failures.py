"""Real Nginx/container failure injection, confined to the synthetic local stack."""
import concurrent.futures
import json
from pathlib import Path
import re
import threading
import time
import sys
import urllib.request
from unittest.mock import patch
import release
from prepare_rehearsal import ROOT, OUT, command
import rehearse

assert rehearse.BASE == "http://quotation-smooth-uat-edge"
M = release.Release(ROOT+"/config.json")
assert M.c.get("test_only") and M.c["worker"]=="quotation-smooth-uat-backend"
report={"successful_requests":0,"errors":[],"checks":[]}
stop=threading.Event()
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get(path):
    with opener.open(rehearse.BASE+path,timeout=20) as response:
        assert response.status==200
        return response.read()


def sampler():
    while not stop.is_set():
        try:
            assert json.loads(get("/api/v1/auth/csrf"))["code"]=="SUCCESS"
            get("/")
            report["successful_requests"]+=2
        except Exception as error:report["errors"].append(str(error))
        time.sleep(.1)


def expect_failure(callback, text):
    try:callback()
    except RuntimeError as error:
        assert text in str(error),str(error)
    else:raise AssertionError("Expected failure: "+text)
    report["checks"].append(text)


def main():
    baseline=M.marker()
    assert baseline=="web-v1"
    old_html=get("/").decode()
    assets={path:get(path) for path in re.findall(r'["\'](/assets/[^"\']+)["\']',old_html)}
    protocol=json.loads(get("/app-version.json"))["buildId"]
    thread=threading.Thread(target=sampler);thread.start()
    try:
        if "--resume" in sys.argv:
            prior=json.loads((OUT/"failure-rehearsal.json").read_text())
            assert "readiness timed out" in prior["checks"] and not prior["errors"]
            assert not release.inspect(M.c["gateway"]+"-web-broken-v1")["State"]["Running"]
            report["successful_requests"]+=prior["successful_requests"]
            report["checks"].append("readiness timed out")
        else:
            expect_failure(lambda:M.prepare("broken-v1","quotation-smooth-broken:20261121"),"readiness timed out")
        assert M.marker()==baseline
        if "web-v2" not in M.state["slots"]:
            M.prepare("web-v2","quotation-smooth-web:20261121","quotation-smooth-frontend:20261121")
        target=M.state["slots"]["web-v2"]
        original_configuration=release.configuration
        def invalid_config(selected,worker):
            return "invalid nginx configuration;\n" if selected["release"]=="web-v2" else original_configuration(selected,worker)
        with patch("release.configuration",side_effect=invalid_config):
            expect_failure(lambda:M.switch(target),"failed")
        assert M.marker()==baseline
        with patch.object(M,"public_smoke",side_effect=RuntimeError("injected post-switch failure")):
            expect_failure(lambda:M.switch(target),"post-switch failure")
        assert M.marker()==baseline
        # Persist exactly the state left by an interrupted switch, apply new Nginx config, then recover.
        M.state["pending"]={"old":M.state["active"],"target":target,"workers":M.worker_pids()};M.save()
        path=M.gateway_dir/"default.conf"
        release.atomic(path,release.configuration(target,M.c["worker"]));path.chmod(0o644)
        release.run("docker","exec",M.c["gateway"],"nginx","-s","reload");M.wait_marker("web-v2")
        M.recover();assert M.marker()==baseline
        report["checks"].append("interrupted switch recovered")
        with M.lock():
            expect_failure(lambda: acquire_second_lock(),"Another smooth release")
        # Hold a real upload on old web-v1 while switching both backend and frontend.
        rehearse.CSRF=rehearse.request("/api/v1/auth/csrf")
        rehearse.request("/api/v1/auth/login",dict(account="SMOOTHQA",password="isolated-smooth-rehearsal-only-20261121"))
        rehearse.CSRF=rehearse.request("/api/v1/auth/csrf")
        png=__import__('base64').b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aJ1sAAAAASUVORK5CYII=")
        body,kind=rehearse.multipart(png);started=threading.Event()
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(rehearse.slow_request,"/api/v1/quotation-sheet-photos",body,kind,"drain-upload",started)
            assert started.wait(10);time.sleep(.5)
            M.switch(target)
            expect_failure(lambda:M.retire("web-v1"),"in-flight")
            future.result(timeout=60)
        assert json.loads(get("/app-version.json"))["buildId"]==protocol
        for path,data in assets.items():assert get(path)==data,("old asset changed",path)
        assert rehearse.request("/api/v1/auth/me")["account"]=="SMOOTHQA"
        report["checks"].append("frontend switch preserves protocol, old assets, session and upload")
        for _ in range(30):
            listing=release.run("docker","exec",M.c["gateway"],"ps","-o","pid,args")
            if "worker process is shutting down" not in listing:break
            time.sleep(.2)
        M.retire("web-v1")
        assert not release.inspect(M.state["slots"]["web-v1"]["backend"])["State"]["Running"]
        M.switch(M.state["previous"])
        assert M.marker()==baseline
        for path,data in assets.items():assert get(path)==data
        report["checks"].append("retired rollback slot warms before switching")
        assert not report["errors"],report["errors"]
        report["passed"]=True
    finally:
        stop.set();thread.join(30)
        OUT.mkdir(exist_ok=True,parents=True)
        (OUT/"failure-rehearsal.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
        print(json.dumps(report,ensure_ascii=False),flush=True)


def acquire_second_lock():
    with release.Release(ROOT+"/config.json").lock():pass


if __name__=="__main__":main()

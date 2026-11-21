#!/usr/bin/env python3
"""Quotation-only immutable web slots behind a gracefully reloaded gateway.

The existing worker, parser and data services are never stopped or recreated.
Every mutation uses a local deployment lock and durable write-ahead evidence.
"""
import argparse
import contextlib
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.error

from compatibility import verify, sha


def run(*args, input=None, timeout=120):
    result = subprocess.run(args, input=input, capture_output=True, timeout=timeout)
    if result.returncode:
        # Never print docker create arguments or an environment file.
        raise RuntimeError(f"{args[0]} {args[1] if len(args)>1 else ''} failed: "
                           + result.stderr.decode(errors="replace")[-2000:])
    return result.stdout.decode().strip()


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def healthy(name):
    state = inspect(name)["State"]
    return state["Running"] and state.get("Health", {}).get("Status") == "healthy"


def atomic(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with open(temporary, "w", encoding="utf-8", newline="\n") as file:
        os.chmod(temporary, 0o600)
        file.write(value)
        file.flush()
        os.fsync(file.fileno())
    os.replace(temporary, path)


def environment(container):
    return dict(item.split("=", 1) for item in inspect(container)["Config"]["Env"])


def slot(label, backend, frontend):
    return {"release": label, "backend": backend, "frontend": frontend,
            "backend_id": inspect(backend)["Id"], "frontend_id": inspect(frontend)["Id"]}


def safe_name(value):
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,100}", value):
        raise ValueError("Invalid container/release name")
    return value


def configuration(active, worker):
    for name in (active["backend"], active["frontend"], worker):
        safe_name(name)
    label = safe_name(active["release"])
    # No retry of non-idempotent requests, no client refresh or request replay.
    return f'''server {{
    listen 80;
    server_name _;
    client_max_body_size 520m;
    client_body_timeout 600s;
    add_header X-Quotation-Release "{label}" always;
    location = /__quotation_release {{
        default_type application/json;
        add_header Cache-Control no-store always;
        return 200 '{{"release":"{label}"}}';
    }}
    location = /actuator {{ return 404; }}
    location ^~ /actuator/ {{ return 404; }}
    # The Shimo worker owns its in-memory execution guard and connection settings.
    location ~ ^/api/v1/purchase-shimo-sync(?:/|$) {{
        proxy_pass http://{worker}:8088;
        include /etc/nginx/release/proxy.inc;
    }}
    location /api/ {{
        proxy_pass http://{active["backend"]}:8088;
        include /etc/nginx/release/proxy.inc;
    }}
    location / {{
        proxy_pass http://{active["frontend"]}:80;
        include /etc/nginx/release/proxy.inc;
    }}
}}
'''


PROXY = '''proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
proxy_set_header X-Request-ID $http_x_request_id;
proxy_connect_timeout 5s;
proxy_read_timeout 900s;
proxy_send_timeout 900s;
proxy_request_buffering off;
proxy_next_upstream off;
'''


class Release:
    def __init__(self, config):
        self.c = json.loads(Path(config).read_text(encoding="utf-8"))
        self.root = Path(self.c["root"]).resolve()
        if self.root.name != "smooth-release":
            raise ValueError("Managed root must end in smooth-release")
        self.root.mkdir(parents=True, exist_ok=True)
        self.state_path = self.root / "state.json"
        self.gateway_dir = self.root / "gateway"
        self.gateway_dir.mkdir(exist_ok=True)
        for key in ("gateway", "worker", "backend", "frontend", "edge"):
            safe_name(self.c[key])
        if not self.c["gateway"].startswith("quotation-"):
            raise ValueError("Gateway must be quotation scoped")
        self.state = json.loads(self.state_path.read_text()) if self.state_path.exists() else None

    @contextlib.contextmanager
    def lock(self):
        import fcntl
        with open(self.root / "deploy.lock", "w") as file:
            try:
                fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise RuntimeError("Another smooth release is running") from None
            yield

    def event(self, name, **data):
        line = json.dumps({"time": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                           "event": name, **data}, ensure_ascii=False)
        with open(self.root / "events.jsonl", "a", encoding="utf-8") as file:
            file.write(line + "\n")
            file.flush()
            os.fsync(file.fileno())
        print(line, flush=True)

    def save(self):
        atomic(self.state_path, json.dumps(self.state, indent=2))

    def check_identity(self, target):
        for role in ("backend", "frontend"):
            if inspect(target[role])["Id"] != target[role + "_id"]:
                raise RuntimeError("Container changed outside the release manager")
            if not healthy(target[role]):
                raise RuntimeError(f"{role} is not healthy")
        if inspect(self.c["worker"])["Id"] != self.state["worker_id"] or not healthy(self.c["worker"]):
            raise RuntimeError("Worker identity or health changed")

    def inside(self, path, *, backend=None):
        container = backend or self.c["gateway"]
        port = 8088 if backend else 80
        return run("docker", "exec", container, "wget", "-qO-", "-T", "15", f"http://127.0.0.1:{port}{path}")

    def smoke(self, target):
        self.check_identity(target)
        data = json.loads(self.inside("/api/v1/auth/csrf", backend=target["backend"]))
        if data.get("code") != "SUCCESS" or not data.get("data", {}).get("token"):
            raise RuntimeError("Candidate CSRF API contract failed")

    def marker(self):
        return json.loads(self.inside("/__quotation_release"))["release"]

    def worker_pids(self):
        listing = run("docker", "exec", self.c["gateway"], "ps", "-o", "pid,args")
        return [line.strip().split()[0] for line in listing.splitlines()
                if "nginx: worker process" in line]

    def wait_marker(self, label):
        for _ in range(50):
            try:
                if self.marker() == label:
                    return
            except (RuntimeError, ValueError):
                pass
            time.sleep(.2)
        raise RuntimeError("Gateway did not apply candidate configuration")

    def public_smoke(self, label):
        for suffix in ("/__quotation_release", "/api/v1/auth/csrf", "/"):
            request = urllib.request.Request(self.c["public_url"].rstrip("/") + suffix,
                                             headers={"Cache-Control": "no-cache"})
            with urllib.request.urlopen(request, timeout=20) as response:
                data = response.read()
                if response.status != 200:
                    raise RuntimeError("Public smoke request failed")
                if suffix == "/__quotation_release" and json.loads(data)["release"] != label:
                    raise RuntimeError("Public route did not reach candidate")
                if suffix == "/api/v1/auth/csrf" and not json.loads(data).get("data", {}).get("token"):
                    raise RuntimeError("Public API returned an invalid envelope")

    def wait_public(self, label):
        failure = None
        for _ in range(20):
            try:
                self.public_smoke(label)
                return
            except (RuntimeError, ValueError, urllib.error.URLError) as error:
                failure = error
                time.sleep(.5)
        raise RuntimeError("Public route verification failed") from failure

    def bootstrap(self):
        if self.state:
            raise RuntimeError("Already initialized; use status")
        active = slot("legacy", self.c["backend"], self.c["frontend"])
        self.state = {"schema": 1, "active": active, "previous": None, "slots": {},
                      "worker_id": inspect(self.c["worker"])["Id"], "installed": False}
        self.smoke(active)
        atomic(self.gateway_dir / "proxy.inc", PROXY)
        # Nginx workers need to read these mounted public configuration files.
        os.chmod(self.gateway_dir / "proxy.inc", 0o644)
        atomic(self.gateway_dir / "default.conf", configuration(active, self.c["worker"]))
        os.chmod(self.gateway_dir / "default.conf", 0o644)
        args = ["docker", "run", "-d", "--name", self.c["gateway"], "--restart", "unless-stopped",
                "--network", self.c["edge_network"], "--memory", "128m", "--cpus", "0.5",
                "--security-opt", "no-new-privileges:true", "--log-driver", "local",
                "--log-opt", "max-size=20m", "--log-opt", "max-file=5",
                "--label", "com.milano.quotation.smooth=gateway",
                "--mount", f"type=bind,src={self.gateway_dir},dst=/etc/nginx/release,readonly",
                "--mount", f"type=bind,src={self.gateway_dir},dst=/etc/nginx/conf.d,readonly",
                "--health-cmd", "wget -qO- http://127.0.0.1/__quotation_release",
                "--health-interval", "5s", "--health-retries", "3"]
        # Isolated rehearsals may bind loopback only; production has no host port.
        if self.c.get("gateway_port"):
            args += ["-p", f"127.0.0.1:{int(self.c['gateway_port'])}:80"]
        args += [self.c["nginx_image"]]
        run(*args)
        self.wait_marker("legacy")
        self.save()
        self.event("gateway-prepared", serving="legacy", public_route_unchanged=True)

    def install(self):
        if self.state["installed"]:
            raise RuntimeError("Gateway already installed")
        self.smoke(self.state["active"])
        path = Path(self.c["edge_file"])
        old = path.read_text()
        before = {"http://quotation-backend:8088": f"http://{self.c['gateway']}:80",
                  "http://quotation-frontend:80": f"http://{self.c['gateway']}:80"}
        # Rehearsal names may differ, but always require exact known substitutions.
        before = self.c.get("edge_replacements", before)
        candidate = old
        for source, destination in before.items():
            if candidate.count("proxy_pass " + source + ";") != 1:
                raise RuntimeError("Unexpected edge configuration; no changes made")
            if destination != f"http://{self.c['gateway']}:80":
                raise ValueError("Edge destination must be the managed gateway")
            candidate = candidate.replace("proxy_pass " + source + ";", "proxy_pass " + destination + ";")
        # This file may be a single-file bind mount: preserve its inode.
        atomic(self.root / "edge-before.conf", old)
        self.state["pending_install"] = {"before": sha(old.encode()), "after": sha(candidate.encode())}
        self.save()
        self.event("edge-install-intent", before_sha256=sha(old.encode()), after_sha256=sha(candidate.encode()))
        wrote = False
        try:
            if path.read_text() != old:
                raise RuntimeError("Edge changed concurrently")
            with open(path, "w") as file:
                file.write(candidate); file.flush(); os.fsync(file.fileno())
            wrote = True
            run("docker", "exec", self.c["edge"], "nginx", "-t")
            run("docker", "exec", self.c["edge"], "nginx", "-s", "reload")
            self.wait_public("legacy")
        except BaseException:
            if not wrote or path.read_text() != candidate:
                self.event("edge-install-needs-reconciliation", overwritten=False)
                raise
            with open(path, "w") as file:
                file.write(old); file.flush(); os.fsync(file.fileno())
            run("docker", "exec", self.c["edge"], "nginx", "-t")
            run("docker", "exec", self.c["edge"], "nginx", "-s", "reload")
            self.event("edge-install-restored")
            self.state.pop("pending_install", None); self.save()
            raise
        self.state["installed"] = True
        self.state.pop("pending_install", None)
        self.state["edge_sha256"] = sha(candidate.encode())
        self.save()
        self.event("edge-installed", application_containers_unchanged=True)

    def prepare(self, release, image, frontend_image=None):
        safe_name(release)
        if release in self.state["slots"]:
            raise RuntimeError("Release already exists")
        self.check_identity(self.state["active"])
        self.check_capacity()
        package = self.root / "releases" / release
        package.mkdir(parents=True, exist_ok=False)
        image_data = json.loads(run("docker", "image", "inspect", image))[0]
        image_id = image_data["Id"]
        # Extract from a never-started container. No candidate can write before the gate.
        extraction = self.c["gateway"] + "-inspect-" + release
        run("docker", "create", "--name", extraction, image_id)
        try:
            run("docker", "cp", extraction + ":/app/app.jar", str(package / "web.jar"))
        finally:
            run("docker", "rm", extraction)
        run("docker", "cp", self.c["worker"] + ":/app/app.jar", str(package / "worker.jar"))
        approval = json.loads(Path(self.c["adapters"]).read_text())
        from compatibility import entries
        web_entries = entries(package / "web.jar")
        required = ["QuotationApplication", "common/BackgroundSchedulingConfiguration", "common/WebReleaseConfiguration"]
        for name in required:
            key = "BOOT-INF/classes/com/milano/quotation/" + name + ".class"
            if sha(web_entries.get(key, b"")) != approval["runtime_sha256"].get(key):
                raise RuntimeError("Web-only isolation implementation changed; repeat isolated rehearsal")
        evidence = verify(package / "worker.jar", package / "web.jar", approval["worker_adapters"])
        atomic(package / "compatibility.json", json.dumps(evidence, indent=2))
        env = environment(self.c["worker"])
        env.update(APP_RELEASE_WEB_ONLY="true", SPRING_FLYWAY_ENABLED="false",
                   APP_LOGISTICS_RESUME_ON_START="false", APP_LOGISTICS_RECONCILE_INTERRUPTED="false",
                   APP_LOGISTICS_FILE_CLEANUP_ENABLED="false", APP_SHIMO_WORKER_ENABLED="false",
                   APP_STORAGE_INITIALIZE="false", SERVER_SHUTDOWN="graceful",
                   SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE="900s",
                   JAVA_TOOL_OPTIONS="-Xmx640m -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC")
        name = safe_name(self.c["gateway"] + "-web-" + release)
        env_file = package / "candidate.env"
        atomic(env_file, "\n".join(k + "=" + v for k, v in env.items()) + "\n")
        args = ["docker", "create", "--name", name, "--restart", "unless-stopped",
                "--network", self.c["internal_network"], "--env-file", str(env_file),
                "--memory", "1024m", "--memory-swap", "1024m", "--cpus", "1.5",
                "--security-opt", "no-new-privileges:true", "--log-driver", "local",
                "--log-opt", "max-size=20m", "--log-opt", "max-file=5",
                "--label", "com.milano.quotation.smooth=web",
                "--label", "com.milano.quotation.release=" + release,
                "--health-cmd", "wget -qO- http://127.0.0.1:8088/actuator/health/readiness",
                "--health-interval", "5s", "--health-timeout", "5s", "--health-retries", "30"]
        for mount in inspect(self.c["worker"])["Mounts"]:
            if mount["Destination"] != "/app/purchase-sales" or mount["RW"]:
                raise RuntimeError("Unrecognized worker mount; explicit review required")
            args += ["--mount", f"type=bind,src={mount['Source']},dst={mount['Destination']},readonly"]
        args += [image_id]
        try:
            run(*args)
        finally:
            env_file.unlink(missing_ok=True)
        run("docker", "network", "connect", self.c["edge_network"], name)
        run("docker", "start", name)
        self.event("candidate-started", release=release, container=name, image_id=image_id)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline and not healthy(name):
            if not inspect(name)["State"]["Running"]:
                raise RuntimeError("Candidate stopped; active service is unchanged")
            time.sleep(2)
        if not healthy(name):
            run("docker", "stop", "-t", "10", name)
            raise RuntimeError("Candidate readiness timed out; active service unchanged")
        frontend = self.state["active"]["frontend"]
        if frontend_image:
            frontend = self.prepare_frontend(release, frontend_image)
        target = slot(release, name, frontend)
        self.smoke(target)
        self.state["slots"][release] = target
        self.save()
        self.event("candidate-ready", release=release, compatibility=evidence)

    def prepare_frontend(self, release, image):
        active = inspect(self.state["active"]["frontend"])
        mounts = [m for m in active["Mounts"] if m["Destination"] == "/usr/share/nginx/html/assets"]
        if len(mounts) != 1 or mounts[0]["Type"] != "volume":
            raise RuntimeError("Frontend must already use the retained assets volume")
        image_id = json.loads(run("docker", "image", "inspect", image))[0]["Id"]
        name = safe_name(self.c["gateway"] + "-front-" + release)
        run("docker", "create", "--name", name, "--restart", "unless-stopped",
            "--network", self.c["edge_network"], "--memory", "128m", "--cpus", "0.5",
            "--security-opt", "no-new-privileges:true", "--log-driver", "local",
            "--label", "com.milano.quotation.smooth=frontend", "--label", "com.milano.quotation.release=" + release,
            "--mount", f"type=volume,src={mounts[0]['Name']},dst=/usr/share/nginx/html/assets",
            "--health-cmd", "wget -qO- http://127.0.0.1/", "--health-interval", "5s", image_id)
        manifest = self.root / "releases" / release / "frontend-version.json"
        run("docker", "cp", name + ":/usr/share/nginx/html/app-version.json", str(manifest))
        incoming = json.loads(manifest.read_text())
        current = json.loads(run("docker", "exec", self.state["active"]["frontend"], "cat", "/usr/share/nginx/html/app-version.json"))
        if incoming.get("schemaVersion") != 1 or not current.get("buildId") or incoming.get("buildId") != current["buildId"]:
            run("docker", "rm", name)
            raise RuntimeError("Frontend protocol ID changed: old pages would be forced to refresh; ordinary smooth release refused")
        run("docker", "run", "--rm", "--network", "none", "--entrypoint", "/opt/quotation/retain-assets.sh",
            "--mount", f"type=volume,src={mounts[0]['Name']},dst=/retained", image_id,
            "/opt/quotation/assets", "/retained")
        run("docker", "start", name)
        for _ in range(30):
            if healthy(name):
                return name
            time.sleep(2)
        raise RuntimeError("Candidate frontend unhealthy; active service unchanged")

    def check_capacity(self):
        # Keep at least 25% (and 1 GiB) memory available after the candidate budget.
        if Path("/proc/meminfo").exists():
            info = {line.split(":")[0]: int(line.split()[1]) for line in Path("/proc/meminfo").read_text().splitlines()}
            reserve = max(info["MemTotal"] // 4, 1024 * 1024)
            # A bounded local functional rehearsal shares Docker Desktop with unrelated dev stacks.
            # This exception cannot match the production names, database or public URL.
            if (self.c.get("test_only") and self.c["public_url"] == "http://quotation-smooth-uat-edge"
                    and self.c["worker"] == "quotation-smooth-uat-backend"
                    and environment(self.c["worker"]).get("SPRING_DATASOURCE_URL", "").endswith("/quotation_smooth_test")):
                reserve = 512 * 1024
            if info["MemAvailable"] - 1024 * 1024 < reserve:
                raise RuntimeError("Insufficient memory headroom for an additional web slot")
        live = run("docker", "ps", "-q", "--filter", "label=com.milano.quotation.smooth=web").splitlines()
        if len(live) >= 2:
            raise RuntimeError("Two web slots already running; retire a drained unused slot first")

    def switch(self, target):
        if not self.state["installed"]:
            raise RuntimeError("Install the gateway first")
        if self.state.get("pending"):
            raise RuntimeError("Interrupted release: run recover before another switch")
        self.resume_slot(target)
        self.smoke(target)
        old = self.state["active"]
        if self.marker() != old["release"]:
            raise RuntimeError("Gateway/state mismatch; recover first")
        path = self.gateway_dir / "default.conf"
        old_config = path.read_text()
        if old_config != configuration(old, self.c["worker"]):
            raise RuntimeError("Gateway configuration changed outside release manager")
        draining = self.worker_pids()
        self.state["pending"] = {"old": old, "target": target, "workers": draining}
        self.save()
        self.event("switch-intent", old=old["release"], target=target["release"])
        try:
            atomic(path, configuration(target, self.c["worker"]))
            os.chmod(path, 0o644)
            run("docker", "exec", self.c["gateway"], "nginx", "-t")
            run("docker", "exec", self.c["gateway"], "nginx", "-s", "reload")
            self.wait_marker(target["release"])
            # Observe multiple successful rounds. Failed verification restores old routing.
            for _ in range(3):
                self.public_smoke(target["release"])
                self.smoke(target)
                time.sleep(1)
        except BaseException:
            atomic(path, old_config); os.chmod(path, 0o644)
            run("docker", "exec", self.c["gateway"], "nginx", "-t")
            run("docker", "exec", self.c["gateway"], "nginx", "-s", "reload")
            self.wait_marker(old["release"])
            self.state.pop("pending", None); self.save()
            self.event("switch-restored", active=old["release"], candidates_retained=True)
            raise
        self.state.update(active=target, previous=old, draining_workers=draining)
        self.state.pop("pending", None)
        self.save()
        self.event("switch-completed", active=target["release"], previous=old["release"], old_instances_retained=True)

    def recover(self):
        if self.state.get("pending_install"):
            path = Path(self.c["edge_file"])
            pending_install = self.state["pending_install"]
            if sha(path.read_bytes()) not in pending_install.values():
                raise RuntimeError("Edge changed after interrupted installation; manual reconciliation needed")
            old = (self.root / "edge-before.conf").read_text()
            with open(path, "w") as file:
                file.write(old); file.flush(); os.fsync(file.fileno())
            run("docker", "exec", self.c["edge"], "nginx", "-t")
            run("docker", "exec", self.c["edge"], "nginx", "-s", "reload")
            self.state.pop("pending_install"); self.save()
            self.event("interrupted-install-restored")
        pending = self.state.get("pending")
        if not pending:
            self.event("no-pending-switch")
            return
        old = pending["old"]
        self.smoke(old)
        path = self.gateway_dir / "default.conf"
        atomic(path, configuration(old, self.c["worker"])); os.chmod(path, 0o644)
        run("docker", "exec", self.c["gateway"], "nginx", "-t")
        run("docker", "exec", self.c["gateway"], "nginx", "-s", "reload")
        self.wait_marker(old["release"])
        self.public_smoke(old["release"])
        self.state["active"] = old
        self.state.pop("pending")
        self.save()
        self.event("interrupted-switch-recovered", active=old["release"])

    def retire(self, release):
        target = self.state["slots"][release]
        protected = {self.state["active"]["release"]}
        if release in protected or self.state.get("pending"):
            raise RuntimeError("Cannot retire active or pending slot")
        self.smoke(self.state["active"])
        listing = run("docker", "exec", self.c["gateway"], "ps", "-o", "pid,args")
        if "worker process is shutting down" in listing:
            raise RuntimeError("Old gateway workers still have in-flight requests; leave slot running")
        for role in ("backend", "frontend"):
            name = target[role]
            if any(s[role] == name for key,s in self.state["slots"].items() if key != release):
                continue
            details = inspect(name)
            if details["Id"] != target[role + "_id"] or details["Config"].get("Labels", {}).get("com.milano.quotation.smooth") not in ("web", "frontend"):
                continue
            run("docker", "stop", "-t", "900", name, timeout=920)
        self.event("unused-slot-stopped", release=release, artifacts_retained=True)

    def resume_slot(self, target):
        """A retired rollback slot is started and warmed before any route change."""
        for role in ("backend", "frontend"):
            details = inspect(target[role])
            if details["Id"] != target[role + "_id"]:
                raise RuntimeError("Rollback container identity changed")
            if not details["State"]["Running"]:
                if details["Config"].get("Labels", {}).get("com.milano.quotation.smooth") not in ("web", "frontend"):
                    raise RuntimeError("Legacy worker/frontend is down; never restart it automatically")
                run("docker", "start", target[role])
                deadline = time.monotonic() + 180
                while time.monotonic() < deadline and not healthy(target[role]):
                    time.sleep(2)
                if not healthy(target[role]):
                    raise RuntimeError("Rollback slot failed readiness; current routing unchanged")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("action", choices=["bootstrap", "install", "prepare", "switch", "rollback", "recover", "retire", "status"])
    parser.add_argument("--release")
    parser.add_argument("--image")
    parser.add_argument("--frontend-image")
    args = parser.parse_args()
    manager = Release(args.config)
    with manager.lock():
        if args.action == "bootstrap": manager.bootstrap()
        elif args.action == "install": manager.install()
        elif args.action == "prepare":
            if not args.release or not args.image: parser.error("prepare requires --release and --image")
            manager.prepare(args.release, args.image, args.frontend_image)
        elif args.action == "switch": manager.switch(manager.state["slots"][args.release])
        elif args.action == "rollback":
            if not manager.state.get("previous"): raise RuntimeError("No rollback slot")
            manager.switch(manager.state["previous"])
        elif args.action == "recover": manager.recover()
        elif args.action == "retire": manager.retire(args.release)
        else: print(json.dumps({"state": manager.state, "gateway_release": manager.marker()}, indent=2))


if __name__ == "__main__":
    main()

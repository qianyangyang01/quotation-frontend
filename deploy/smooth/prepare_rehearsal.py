"""Build and start a localhost-only rehearsal with synthetic data and dedicated volumes."""
from pathlib import Path
import json
import shutil
import subprocess
import sys
import time
from compatibility import entries, sha, verify

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "outputs" / "smooth-rehearsal"
ROOT = "/var/lib/docker/volumes/quotation-smooth-control/_data"
PREFIX = "quotation-smooth-uat"


def command(*args, input=None):
    result = subprocess.run(args, input=input, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors="replace")[-2500:])
    return result.stdout.decode().strip()


def start(name, image, extra, env=None):
    args = ["docker", "run", "-d", "--name", PREFIX + "-" + name,
            "--network", PREFIX + "-internal", "--network-alias", "quotation-" + name,
            "--label", "com.milano.quotation.rehearsal=20261121", "--memory", "1g", "--cpus", "1.5"]
    for k,v in (env or {}).items(): args += ["-e", k + "=" + v]
    return command(*args, *extra, image)


def tool(*args):
    return command("docker", "run", "--rm", "--network", PREFIX + "-edge",
        "-v", "/var/run/docker.sock:/var/run/docker.sock",
        "-v", "quotation-smooth-control:" + ROOT,
        "-v", str(REPO / "deploy" / "smooth") + ":/tool:ro",
        "quotation-smooth-tool:20261121", "python", *args)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    baseline = Path(sys.argv[1])
    candidate = REPO / "backend/target/quotation-backend-0.1.0.jar"
    old, new = entries(baseline), entries(candidate)
    # These reviewed changes only suppress startup work and queue dispatch in web mode.
    adapter_names = ["logistics/LogisticsImportService", "logistics/LogisticsImportService$1",
                     "logistics/LogisticsPriceHistoryWorker", "logistics/LogisticsPriceHistoryWorker$1"]
    adapters = [{"path": "BOOT-INF/classes/com/milano/quotation/" + n + ".class",
                 "worker_sha256": sha(old["BOOT-INF/classes/com/milano/quotation/" + n + ".class"]),
                 "web_sha256": sha(new["BOOT-INF/classes/com/milano/quotation/" + n + ".class"])} for n in adapter_names]
    runtime = ["QuotationApplication", "common/BackgroundSchedulingConfiguration", "common/WebReleaseConfiguration"]
    approval = {"worker_adapters": adapters, "runtime_sha256": {
        "BOOT-INF/classes/com/milano/quotation/" + n + ".class": sha(new["BOOT-INF/classes/com/milano/quotation/" + n + ".class"]) for n in runtime}}
    print(json.dumps(verify(baseline, candidate, adapters)), flush=True)
    (OUT / "approved-runtime.json").write_text(json.dumps(approval, indent=2))
    for label,jar in [("worker",baseline), ("web",candidate)]:
        stage=OUT/label; stage.mkdir(exist_ok=True)
        shutil.copyfile(jar, stage / "app.jar")
        (stage / "Dockerfile").write_text("FROM quotation-backend:quotation-2026.10.24-01\nCOPY --chown=quotation:quotation app.jar /app/app.jar\n")
        print(command("docker", "build", "-t", "quotation-smooth-"+label+":20261121", str(stage))[-300:], flush=True)
    tool_stage=OUT/"tool";tool_stage.mkdir(exist_ok=True)
    (tool_stage/"Dockerfile").write_text("FROM docker:29-cli AS cli\nFROM python:3.12-slim\nCOPY --from=cli /usr/local/bin/docker /usr/local/bin/docker\n")
    command("docker", "build", "-t", "quotation-smooth-tool:20261121", str(tool_stage))
    for index,suffix in enumerate(["internal", "edge"]):
        existing = command("docker", "network", "ls", "--format", "{{.Name}}").splitlines()
        if PREFIX+"-"+suffix not in existing:
            command("docker", "network", "create", *(["--internal"] if suffix == "internal" else []), "--subnet", f"10.245.{210+index}.0/24", PREFIX+"-"+suffix)
    command("docker", "volume", "create", "quotation-smooth-control")
    init = "from pathlib import Path; p=Path('"+ROOT+"');(p/'edge').mkdir(exist_ok=True);(p/'smooth-release').mkdir(exist_ok=True)"
    tool("-c", init)
    for volume in ["db", "redis", "minio", "assets"]:
        command("docker", "volume", "create", PREFIX+"-"+volume)
    password="isolated-smooth-rehearsal-only-20261121"
    start("postgres", "postgres:16.4-alpine", ["-v",PREFIX+"-db:/var/lib/postgresql/data"],
          dict(POSTGRES_DB="quotation_smooth_test", POSTGRES_USER="quotation_app", POSTGRES_PASSWORD=password))
    # The dedicated Redis is reachable only on the private rehearsal network.
    start("redis", "redis:7.4-alpine", ["-v",PREFIX+"-redis:/data"])
    args=["docker","run","-d","--name",PREFIX+"-minio","--network",PREFIX+"-internal","--network-alias","quotation-minio",
          "--memory","512m","--cpus","1","-v",PREFIX+"-minio:/data","-e","MINIO_ROOT_USER=SMOOTHQA","-e","MINIO_ROOT_PASSWORD="+password,
          "minio/minio:RELEASE.2025-07-23T15-54-02Z","server","/data"]
    command(*args)
    env=dict(SPRING_DATASOURCE_URL="jdbc:postgresql://quotation-postgres:5432/quotation_smooth_test",
        SPRING_DATASOURCE_USERNAME="quotation_app",SPRING_DATASOURCE_PASSWORD=password,
        SPRING_DATA_REDIS_HOST="quotation-redis",SPRING_DATA_REDIS_PASSWORD="",
        APP_STORAGE_ENDPOINT="http://quotation-minio:9000",APP_STORAGE_ACCESS_KEY="SMOOTHQA",APP_STORAGE_SECRET_KEY=password,
        APP_STORAGE_BUCKET="quotation-smooth-test",APP_BOOTSTRAP_ADMIN_ACCOUNT="SMOOTHQA",APP_BOOTSTRAP_ADMIN_NAME="隔离演练",
        APP_BOOTSTRAP_ADMIN_PASSWORD=password,APP_PUBLIC_BASE_URL="http://127.0.0.1:18191",APP_CORS_ALLOWED_ORIGIN="http://127.0.0.1:18191",
        APP_COOKIE_SECURE="false",APP_SHIMO_WORKER_ENABLED="false",APP_LOGISTICS_RECONCILE_INTERRUPTED="true",
        JAVA_TOOL_OPTIONS="-Xmx640m -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC")
    start("backend", "quotation-smooth-worker:20261121",
          ["--health-cmd","wget -qO- http://127.0.0.1:8088/actuator/health/readiness","--health-interval","5s","--health-retries","30"],env)
    command("docker","network","connect","--alias","quotation-backend",PREFIX+"-edge",PREFIX+"-backend")
    # Use an actual retained-assets frontend, never a fake success page.
    start("frontend", "quotation-frontend:quotation-2026.11.17-02-quantity-compare",
          ["-v",PREFIX+"-assets:/usr/share/nginx/html/assets","--health-cmd","wget -qO- http://127.0.0.1/","--health-interval","5s"])
    command("docker","network","connect",PREFIX+"-edge",PREFIX+"-frontend")
    edge='''server { listen 80; client_max_body_size 520m;
location /api/ { proxy_pass http://quotation-smooth-uat-backend:8088; proxy_request_buffering off; proxy_read_timeout 300s; proxy_set_header Host $host; }
location / { proxy_pass http://quotation-smooth-uat-frontend:80; proxy_set_header Host $host; }
}
'''
    cfg=dict(test_only=True,root=ROOT+"/smooth-release",gateway=PREFIX+"-gateway",worker=PREFIX+"-backend",backend=PREFIX+"-backend",frontend=PREFIX+"-frontend",
        edge=PREFIX+"-edge",edge_network=PREFIX+"-edge",internal_network=PREFIX+"-internal",nginx_image="nginx:1.27-alpine",
        edge_file=ROOT+"/edge/default.conf",public_url="http://"+PREFIX+"-edge",adapters=ROOT+"/approved-runtime.json",
        edge_replacements={"http://quotation-smooth-uat-backend:8088":"http://"+PREFIX+"-gateway:80",
                           "http://quotation-smooth-uat-frontend:80":"http://"+PREFIX+"-gateway:80"})
    payload={"edge/default.conf":edge,"config.json":json.dumps(cfg),"approved-runtime.json":json.dumps(approval)}
    tool("-c", "import json;from pathlib import Path;p=Path('"+ROOT+"');d=json.loads("+repr(json.dumps(payload))+");[(p.joinpath(k).write_text(v)) for k,v in d.items()]")
    command("docker","run","-d","--name",PREFIX+"-edge","--network",PREFIX+"-edge","--memory","128m","--cpus","0.5",
        "--mount","type=bind,src="+ROOT+"/edge,dst=/etc/nginx/conf.d,readonly","-p","127.0.0.1:18191:80","nginx:1.27-alpine")
    for _ in range(90):
        health=command("docker","inspect","--format","{{.State.Health.Status}}",PREFIX+"-backend")
        if health=="healthy":break
        time.sleep(2)
    else:raise RuntimeError("Isolated backend did not become ready")
    print(tool("/tool/release.py","--config",ROOT+"/config.json","bootstrap"),flush=True)
    print(tool("/tool/release.py","--config",ROOT+"/config.json","install"),flush=True)
    print(tool("/tool/release.py","--config",ROOT+"/config.json","prepare","--release","web-v1","--image","quotation-smooth-web:20261121"),flush=True)
    print("Isolated rehearsal is ready on http://127.0.0.1:18191",flush=True)


if __name__=="__main__":main()

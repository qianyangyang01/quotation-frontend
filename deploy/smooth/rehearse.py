"""Real HTTP/database rehearsal. Refuses any target other than the dedicated local stack."""
import base64
import concurrent.futures
import copy
import hashlib
import http.client
import http.cookiejar
import io
import json
import os
from pathlib import Path
import re
import threading
import time
import urllib.request
import urllib.error
import uuid
import zipfile
from xml.sax.saxutils import escape
from prepare_rehearsal import command, tool, ROOT, PREFIX, REPO, OUT

BASE = os.environ.get("SMOOTH_BASE", "http://127.0.0.1:18191")
assert BASE in ("http://127.0.0.1:18191", "http://quotation-smooth-uat-edge")
COOKIE = http.cookiejar.CookieJar()
CLIENT = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(COOKIE))
CSRF = {}
RUN = uuid.uuid4().hex[:10]
RESULTS = {"run": RUN, "requests": [], "errors": [], "slow": [], "switches": []}
LOCK = threading.Lock()


def sql(query):
    return command("docker", "exec", "-i", PREFIX+"-postgres", "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                   "-U", "quotation_app", "-d", "quotation_smooth_test", input=query.encode())


def request(path, data=None, *, key=None, raw=None, content_type=None):
    body = raw if raw is not None else (json.dumps(data,ensure_ascii=False).encode() if data is not None else None)
    headers={}
    if body is not None:
        headers.update({CSRF.get("headerName","X-XSRF-TOKEN"):CSRF.get("token",""), "Content-Type":content_type or "application/json"})
    if key: headers["Idempotency-Key"] = key
    req = urllib.request.Request(BASE + path, data=body, headers=headers)
    began=time.monotonic()
    try:
        with CLIENT.open(req,timeout=60) as response:
            payload=response.read()
            with LOCK: RESULTS["requests"].append({"path":path,"status":response.status,"seconds":round(time.monotonic()-began,3)})
            return json.loads(payload)["data"] if response.headers.get("Content-Type","").startswith("application/json") else payload
    except urllib.error.HTTPError as error:
        raise AssertionError(f"{path} returned {error.code}: {error.read().decode()}") from None


def manager(action, *args):
    if BASE == "http://quotation-smooth-uat-edge":
        result=command("python",str(REPO/"deploy/smooth/release.py"),"--config",ROOT+"/config.json",action,*args)
    else:
        result=tool("/tool/release.py","--config",ROOT+"/config.json",action,*args)
    RESULTS["switches"].append({"action":action,"output":result})
    return result


def seed():
    assert sql("select current_database()") == "quotation_smooth_test"
    p,c,v=[str(uuid.uuid4()) for _ in range(3)]
    existing=sql("select provider_id::text||','||id::text||','||current_version_id::text from logistics_channel where rule_id=9910")
    if existing:p,c,v=existing.split(',')
    def literal(obj):return "'"+json.dumps(obj,ensure_ascii=False).replace("'","''")+"'::jsonb"
    provider=dict(id=p,code="SMOOTH"+RUN,name="燕文",enabled=True)
    channel=dict(id=c,providerId=p,name="平滑发布测试渠道",code="TEST",logisticsAttribute="普货",enabled=True)
    version=dict(id=v,channelId=c,versionNumber=1,status="published",rows=[dict(areaName="美国",countryCode="US",weightFromKg=0,
        weightToKg=30,pricePerKg=50,registrationFee=5,allowedMarks="",prohibitedMarks="")],issues=[],diffRows=[])
    statements=["BEGIN;", "update app_user set must_change_password=false where account='SMOOTHQA';",
        "update logistics_company_state set enabled=false,paused=false;",
        f"insert into logistics_provider(id,code,payload,version,created_at,updated_at) values('{p}','SMOOTH{RUN}',{literal(provider)},0,now(),now());",
        f"insert into logistics_channel(id,provider_id,code,rule_id,payload,version,created_at,updated_at) values('{c}','{p}','TEST',9910,{literal(channel)},0,now(),now());",
        f"insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at,published_at) values('{v}','{c}',1,'published','test',{literal(version)},now(),now());",
        f"update logistics_channel set current_version_id='{v}' where id='{c}';",
        f"insert into logistics_billing_acceptance select gen_random_uuid(),id,md5((payload->'rows')::text),'legacy','legacy','{{}}','SMOOTHQA',now() from logistics_version where id='{v}';"]
    if existing:statements=statements[:3]
    settings={"country-classification":[dict(country="美国",code="US",enabled=True)],
        "channel-policies":[dict(enabled=True,category="普货",countryRules=[dict(country="美国",allowedChannels=["9910::燕文::TEST"])])],
        "customer-grades":[dict(grade="S",coefficient=1.3,enabled=True)],"exchange-rate":dict(usdCny=6,updatedAt="test"),
        "tax-settings":dict(updatedAt="test",countries=[],providers=[]),
        "surcharge-settings":dict(countries=[dict(country="美国",selected=True,enabled=True,fixedFeeUsd=.2,
            providers=[dict(provider="燕文",selected=True,mode="taxable")])]),"customer-operation-fees":dict(customers=[])}
    for key,val in settings.items():
        statements.append(f"insert into finance_setting(setting_key,payload,version,updated_at) values('{key}',{literal(val)},1,now()) on conflict(setting_key) do update set payload=excluded.payload,version=1;")
    statements.append("COMMIT;");sql("\n".join(statements))
    body=json.loads((REPO/"backend/src/test/resources/manual-quotation/shipping-only.json").read_text(encoding="utf-8"))
    body.update(customerName="隔离平滑发布演练-"+RUN,logisticsSyncScope="selected",salespersonAccount="SMOOTHQA")
    body["quoteOptions"][0].update(logisticsChannelId=c,logisticsVersionId=v)
    return body


def multipart(data, filename="sample.png", field="file", mime="image/png"):
    boundary="smooth"+uuid.uuid4().hex
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{filename}"\r\nContent-Type: {mime}\r\n\r\n').encode()+data+f"\r\n--{boundary}--\r\n".encode()
    return body,"multipart/form-data; boundary="+boundary


def slow_request(path, body, content_type, key, started):
    endpoint=urllib.parse.urlsplit(BASE)
    connection=http.client.HTTPConnection(endpoint.hostname,endpoint.port or 80,timeout=60)
    connection.putrequest("POST",path)
    connection.putheader("Content-Length",str(len(body)))
    connection.putheader("Content-Type",content_type)
    connection.putheader("Cookie","; ".join(c.name+"="+c.value for c in COOKIE))
    connection.putheader(CSRF["headerName"],CSRF["token"])
    connection.putheader("Idempotency-Key",key)
    connection.endheaders()
    began=time.monotonic()
    step=max(1,len(body)//100)
    for index in range(0,len(body),step):
        connection.send(body[index:index+step]);started.set();time.sleep(.12)
    response=connection.getresponse();result=response.read();connection.close()
    assert response.status==200,(path,response.status,result)
    outcome=dict(path=path,status=response.status,seconds=round(time.monotonic()-began,2),data=json.loads(result)["data"])
    RESULTS["slow"].append(outcome)
    return outcome["data"]


def workbook():
    source=(REPO/"backend/src/main/java/com/milano/quotation/logistics/LogisticsWorkbookService.java").read_text(encoding="utf-8")
    headers=re.findall(r'"([^\"]+)"',source.split("HEADERS = List.of(",1)[1].split(");",1)[0])+["物流商","渠道名称","产品代码","物流属性"]
    rows=[headers]
    for n in range(20):
        values={"区域名称":"美国","国家简码":"US","起始重量":n,"截止重量":n+1,"运费单价":50,"挂号费":5,
                "物流商":"燕文","渠道名称":"隔离导入演练","产品代码":"SMOOTH","物流属性":"普货"}
        rows.append([values.get(h,"") for h in headers])
    def col(n):
        result=""
        while n: n,r=divmod(n-1,26);result=chr(65+r)+result
        return result
    xml='<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
    for rowno,row in enumerate(rows,1):
        xml+=f'<row r="{rowno}">'+''.join(f'<c r="{col(i)}{rowno}" t="inlineStr"><is><t>{escape(str(value))}</t></is></c>' for i,value in enumerate(row,1))+'</row>'
    xml+='</sheetData></worksheet>'
    out=io.BytesIO()
    with zipfile.ZipFile(out,"w",zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml",'<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
        archive.writestr("_rels/.rels",'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        archive.writestr("xl/workbook.xml",'<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="演练" sheetId="1" r:id="rId1"/></sheets></workbook>')
        archive.writestr("xl/_rels/workbook.xml.rels",'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
        archive.writestr("xl/worksheets/sheet1.xml",xml)
    return out.getvalue()


def main():
    global CSRF
    body=seed()
    CSRF=request("/api/v1/auth/csrf")
    request("/api/v1/auth/login",dict(account="SMOOTHQA",password="isolated-smooth-rehearsal-only-20261121"))
    CSRF=request("/api/v1/auth/csrf")
    old=request("/api/v1/quotations",body,key="historical-"+RUN)
    old_payload=sql(f"select md5(payload::text) from quotation_record where id='{old['id']}'")
    dataset=request("/api/v1/logistics/rebuild/datasets",dict(name="隔离演练"+RUN),key="dataset-"+RUN)["id"]
    baseline=command("docker","inspect",PREFIX+"-backend","--format","{{.Id}} {{.State.StartedAt}} {{.RestartCount}}")
    stop=threading.Event()
    def reads():
        while not stop.is_set():
            try:
                for path in ["/api/v1/auth/me","/api/v1/logistics/company-channels","/api/v1/quotations/"+old["id"]]:request(path)
            except Exception as error:
                RESULTS["errors"].append(str(error))
            time.sleep(.05)
    readers=[threading.Thread(target=reads) for _ in range(3)]
    for thread in readers:thread.start()
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            save_started=threading.Event();upload_started=threading.Event()
            saving=pool.submit(slow_request,"/api/v1/quotations",json.dumps(body).encode(),"application/json","slow-save-"+RUN,save_started)
            png=base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aJ1sAAAAASUVORK5CYII=")
            payload,kind=multipart(png)
            uploading=pool.submit(slow_request,"/api/v1/quotation-sheet-photos",payload,kind,"photo-"+RUN,upload_started)
            assert save_started.wait(10) and upload_started.wait(10)
            time.sleep(1)
            manager("switch","--release","web-v1")
            saved=saving.result(timeout=60);uploaded=uploading.result(timeout=60)
            replay=request("/api/v1/quotations",body,key="slow-save-"+RUN)
            assert replay["id"]==saved["id"],"Idempotency failed across switch"
            assert request("/api/v1/assets/"+uploaded["assetId"])==png,"Uploaded bytes changed"
            # A web-slot upload must enter the shared durable queue, then be processed by the old worker.
            excel=workbook();payload,kind=multipart(excel,"smooth.xlsx","files","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            batch=request(f"/api/v1/logistics/rebuild/datasets/{dataset}/imports",raw=payload,content_type=kind,key="import-"+RUN)
            RESULTS["import_accepted"]=batch
            manager("rollback")
            for _ in range(60):
                batch=request("/api/v1/logistics/rebuild/imports/"+batch["id"])
                if batch["status"] not in ("queued","processing"):break
                time.sleep(1)
            RESULTS["import_final"]=batch
            assert batch["status"] not in ("queued","processing","failed","interrupted"),batch
            manager("switch","--release","web-v1")
            request("/api/v1/quotations",body,key="after-switch-"+RUN)
            assert request("/api/v1/auth/me")["account"]=="SMOOTHQA"
    finally:
        stop.set()
        for thread in readers:thread.join(65)
        OUT.mkdir(exist_ok=True,parents=True)
        (OUT/("rehearsal-"+RUN+".json")).write_text(json.dumps(RESULTS,ensure_ascii=False,indent=2),encoding="utf-8")
    assert not RESULTS["errors"],RESULTS["errors"]
    assert sql(f"select md5(payload::text) from quotation_record where id='{old['id']}'")==old_payload
    assert command("docker","inspect",PREFIX+"-backend","--format","{{.Id}} {{.State.StartedAt}} {{.RestartCount}}")==baseline
    RESULTS["historical_snapshot_unchanged"]=True
    RESULTS["worker_never_restarted"]=True
    RESULTS["quote_count"]=sql("select count(*) from quotation_record")
    RESULTS["passed"]=True
    (OUT/("rehearsal-"+RUN+".json")).write_text(json.dumps(RESULTS,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({k:v for k,v in RESULTS.items() if k not in ("requests","import_accepted","import_final","switches","slow")},ensure_ascii=False))
    print("successful requests:",len(RESULTS["requests"]))


if __name__=="__main__":main()

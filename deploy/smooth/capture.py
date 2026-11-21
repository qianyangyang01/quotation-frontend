"""Read-only production evidence. Emits identifiers/hashes, never prices or credentials."""
import json
import subprocess
from pathlib import Path
from release import run


def main():
    query="""BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout='30s';
select json_build_object(
 'at',clock_timestamp(),
 'quotes',(select json_object_agg(id,json_build_object('hash',md5(row(status,payload,version,lifecycle_state)::text),'updated_at',updated_at)) from quotation_record),
 'reviews',(select json_object_agg(id,md5(row(status,claimant_account,state,version)::text)) from quotation_review),
 'batches',(select json_object_agg(id,md5(payload::text)) from batch_single_quotation),
 'logistics',(select md5(string_agg(row(id,channel_id,status,quote_rows,rows_fingerprint)::text,',' order by id)) from logistics_version where status='published'),
 'audit_max',(select max(created_at) from audit_log),
 'imports',(select json_object_agg(status,n) from (select status,count(*) n from logistics_import_batch group by status)t));
COMMIT;
"""
    raw=run("docker","exec","-i","quotation-prod-quotation-postgres-1","psql","-X","-qAt","-v","ON_ERROR_STOP=1",
            "-U","quotation_app","-d","quotation_prod",input=query.encode())
    snapshot=json.loads(raw)
    names=run("docker","ps","--format","{{.Names}}").splitlines()
    snapshot["containers"]={}
    for name in names:
        data=json.loads(run("docker","inspect",name))[0]
        snapshot["containers"][name]={"id":data["Id"],"image":data["Config"]["Image"],"started":data["State"]["StartedAt"],
            "restarts":data["RestartCount"],"health":data["State"].get("Health",{}).get("Status",data["State"]["Status"])}
    snapshot["current_release"]=str(Path("/srv/ahmln-data/quotation-app/current").resolve())
    snapshot["public"]={}
    for url in ("https://vip.ahmln.com/","https://vip.ahmln.com/api/v1/auth/csrf","https://ahmln.com/"):
        snapshot["public"][url]=run("curl","-sS","-o","/dev/null","-w","%{http_code}","--max-time","20",url)
    print(json.dumps(snapshot,ensure_ascii=False))


if __name__=="__main__":main()

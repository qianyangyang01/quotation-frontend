# Quotation backend resource isolation

Production runs quotation, training and two quotation UAT environments on a shared 7.1 GiB host. The quotation backend previously had no container memory or CPU limit, while Java used MaxRAMPercentage=70 against the host capacity.

After release quotation-2026.09.07-02, both sites and SSH became unresponsive. The release log recorded five healthy quotation containers at 03:10 UTC. SSH became available again around 03:22 UTC; the quotation backend had restarted once, host load averages were approximately 240/198/104, and available memory was 3.3 GiB. Subsequent samples showed 97–98% idle CPU and falling load. The VNC console contained multiple historical Java OOM kills, but the kernel log for this exact outage could not be read with the deploy account, so the precise termination cause remains unconfirmed.

The startup log recorded automatic recovery of an old import batch (3dfb6ecc-ffd7-49f6-ae86-c83bf7da8b06). It parsed the first workbook before the monitoring gap. Automatic recovery is temporarily disabled in the production environment with QUOTATION_LOGISTICS_RESUME_ON_START=false until that workload is reviewed. This does not discard upload chunks or change formal prices.

The quotation backend now has a 1536 MiB memory limit, 2 GiB total memory plus swap limit, and 2 CPU limit. Java's existing 70% heap setting will use the container limit after recreation. These limits prevent one quotation process from consuming the host memory allocation.

This is a deployment configuration patch over application release quotation-2026.09.07-02; application image SHA remains 4d0f49095e70de402ea5ca54d7b8a2c7bfb3a246. Record the separate configuration commit in the release manifest. No training containers, databases, images, or configuration are changed.

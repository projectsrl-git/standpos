# Demo deployment on Oracle Cloud Always Free

Step-by-step guide to run the StandPOS demo on an Oracle Cloud Infrastructure (OCI) **Always Free Ampere A1** VM (Arm64). The demo uses the same Docker Compose stack as production, built for `arm64`. It is a demo: SumUp sandbox and Virtual Solo only (from Phase 2), never real merchant keys or customer money. Production stays in the ISO 27001 datacenter.

## 1. Account and home region

1. Sign up at Oracle Cloud. The **home region is chosen once** and cannot be changed: pick an EU region (for example *Italy Northwest (Milan)* or *Germany Central (Frankfurt)*). Always Free compute and block storage exist only in the home region.
2. Optional but recommended: upgrade the account to **Pay As You Go** and create a **budget alert** (for example 1 €). Always Free resources stay free, and see the idle-reclamation note below.

## 2. Network

1. *Networking → Virtual Cloud Networks → Start VCN Wizard → Create VCN with Internet Connectivity*.
2. In the public subnet's **security list**, add two ingress rules: source `0.0.0.0/0`, TCP, destination ports **80** and **443**. SSH (22) is already open; restrict its source to your IP if you can.

## 3. Virtual machine

1. *Compute → Instances → Create instance*.
2. Image: **Ubuntu 24.04** (aarch64) or **Oracle Linux 9** (aarch64).
3. Shape: **VM.Standard.A1.Flex**, 2 OCPU and 12 GB RAM are plenty (Always Free allows up to 4 OCPU and 24 GB in total).
4. Public subnet of the VCN above, assign a public IPv4 address, add your SSH public key.
5. Boot volume: the default 50 GB is enough (Always Free includes 200 GB of block storage in total).

## 4. VM firewall

OCI images ship a host firewall that blocks 80/443 even when the security list allows them.

Ubuntu (iptables rules saved by `netfilter-persistent`):

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

Oracle Linux (firewalld):

```bash
sudo firewall-cmd --permanent --add-service=http --add-service=https
sudo firewall-cmd --reload
```

## 5. DNS

Create an `A` record (for example `demo.standpos.example`) pointing to the VM's public IP. Caddy needs it to obtain a Let's Encrypt certificate, and SumUp needs a public HTTPS host to deliver payment results. Check with `dig +short demo.standpos.example` before going on.

## 6. Docker

Ubuntu 24.04 (arm64):

```bash
sudo apt-get update && sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=arm64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update && sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"   # log out and back in
```

Oracle Linux 9 (aarch64):

```bash
sudo dnf install -y dnf-plugins-core git
sudo dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"   # log out and back in
```

## 7. Configure and start

```bash
git clone https://github.com/projectsrl-git/standpos.git
cd standpos
cp deploy/.env.example deploy/.env
```

Edit `deploy/.env`:

- `STANDPOS_DOMAIN=demo.standpos.example`
- `STANDPOS_PROFILE=demo`
- strong, different values for `POSTGRES_PASSWORD`, `STANDPOS_DB_OWNER_PASSWORD`, `STANDPOS_DB_APP_PASSWORD` (for example `openssl rand -base64 24`)
- the `STANDPOS_BOOTSTRAP_*` values for the demo organisation and its owner

Then:

```bash
docker compose -f deploy/docker-compose.yml up -d --build --wait
docker compose -f deploy/docker-compose.yml run --rm backend --bootstrap
```

The images are built on the VM for `arm64` (a few minutes the first time). Open `https://demo.standpos.example`, log in as the owner, and follow the flow in the README.

Update to a new version:

```bash
git pull
docker compose -f deploy/docker-compose.yml up -d --build --wait
```

Flyway migrates the database on backend start.

## 8. Backups to Object Storage

The `backup` service writes a `pg_dump` every night at 02:30 UTC to `deploy/backups/` and keeps the newest 30. Copy them off the VM so a lost VM can be rebuilt from Compose and the last dump:

1. *Storage → Buckets → Create bucket* `standpos-demo-backups` (Standard tier; Always Free includes 20 GB of Object Storage). Add a lifecycle rule deleting objects older than 30 days.
2. Install the OCI CLI on the VM and configure an API key for a user allowed to write to the bucket (`oci setup config`).
3. Add a cron job (`crontab -e`) that uploads new dumps after the nightly run:

   ```cron
   15 3 * * * oci os object bulk-upload --bucket-name standpos-demo-backups --src-dir /home/ubuntu/standpos/deploy/backups --no-overwrite >> /home/ubuntu/standpos-backup-upload.log 2>&1
   ```

A one-off dump: `docker compose -f deploy/docker-compose.yml run --rm backup --now`.

Restore on a fresh stack:

```bash
docker compose -f deploy/docker-compose.yml up -d postgres
docker compose -f deploy/docker-compose.yml cp standpos-<timestamp>.dump postgres:/tmp/restore.dump
docker compose -f deploy/docker-compose.yml exec postgres pg_restore -U postgres -d standpos --clean --if-exists /tmp/restore.dump
docker compose -f deploy/docker-compose.yml up -d --wait
```

## 9. Idle reclamation

Oracle may reclaim **idle Always Free instances**: if over 7 days CPU, network and memory use all stay very low, the instance can be stopped. A demo that waits between sessions looks exactly like that. Upgrading the account to Pay As You Go (step 1), with a budget alert, keeps the Always Free resources free and is the usual way to avoid reclamation. Confirm against Oracle's current terms before relying on the demo: <https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm>.

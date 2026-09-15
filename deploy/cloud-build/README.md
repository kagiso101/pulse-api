# Automated deploys (Cloud Build)

Same pipeline shape as bookr-api. Nothing in this folder is active until a trigger is imported.

## One-time setup (Kagiso, browser + gcloud)

1. **Run `deploy/gcp/bootstrap.sh` first** — it creates the runtime service account, secrets,
   the `pulse` database, the Cloud Scheduler jobs and the two Cloud Run services with their
   env vars / secrets / Cloud SQL binding. The pipeline below only ever changes the image.

2. **Connect the repo.** Cloud Console → Cloud Build → Repositories → *Connect repository*
   → GitHub (Cloud Build GitHub App) → `kagiso101/pulse-api`, region `africa-south1`.
   This is the only step that needs a browser.

3. **Grant the Cloud Build service account** (`898880840502@cloudbuild.gserviceaccount.com`)
   — roles/run.admin, roles/artifactregistry.writer and roles/logging.logWriter are already
   granted for bookr-api; add actAs on the new runtime SAs:

   ```
   PROJECT=bookings-prod-503907
   CB=898880840502@cloudbuild.gserviceaccount.com
   gcloud iam service-accounts add-iam-policy-binding pulse-api-run@$PROJECT.iam.gserviceaccount.com \
     --member=serviceAccount:$CB --role=roles/iam.serviceAccountUser
   ```

4. **Import the SIT trigger:**

   ```
   gcloud builds triggers import --region=africa-south1 --source=deploy/cloud-build/trigger-sit.yaml
   ```

5. **Prove it:** push a trivial commit to `development`, watch Cloud Build → History, then
   `gcloud run services describe pulse-api-sit --region africa-south1 --format="value(status.latestReadyRevisionName)"`.

6. **Production trigger** (`trigger-prod.yaml`) once SIT has run a full scheduler cycle.

## What the pipeline deliberately does not do

- It does not touch environment variables, secrets, the service account, CPU/memory,
  `--add-cloudsql-instances` or `--max-instances`. Change them with
  `gcloud run services update … --update-env-vars / --update-secrets` (never `--set-*`).
- It does not run the test suite: GitHub Actions does on every push.
- It does not run Flyway separately. The app applies migrations at startup; a failed migration
  fails the revision and Cloud Run keeps serving the previous one.

## Rollback

```
gcloud run revisions list --service pulse-api --region africa-south1
gcloud run services update-traffic pulse-api --region africa-south1 --to-revisions=<revision>=100
```

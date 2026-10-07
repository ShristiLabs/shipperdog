# Secrets and service accounts: the api's runtime identity reads its
# signing keys from Secret Manager and reaches the artifact bucket
# with objectAdmin (uploads, reads, V4 signing); the portal needs no
# cloud permissions at all — it serves static files and proxies /api
# to the api service on the internal Cloud Run URL.

resource "google_secret_manager_secret" "auth_jwt_secret" {
  secret_id = "${local.service_name}-auth-jwt-secret"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "auth_jwt_secret" {
  secret      = google_secret_manager_secret.auth_jwt_secret.id
  secret_data = var.auth_jwt_secret
}

resource "google_secret_manager_secret" "download_signing_key" {
  secret_id = "${local.service_name}-download-signing-key"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "download_signing_key" {
  secret      = google_secret_manager_secret.download_signing_key.id
  secret_data = var.artifact_download_proxy_signing_key
}

resource "google_secret_manager_secret" "db_password" {
  secret_id = "${local.service_name}-db-password"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "db_password" {
  secret      = google_secret_manager_secret.db_password.id
  secret_data = local.database_password
}

resource "google_service_account" "api" {
  account_id   = "${local.service_name}-api"
  display_name = "ShipperDog api runtime"
}

resource "google_service_account" "portal" {
  account_id   = "${local.service_name}-portal"
  display_name = "ShipperDog portal runtime"
}

resource "google_secret_manager_secret_iam_member" "api_can_read_secrets" {
  for_each = toset([
    google_secret_manager_secret.auth_jwt_secret.id,
    google_secret_manager_secret.download_signing_key.id,
    google_secret_manager_secret.db_password.id,
  ])
  secret_id = each.value
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.api.email}"
}

resource "google_storage_bucket_iam_member" "api_artifacts" {
  bucket = google_storage_bucket.artifacts.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.api.email}"
}

# Cloud SQL connections authenticate through the connector's IAM.
resource "google_project_iam_member" "api_sql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.api.email}"
}

# NOTE (least privilege, review finding): the deploy pipeline pushes
# images through its OWN workload-identity identity (see
# .github/workflows/deploy.yml and docs/deployment.md setup), which is
# granted artifactregistry.writer at project setup — deliberately NOT
# here. The runtime service accounts get no registry rights at all:
# Cloud Run pulls images with Google's internal mechanism, not with
# either service account's credentials.

# The data tier: PostgreSQL (the system of record), Redis (the
# decision-input cache — degradable by design, S7-03), and the GCS
# artifact bucket (ADR-0003). All private-IP; the app authenticates to
# GCS with its service account (application default credentials).

resource "google_artifact_registry_repository" "images" {
  repository_id = local.service_name
  description   = "Container images for the ShipperDog M1 stack (api, portal)."
  format        = "DOCKER"
  location      = var.region
}

resource "random_password" "db_password" {
  count   = var.db_password == null ? 1 : 0
  length  = 32
  special = false
}

locals {
  database_password = var.db_password != null ? var.db_password : random_password.db_password[0].result
}

resource "google_sql_database_instance" "postgres" {
  name             = "${local.service_name}-postgres"
  region           = var.region
  database_version = "POSTGRES_17"

  depends_on = [google_service_networking_connection.private_services]

  settings {
    tier              = "db-f1-micro" # demo sizing; scale for production load
    availability_type = "ZONAL"
    disk_size         = 10
    disk_type         = "PD_SSD"

    ip_configuration {
      ipv4_enabled    = false # private ONLY
      private_network = google_compute_network.network.id
    }

    backup_configuration {
      enabled = true
    }
  }

  deletion_protection = true
}

resource "google_sql_database" "app" {
  name     = local.service_name
  instance = google_sql_database_instance.postgres.name
}

resource "google_sql_user" "app" {
  name     = local.service_name
  instance = google_sql_database_instance.postgres.name
  password = local.database_password
}

resource "google_redis_instance" "cache" {
  name           = "${local.service_name}-cache"
  tier           = "BASIC"
  memory_size_gb = 1
  region         = var.region

  connect_mode       = "PRIVATE_SERVICE_ACCESS"
  authorized_network = google_compute_network.network.id

  depends_on = [google_service_networking_connection.private_services]

  # The decision-input cache must never be a data-loss surface.
  persistence_config {
    persistence_mode    = "RDB"
    rdb_snapshot_period = "TWELVE_HOURS"
  }
}

resource "google_storage_bucket" "artifacts" {
  name          = "${var.project_id}-${local.service_name}-artifacts"
  location      = var.region
  storage_class = "STANDARD"

  # Artifacts are immutable (INV-003) but never deleted in M1; the
  # bucket keeps soft deletes as the belt beneath the application
  # rule.
  soft_delete_policy {
    retention_duration_seconds = 604800 # 7 days
  }

  uniform_bucket_level_access = true
}

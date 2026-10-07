# The two Cloud Run services (ADR-0005): managed TLS on run.app
# domains, the api with health-checked startup, and the portal as the
# hardened nginx image (identical conf to compose — the same artifact
# the CI security-header check grades).

locals {
  # The dev profile's ONLY effect is adding the demo-org seed to the
  # Flyway locations (docs/database-migrations.md) — the M1 demo
  # environment runs it so the post-deploy smoke test has its seeded
  # tester; production sets seed_demo_data=false.
  api_profile = var.seed_demo_data ? "dev" : ""
}

resource "google_cloud_run_v2_service" "api" {
  name     = "${local.service_name}-api"
  location = var.region

  ingress = "INGRESS_TRAFFIC_ALL" # the fronting proxy / uptime check reaches the domain

  template {
    service_account = google_service_account.api.email

    scaling {
      min_instance_count = var.min_instances
      max_instance_count = 4
    }

    vpc_access {
      network_interfaces {
        network    = google_compute_network.network.id
        subnetwork = google_compute_subnetwork.subnet.id
      }
      egress = "PRIVATE_RANGES_ONLY"
    }

    containers {
      image = var.api_image
      ports {
        container_port = 8080
      }

      startup_probe {
        http_get {
          path = "/actuator/health"
          port = 8080
        }
        period_seconds    = 5
        timeout_seconds   = 3
        failure_threshold = 12
      }

      liveness_probe {
        http_get {
          path = "/actuator/health"
          port = 8080
        }
        period_seconds    = 30
        timeout_seconds   = 5
        failure_threshold = 4
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
        startup_cpu_boost = true
      }

      env {
        name  = "SERVER_PORT"
        value = "8080"
      }
      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = local.api_profile
      }
      env {
        name = "DATABASE_URL"
        value = format(
          "jdbc:postgresql://%s:5432/%s?sslmode=require",
          google_sql_database_instance.postgres.private_ip_address,
          google_sql_database.app.name,
        )
      }
      env {
        name  = "DATABASE_USERNAME"
        value = google_sql_user.app.name
      }
      env {
        name = "DATABASE_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_password.secret_id
            version = "latest"
          }
        }
      }
      env {
        name  = "REDIS_HOST"
        value = google_redis_instance.cache.host
      }
      env {
        name  = "REDIS_PORT"
        value = "6379"
      }
      env {
        name = "AUTH_JWT_SECRET"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.auth_jwt_secret.secret_id
            version = "latest"
          }
        }
      }
      env {
        name = "ARTIFACT_DOWNLOAD_PROXY_SIGNING_KEY"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.download_signing_key.secret_id
            version = "latest"
          }
        }
      }
      env {
        name  = "ARTIFACT_STORAGE_BUCKET"
        value = google_storage_bucket.artifacts.name
      }
      # Blank host = application default credentials against real GCS
      # (the GCS V4 signing mode of ADR-0003's download tier).
      env {
        name  = "ARTIFACT_STORAGE_EMULATOR_HOST"
        value = ""
      }
      # Cloud Run posture (ADR-0005): ONE public port, so management
      # rides on the serving port with only health,info exposed —
      # never prometheus (the compose stack keeps the separate
      # loopback management port instead). show-details stays never.
      env {
        name  = "MANAGEMENT_SERVER_PORT"
        value = "8080"
      }
      env {
        name  = "MANAGEMENT_ENDPOINTS_EXPOSE"
        value = "health,info"
      }
      # Forwarded-header trust (S8-05): Google's fronting proxy
      # supplies X-Forwarded-*; the rate limiter resolves the raw
      # chain itself regardless.
      env {
        name  = "FORWARD_HEADERS_STRATEGY"
        value = "framework"
      }
    }
  }

  depends_on = [google_service_networking_connection.private_services]
}

resource "google_cloud_run_v2_service" "portal" {
  name     = "${local.service_name}-portal"
  location = var.region

  ingress = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = google_service_account.portal.email

    scaling {
      max_instance_count = 2
    }

    containers {
      image = var.portal_image
      ports {
        container_port = 80
      }
      resources {
        limits = {
          cpu    = "1"
          memory = "256Mi"
        }
      }
      # The portal's nginx proxies /api to the api service over
      # Cloud Run's internal URL — same-origin from the browser's
      # point of view, exactly like compose.
      env {
        name  = "API_UPSTREAM"
        value = google_cloud_run_v2_service.api.uri
      }
    }
  }
}

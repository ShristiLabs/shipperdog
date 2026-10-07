output "api_url" {
  description = "The api service's managed-TLS URL (health + API surface)."
  value       = google_cloud_run_v2_service.api.uri
}

output "portal_url" {
  description = "The portal service's managed-TLS URL (the demo entry point)."
  value       = google_cloud_run_v2_service.portal.uri
}

output "artifact_registry" {
  description = "Where the deploy pipeline pushes api and portal images."
  value       = "${google_artifact_registry_repository.images.location}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.name}"
}

output "smoke_env" {
  description = "The environment the post-deploy smoke test needs (infrastructure/smoke/smoke.sh)."
  value = {
    API_BASE_URL    = google_cloud_run_v2_service.api.uri
    PORTAL_BASE_URL = google_cloud_run_v2_service.portal.uri
  }
}

variable "project_id" {
  description = "GCP project id the M1 stack deploys into."
  type        = string
}

variable "region" {
  description = "GCP region for every regional resource (Cloud Run, Cloud SQL, Memorystore, Artifact Registry)."
  type        = string
  default     = "us-central1"
}

variable "api_image" {
  description = "Fully-qualified api container image (Artifact Registry), built and pushed by the deploy pipeline."
  type        = string
}

variable "portal_image" {
  description = "Fully-qualified portal container image (Artifact Registry), built and pushed by the deploy pipeline."
  type        = string
}

variable "auth_jwt_secret" {
  description = "HMAC signing key for access/refresh JWTs (>= 32 bytes, high entropy). Stored in Secret Manager."
  type        = string
  sensitive   = true
}

variable "artifact_download_proxy_signing_key" {
  description = "HMAC key for emulator-mode download URLs (unused when GCS V4 signing is active, required at startup)."
  type        = string
  sensitive   = true
}

variable "db_password" {
  description = "Application database password. Stored in Secret Manager."
  type        = string
  sensitive   = true
  default     = null
}

variable "seed_demo_data" {
  description = "Run the api with the dev profile, which loads the demo-org fixture (docs/database-migrations.md): the M1 demo environment's smoke test logs in as the seeded tester. A production environment sets this false and provisions its own data; the bootstrap-admin story is an M2 item."
  type        = bool
  default     = true
}

variable "min_instances" {
  description = "Cloud Run min instances for the api (0 scales to zero with cold starts; 1 keeps the demo warm and the rate limiter's local buckets resident)."
  type        = number
  default     = 1
}

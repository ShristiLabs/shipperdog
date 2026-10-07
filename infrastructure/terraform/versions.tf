# Terraform for the M1 Cloud Run deployment (issue #39, S8-05).
#
# Provisions the whole shipping topology of PRD 45 in one apply:
# private networking (VPC + Serverless VPC connector), Cloud SQL
# PostgreSQL and Memorystore Redis on private IPs, the GCS artifact
# bucket, Artifact Registry for the two images, Secret Manager for
# the signing keys, the api + portal Cloud Run services with managed
# TLS, and Cloud Monitoring uptime checks. See docs/deployment.md
# for the runbook; ADR-0005 records the topology decisions.

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Remote state is the operator's choice (local for the demo
  # environment is acceptable; a production environment uses a GCS
  # backend and enables versioning). Uncomment and fill for GCS:
  #
  # backend "gcs" {
  #   bucket = "shipperdog-tfstate"
  #   prefix = "m1"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

locals {
  service_name = "shipperdog"
  # The API talks to the database and Redis over the VPC connector.
  connector = "${local.service_name}-connector"
}

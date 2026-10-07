# Private networking for the data tier (ADR-0005): Cloud Run reaches
# Cloud SQL and Memorystore exclusively over private IPs through a
# Serverless VPC Access connector — no database or cache is ever on a
# public address, and the app keeps its plain JDBC/Redis clients
# (no socket-factory dependency baked into the image).

resource "google_compute_network" "network" {
  name                    = "${local.service_name}-network"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "subnet" {
  name          = "${local.service_name}-subnet"
  ip_cidr_range = "10.10.0.0/24"
  region        = var.region
  network       = google_compute_network.network.id
  # Cloud Run containers need Private Google Access to reach
  # Artifact Registry / Secret Manager over Google's network.
  private_ip_google_access = true
}

# The private-services range Cloud SQL and Memorystore allocate their
# instances from (Google-managed services peering).
resource "google_compute_global_address" "private_services" {
  name          = "${local.service_name}-psa-range"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.network.id
}

resource "google_service_networking_connection" "private_services" {
  network                 = google_compute_network.network.id
  reserved_peering_ranges = [google_compute_global_address.private_services.name]
  service                 = "servicenetworking.googleapis.com"
}

# The connector Cloud Run egresses through to the private range.
resource "google_vpc_access_connector" "connector" {
  name          = local.connector
  region        = var.region
  network       = google_compute_network.network.id
  ip_cidr_range = "10.20.0.0/28"
  # 500 MHz / 256 MB is ample for the M1 single-service load.
  machine_type  = "e2-micro"
  min_instances = 2
  max_instances = 3
}

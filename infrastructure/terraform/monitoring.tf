# Availability observability (issue #39, S8-05; PRD 42's 99.9%
# portal availability SLO): Cloud Monitoring uptime checks on both
# public origins, every minute, from multiple regions — the external
# prober that stays up when the service is the thing that is down.
# The dashboarding leg of the story runs on the compose Grafana
# stack (S1-07); Cloud Monitoring alert policies wire to the
# operator's notification channels and are left to the runbook.

locals {
  api_host    = regex("https://([^/]+)", google_cloud_run_v2_service.api.uri)[0]
  portal_host = regex("https://([^/]+)", google_cloud_run_v2_service.portal.uri)[0]
}

resource "google_monitoring_uptime_check_config" "api_health" {
  display_name = "${local.service_name}-api-health"
  timeout      = "10s"
  period       = "60s"

  http_check {
    path         = "/actuator/health"
    port         = 443
    use_ssl      = true
    validate_ssl = true
  }

  monitored_resource {
    type = "uptime_url"
    labels = {
      host = local.api_host
    }
  }

  # The checks must read the SERVICES, not each other's template
  # rendering; both depend on their service existing.
  depends_on = [google_cloud_run_v2_service.api]
}

resource "google_monitoring_uptime_check_config" "portal" {
  display_name = "${local.service_name}-portal"
  timeout      = "10s"
  period       = "60s"

  http_check {
    path         = "/"
    port         = 443
    use_ssl      = true
    validate_ssl = true
    # The security-header set is part of the posture; accepting only
    # the classes we ship would need a custom checker — the uptime
    # leg asserts reachability, the CI header check asserts the set.
  }

  monitored_resource {
    type = "uptime_url"
    labels = {
      host = local.portal_host
    }
  }

  depends_on = [google_cloud_run_v2_service.portal]
}

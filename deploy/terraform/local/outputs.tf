output "api_url" {
  value = "http://${var.bind_address}:${var.api_port}/api/v1/analyses"
}

output "health_url" {
  value = "http://127.0.0.1:${var.management_port}/actuator/health/readiness"
}

output "metrics_url" {
  value = "http://127.0.0.1:${var.management_port}/actuator/prometheus"
}

output "keycloak_url" {
  description = "Admin console / issuer of the bundled dev Keycloak (admin password: terraform output -raw keycloak_admin_password)."
  value       = var.deploy_keycloak ? "http://localhost:${var.keycloak_port}/realms/dpi" : "not deployed"
}

output "keycloak_admin_password" {
  value     = var.deploy_keycloak ? random_password.keycloak_admin[0].result : ""
  sensitive = true
}

output "postgres_dsn" {
  description = "Connection string for local inspection (password via: terraform output -raw postgres_password)."
  value       = var.postgres_port > 0 ? "postgresql://${local.db_user}@127.0.0.1:${var.postgres_port}/${local.db_name}" : "not published"
}

output "postgres_password" {
  value     = random_password.db.result
  sensitive = true
}

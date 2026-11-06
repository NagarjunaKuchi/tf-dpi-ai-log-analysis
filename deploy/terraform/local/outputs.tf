output "api_url" {
  value = "http://${var.bind_address}:${var.api_port}/api/v1/analyses"
}

output "health_url" {
  value = "http://127.0.0.1:${var.management_port}/actuator/health/readiness"
}

output "metrics_url" {
  value = "http://127.0.0.1:${var.management_port}/actuator/prometheus"
}

output "postgres_dsn" {
  description = "Connection string for local inspection (password via: terraform output -raw postgres_password)."
  value       = var.postgres_port > 0 ? "postgresql://${local.db_user}@127.0.0.1:${var.postgres_port}/${local.db_name}" : "not published"
}

output "postgres_password" {
  value     = random_password.db.result
  sensitive = true
}

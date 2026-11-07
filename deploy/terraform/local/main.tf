locals {
  db_name = "dpi"
  db_user = "dpi"
  app_img = var.build_local_image ? docker_image.app_built[0].image_id : docker_image.app_pulled[0].image_id

  # With the bundled Keycloak the issuer is the browser-facing URL (--hostname) while keys are fetched over the docker network.
  jwt_issuer = var.deploy_keycloak ? "http://localhost:${var.keycloak_port}/realms/dpi" : var.jwt_issuer_uri
  jwk_set    = var.deploy_keycloak ? "http://keycloak:8080/realms/dpi/protocol/openid-connect/certs" : var.jwk_set_uri
}

resource "random_password" "keycloak_admin" {
  count   = var.deploy_keycloak ? 1 : 0
  length  = 32
  special = false
}

resource "random_password" "db" {
  length  = 32
  special = false
}

resource "docker_network" "net" {
  name = "${var.name_prefix}-net"
}

resource "docker_volume" "pgdata" {
  name = "${var.name_prefix}-pgdata"
}

resource "docker_volume" "ollama" {
  name = "${var.name_prefix}-ollama"
}

# ---------- images ----------
resource "docker_image" "postgres" {
  name         = "pgvector/pgvector:pg16"
  keep_locally = true
}

resource "docker_image" "ollama" {
  name         = "ollama/ollama:latest"
  keep_locally = true
}

resource "docker_image" "keycloak" {
  count        = var.deploy_keycloak ? 1 : 0
  name         = var.keycloak_image
  keep_locally = true
}

resource "docker_image" "app_built" {
  count = var.build_local_image ? 1 : 0
  name  = "${var.name_prefix}-log-analysis:local"

  build {
    context    = "${path.module}/../../.."
    dockerfile = "deploy/docker/Dockerfile"
  }

  # rebuild when sources change
  triggers = {
    pom = filesha256("${path.module}/../../../pom.xml")
    src = sha1(join("", [for f in fileset("${path.module}/../../../src", "**") : filesha1("${path.module}/../../../src/${f}")]))
  }
}

resource "docker_image" "app_pulled" {
  count        = var.build_local_image ? 0 : 1
  name         = var.app_image
  keep_locally = true

  lifecycle {
    precondition {
      condition     = var.app_image != ""
      error_message = "Set app_image when build_local_image is false."
    }
  }
}

# ---------- PostgreSQL + pgvector ----------
resource "docker_container" "postgres" {
  name  = "${var.name_prefix}-postgres"
  image = docker_image.postgres.image_id

  env = [
    "POSTGRES_DB=${local.db_name}",
    "POSTGRES_USER=${local.db_user}",
    "POSTGRES_PASSWORD=${random_password.db.result}",
    "TZ=UTC",
  ]

  networks_advanced {
    name    = docker_network.net.name
    aliases = ["postgres"]
  }

  volumes {
    volume_name    = docker_volume.pgdata.name
    container_path = "/var/lib/postgresql/data"
  }

  dynamic "ports" {
    for_each = var.postgres_port > 0 ? [1] : []
    content {
      internal = 5432
      external = var.postgres_port
      ip       = "127.0.0.1"
    }
  }

  healthcheck {
    test         = ["CMD-SHELL", "pg_isready -U ${local.db_user} -d ${local.db_name}"]
    interval     = "5s"
    timeout      = "3s"
    retries      = 20
    start_period = "10s"
  }

  wait         = true
  wait_timeout = 120
  restart      = "unless-stopped"
}

# ---------- Keycloak (DEV MODE ONLY: embedded H2, plain HTTP; production needs its own DB, TLS and hostname) ----------
resource "docker_container" "keycloak" {
  count = var.deploy_keycloak ? 1 : 0
  name  = "${var.name_prefix}-keycloak"
  image = docker_image.keycloak[0].image_id

  command = ["start-dev", "--http-port=8080", "--hostname=http://localhost:${var.keycloak_port}", "--import-realm"]

  env = [
    "KC_BOOTSTRAP_ADMIN_USERNAME=admin",
    "KC_BOOTSTRAP_ADMIN_PASSWORD=${random_password.keycloak_admin[0].result}",
  ]

  networks_advanced {
    name    = docker_network.net.name
    aliases = ["keycloak"]
  }

  volumes {
    host_path      = abspath("${path.module}/../../keycloak/dpi-realm.json")
    container_path = "/opt/keycloak/data/import/dpi-realm.json"
    read_only      = true
  }

  ports {
    internal = 8080
    external = var.keycloak_port
    ip       = "127.0.0.1"
  }

  restart = "unless-stopped"
}

# ---------- Ollama ----------
resource "docker_container" "ollama" {
  name  = "${var.name_prefix}-ollama"
  image = docker_image.ollama.image_id

  networks_advanced {
    name    = docker_network.net.name
    aliases = ["ollama"]
  }

  volumes {
    volume_name    = docker_volume.ollama.name
    container_path = "/root/.ollama"
  }

  gpus    = var.ollama_gpu ? "all" : null
  restart = "unless-stopped"
}

# One-off model download into the running Ollama (needs the docker CLI on this machine).
resource "terraform_data" "pull_models" {
  count = var.pull_models ? 1 : 0

  triggers_replace = [docker_container.ollama.id, var.chat_model, var.embedding_model]

  provisioner "local-exec" {
    command = "docker exec ${docker_container.ollama.name} ollama pull ${var.chat_model} && docker exec ${docker_container.ollama.name} ollama pull ${var.embedding_model}"
  }
}

# ---------- Application ----------
resource "docker_container" "app" {
  name  = "${var.name_prefix}-app"
  image = local.app_img

  env = [
    "DPI_DB_URL=jdbc:postgresql://postgres:5432/${local.db_name}",
    "DPI_DB_USER=${local.db_user}",
    "DPI_DB_PASSWORD=${random_password.db.result}",
    "DPI_JWT_ISSUER_URI=${local.jwt_issuer}",
    "DPI_JWK_SET_URI=${local.jwk_set}",
    "DPI_JWT_AUDIENCE=${var.jwt_audience}",
    "DPI_ES_URIS=${var.es_uris}",
    "DPI_ES_USER=${var.es_username}",
    "DPI_ES_PASSWORD=${var.es_password}",
    "DPI_ES_API_KEY=${var.es_api_key}",
    "DPI_OLLAMA_URL=http://ollama:11434",
    "DPI_CHAT_MODEL=${var.chat_model}",
    "DPI_CHAT_NUM_CTX=${var.chat_num_ctx}",
    "DPI_EMBEDDING_MODEL=${var.embedding_model}",
  ]

  networks_advanced {
    name = docker_network.net.name
  }

  ports {
    internal = 8080
    external = var.api_port
    ip       = var.bind_address
  }

  ports {
    internal = 8081
    external = var.management_port
    ip       = "127.0.0.1" # health + Prometheus: never publish beyond loopback
  }

  # hardening, mirrors the Helm chart
  read_only     = true
  tmpfs         = { "/tmp" = "rw,size=256m" }
  security_opts = ["no-new-privileges:true"]

  capabilities {
    drop = ["ALL"]
  }

  restart = "unless-stopped"

  lifecycle {
    precondition {
      condition     = var.deploy_keycloak || var.jwt_issuer_uri != ""
      error_message = "Set jwt_issuer_uri when deploy_keycloak=false (the service refuses to start without an issuer)."
    }
  }

  # The decoder is lazy, so the app does not need Keycloak to be up to start.
  depends_on = [docker_container.postgres, docker_container.ollama, terraform_data.pull_models]
}

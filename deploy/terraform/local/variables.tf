# ---- required (no defaults: the service refuses to start without them) ----
variable "jwt_issuer_uri" {
  description = "OIDC issuer URI that signs the API's JWTs (tokens need a 'roles' array claim). Required."
  type        = string
}

variable "es_uris" {
  description = "URI(s) of the existing Elasticsearch (read-only access). Use host.docker.internal for an ES on this machine."
  type        = string
}

# ---- image ----
variable "build_local_image" {
  description = "true: build the app image from this repository; false: pull var.app_image (e.g. the CI-published Docker Hub image)."
  type        = bool
  default     = true
}

variable "app_image" {
  description = "Image to pull when build_local_image=false, e.g. <dockerhub-user>/dpi-log-analysis:latest."
  type        = string
  default     = ""
}

# ---- elasticsearch credentials (optional) ----
variable "es_username" {
  type    = string
  default = ""
}

variable "es_password" {
  type      = string
  default   = ""
  sensitive = true
}

variable "es_api_key" {
  type      = string
  default   = ""
  sensitive = true
}

# ---- models ----
variable "chat_model" {
  description = "Ollama chat model tag. Verify the tag exists, e.g. llama3.1:8b or mistral:7b."
  type        = string
  default     = "llama3.1:8b"
}

variable "embedding_model" {
  type    = string
  default = "bge-m3"
}

variable "chat_num_ctx" {
  type    = number
  default = 8192
}

variable "pull_models" {
  description = "Pull the chat and embedding models into Ollama after it starts (large downloads)."
  type        = bool
  default     = true
}

variable "ollama_gpu" {
  description = "Give the Ollama container all GPUs (needs the NVIDIA container toolkit)."
  type        = bool
  default     = false
}

# ---- networking ----
variable "bind_address" {
  description = "Host address the published ports bind to. Keep 127.0.0.1 unless you understand the exposure."
  type        = string
  default     = "127.0.0.1"
}

variable "api_port" {
  type    = number
  default = 8080
}

variable "management_port" {
  type    = number
  default = 8081
}

variable "postgres_port" {
  description = "Host port for PostgreSQL (loopback only); 0 disables publishing."
  type        = number
  default     = 15432
}

variable "name_prefix" {
  type    = string
  default = "dpi"
}

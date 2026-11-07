# CI/CD and local infrastructure

Status: the workflow was linted with `actionlint` and its test commands were run locally against PostgreSQL 16 + pgvector; **it has not run on
GitHub Actions**, and the Docker build/push steps have never executed (no Docker daemon in the authoring environment). The Terraform stack passes
`terraform fmt` and `terraform validate` (Terraform 1.9.8, docker provider 3.9.0) but was **never planned or applied**.

## Pipeline (`.github/workflows/ci.yml`)
| Job | Runs on | What it does |
|---|---|---|
| `test` | every push / PR | `mvn verify` against a `pgvector/pgvector:pg16` service container (all 158 tests incl. full-context and end-to-end); fails if any test was skipped, so a missing database cannot pass silently |
| `chart` | every push / PR | `helm lint`, renders two configurations, validates against Kubernetes 1.30 schemas with kubeconform (checksum-pinned download) |
| `image` | after `test` + `chart` | builds the image, scans it with Trivy (fails on fixable CRITICAL), and **pushes to Docker Hub only on `main` and `v*` tags** (never on pull requests) |

Image tags: `latest` (main), short git SHA, and `X.Y.Z` / `X.Y` for `vX.Y.Z` tags. The push includes SBOM and provenance attestations. Single platform (linux/amd64).

### One-time setup (GitHub repository settings)
1. Docker Hub: create an access token (Account settings → Security) with *Read & Write* on the repository.
2. GitHub → Settings → Secrets and variables → Actions: add secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`.
3. Optional variable `DOCKERHUB_IMAGE` (e.g. `myorg/dpi-log-analysis`); default is `<DOCKERHUB_USERNAME>/dpi-log-analysis`.
4. Recommended: branch protection on `main` requiring the `test` and `chart` checks; restrict who can create `v*` tags.
5. Release: `git tag v0.1.0 && git push origin v0.1.0`.

### Supply-chain notes
Third-party actions are pinned to commit SHAs (resolved from the upstream tags on 2026-11-06); Dependabot (`.github/dependabot.yml`) proposes weekly updates for actions, Maven and the base image.
Base images in the Dockerfile are pinned by tag, not digest. The Trivy gate only covers fixable CRITICAL findings; tighten it once the baseline is known.
Known limitation: `latest` is moved on every main build; deploy by SHA or version tag, not `latest`.

## Local machine IaC (`deploy/terraform/local`)
Terraform + the Docker provider create: a network, PostgreSQL+pgvector (generated password, health-checked), Ollama (optional GPU, optional model pull) and the app container
(hardened: read-only FS, no capabilities, no-new-privileges; API bound to 127.0.0.1, management port loopback-only). Elasticsearch stays **external** and must be supplied. A dev-mode Keycloak (realm `dpi`) is started by default (`deploy_keycloak = true`); set `deploy_keycloak = false` and `jwt_issuer_uri` to use your own OIDC provider.

```bash
cd deploy/terraform/local
cp terraform.tfvars.example terraform.tfvars      # set es_uris (and jwt_issuer_uri if deploy_keycloak=false)
terraform init && terraform apply                 # builds the image locally by default
terraform output health_url                       # then: curl <health_url>
terraform output -raw keycloak_admin_password     # admin console: http://localhost:8180 (create users, assign roles)
terraform destroy                                 # volumes with data are removed too
```
To run the CI-published image instead of building: `-var build_local_image=false -var app_image=<user>/dpi-log-analysis:<tag>`.
Requirements: Docker running locally, the docker CLI (used to pull models), network access for image/model downloads (several GB).

Caveats: Terraform state is local and contains the generated database password in plaintext (it is git-ignored; do not commit or share it).
The Compose file in `deploy/compose` is an alternative for the same stack. A local Kubernetes option (kind + the Helm chart) is not provided.

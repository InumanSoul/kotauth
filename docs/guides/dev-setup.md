# Developer Setup

## Prerequisites

| Tool | Version | Purpose |
|---|---|---|
| Java | 17+ | Gradle / Ktor runtime |
| Docker + Compose | any recent | Local stack |
| Node.js | 20+ | CSS compilation (first run only) |

No global npm installs are needed. The `installCssDeps` Gradle task runs `npm ci` inside `frontend/` automatically on first use.

---

## Quick-start

```bash
# 1. Copy the env template
cp .env.example .env

# 2. Generate a secret key and add it to .env
openssl rand -hex 32
# or, if you have the JAR built:
#   make generate-key

# 3. Paste the key into .env as KAUTH_SECRET_KEY=<value>

# 4. Build and start the full stack
make up
```

`KAUTH_SECRET_KEY` is required in all environments. The server will not start without it.

The app is available at `http://localhost:8080`. The admin console is at `http://localhost:8080/admin`.

---

## Available make targets

Run `make help` from the project root to see all targets with descriptions. Summary:

```
CSS
  css            Compile all four CSS bundles (admin, auth, portal-sidenav, portal-tabnav)
  css-admin      Compile the admin console bundle only
  css-auth       Compile the auth pages bundle only
  css-portal     Compile the portal bundles (sidenav + tabnav)

Kotlin
  lint           Run ktlint check
  lint-fix       Auto-fix lint issues
  test           Run the unit/integration test suite
  e2e            Run E2E browser smoke tests (Playwright, headless)
  e2e-headed     Run E2E tests with visible browser (debugging)
  build          Full build — CSS + lint + tests + fat JAR (CI-equivalent)
  jar            Fat JAR only, skipping tests (faster iteration)
  version        Generate version.properties resource (required before running from IDE)

Docker
  up             Build images from source and start all services (dev)
  up-fresh       Rebuild from scratch (no layer cache)
  down           Stop and remove containers
  nuke           Wipe containers and volumes (destroys DB)
  logs           Follow app container logs
  health         Probe http://localhost:8080/health/ready

CLI
  generate-key   Generate a cryptographically secure KAUTH_SECRET_KEY
  reset-mfa      Reset MFA for an admin user (usage: make reset-mfa USER=admin)
```

---

## Optional: `kotauth` shell function

If you work across multiple terminals or want to run make targets from any directory, paste this function into your `~/.zshrc` or `~/.bashrc`:

```bash
function kotauth() {
  # Resolve the repo root from the current git context, falling back to an
  # explicit path if you are calling from outside the repo.
  local repo
  repo="$(git -C "$(pwd)" rev-parse --show-toplevel 2>/dev/null)"
  if [[ -z "$repo" ]]; then
    echo "kotauth: not inside a git repository. Set KOTAUTH_ROOT or cd into the repo." >&2
    return 1
  fi
  make -C "$repo" "$@"
}
```

After sourcing your shell config (`source ~/.zshrc`) you can call any make target from anywhere:

```bash
kotauth css          # compile CSS
kotauth lint-fix     # auto-fix Kotlin lint
kotauth up-fresh     # full Docker rebuild
kotauth logs         # follow app logs
kotauth help         # list all targets
```

---

## CSS pipeline notes

The four compiled CSS bundles (`kotauth-admin.css`, `kotauth-auth.css`, `kotauth-portal-sidenav.css`, `kotauth-portal-tabnav.css`) are written to `src/main/resources/static/` and embedded in the fat JAR by `processResources`. You rarely need to run the CSS tasks manually — `make build` handles everything.

When iterating on frontend styles locally:
1. Edit files under `frontend/css/`
2. Run `make css` (or `make css-admin` / `make css-auth` / `make css-portal` for individual bundles)
3. Restart the app — the updated bundle is picked up immediately if you are running outside Docker, or rebuild the container if running inside

In Docker builds the CSS stage is handled by a dedicated `node:20-slim` layer before Gradle runs, so the Gradle CSS tasks are skipped with `-x` flags. See `Dockerfile` for details.

---

## Lint scope

`ktlintCheck` and `ktlintFormat` cover all `.kt` sources **except** `*View.kt` files. The `*View.kt` exclusion exists because the deeply nested kotlinx.html DSL creates non-converging formatter loops between ktlint's indent and wrapping rules. All domain, service, persistence, and route files are linted normally.

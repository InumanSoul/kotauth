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
# 1. Copy the env template and fill in the required values
cp .env.example .env

# 2. Build and start the full stack
make up
```

The app is available at `http://localhost:8080`. The admin console is at `http://localhost:8080/admin`.

---

## Available make targets

Run `make help` from the project root to see all targets with descriptions. Summary:

```
CSS
  css            Compile both CSS bundles (admin + auth)
  css-admin      Compile the admin console bundle only
  css-auth       Compile the auth pages bundle only

Kotlin
  lint           Run ktlint check
  lint-fix       Auto-fix lint issues
  test           Run the test suite
  build          Full build — CSS + lint + tests + fat JAR
  jar            Fat JAR only, skipping tests

Docker
  up             Build images and start all services
  up-fresh       Rebuild from scratch (no layer cache)
  down           Stop and remove containers
  nuke           Wipe containers and volumes (destroys DB)
  logs           Follow app container logs
  health         Probe http://localhost:8080/health/ready
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

The two compiled CSS bundles are written to `src/main/resources/static/` and embedded in the fat JAR by `processResources`. You rarely need to run the CSS tasks manually — `make build` handles everything.

When iterating on frontend styles locally:
1. Edit files under `frontend/css/`
2. Run `make css` (or `make css-admin` / `make css-auth` for a single bundle)
3. Restart the app — the updated bundle is picked up immediately if you are running outside Docker, or rebuild the container if running inside

In Docker builds the CSS stage is handled by a dedicated `node:20-slim` layer before Gradle runs, so the Gradle CSS tasks are skipped with `-x` flags. See `Dockerfile` for details.

---

## Lint scope

`ktlintCheck` and `ktlintFormat` cover all `.kt` sources **except** `*View.kt` files. The `*View.kt` exclusion exists because the deeply nested kotlinx.html DSL creates non-converging formatter loops between ktlint's indent and wrapping rules. All domain, service, persistence, and route files are linted normally.

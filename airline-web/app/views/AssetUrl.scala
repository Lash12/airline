package views

/**
 * Cache-busting asset URL helper.
 *
 * The app runs in Play dev mode (`sbt run`) in the deploy container — the container has no Node, so the
 * production asset pipeline (which would fingerprint filenames via sbt-digest) can't run. In dev mode
 * `Assets.versioned` returns the plain, un-hashed path, so the Cloudflare tunnel in front of the app
 * edge-caches JS/CSS by extension and serves stale files across deploys.
 *
 * Appending a per-build token (`?v=<token>`) gives every deploy a fresh CDN cache key without needing
 * the production build. The token is the `assets.version` system property — set on deploy to the git
 * sha (see docker-compose / optiplex-deploy) — and falls back to "dev" locally so iteration is
 * unaffected. Read once at class load; the app JVM is long-lived per deploy.
 */
object AssetUrl {
  private val version : String = sys.props.getOrElse("assets.version", "dev")

  def apply(path : String) : String = controllers.routes.Assets.versioned(path).url + "?v=" + version
}

// Webpack entry for the baked watch bundle.
//
// Exists so `webpack.config.js` at the repo root keeps ZERO fork diff — it is a shared file
// with upstream, and the cheapest merge is no merge (ticket 05).
//
// Selects the watch config by ENTRY, never by array index. The config has no `name` field,
// so `--config-name` cannot address it, and indexing into the exported array would let an
// upstream reorder silently bake the *web* bundle into the watch APK — which would still
// build, still install, and fail only at runtime (ticket 10).

const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "../..");
const WATCH_ENTRY = "./src/watch/index.ts";

if (process.env.NODE_ENV !== "production") {
  // A dev bundle is 2.1MB vs 442KB, with proportionally slower parse on a device where cold
  // start is a budgeted metric. Fail rather than quietly shipping one.
  throw new Error(
    "webpack.watch.js: NODE_ENV must be 'production' (got " +
      JSON.stringify(process.env.NODE_ENV) +
      "). The :wear Gradle build sets this; if you are running webpack by hand, set it."
  );
}

let configs;
try {
  configs = require(path.join(REPO_ROOT, "webpack.config.js"));
} catch (e) {
  throw new Error(
    "webpack.watch.js: could not load the root webpack.config.js. Run `npm install` at the " +
      "repo root first.\nUnderlying error: " +
      e.message
  );
}

if (!Array.isArray(configs)) {
  throw new Error(
    "webpack.watch.js: expected webpack.config.js to export an array of configs, got " +
      typeof configs +
      ". Upstream changed its shape; update this shim."
  );
}

const watchConfig = configs.find((c) => c && c.entry === WATCH_ENTRY);

if (!watchConfig) {
  const entries = configs.map((c) => (c && c.entry ? JSON.stringify(c.entry) : "(none)")).join(", ");
  throw new Error(
    "webpack.watch.js: no config with entry " +
      JSON.stringify(WATCH_ENTRY) +
      " in webpack.config.js. Found entries: " +
      entries +
      ". Upstream renamed or removed the watch entry; update WATCH_ENTRY."
  );
}

module.exports = watchConfig;

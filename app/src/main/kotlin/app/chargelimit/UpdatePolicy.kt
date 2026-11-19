package app.chargelimit

/** Decides when the app can update and which release file it can use. */
object UpdatePolicy {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Release files must come from this address. */
    private const val DOWNLOAD_PREFIX = "https://github.com/dz0ny/charge-limiter/releases/download/"

    /** Returns the numbers of a version such as "v0.2.0". Returns null for all other text. */
    private fun parse(version: String): List<Int>? {
        val parts = version.removePrefix("v").split(".")
        val numbers = parts.map { part -> part.toIntOrNull()?.takeIf { it >= 0 && part.all(Char::isDigit) } }
        return if (numbers.any { it == null }) null else numbers.filterNotNull()
    }

    /** Returns true when the release [tag] is a later version than [current]. Bad text is never newer. */
    fun isNewer(tag: String, current: String): Boolean {
        val release = parse(tag) ?: return false
        val installed = parse(current) ?: return false
        for (i in 0 until maxOf(release.size, installed.size)) {
            val a = release.getOrElse(i) { 0 }
            val b = installed.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** A test build has a different signing key, so Android refuses a release over it. */
    fun canSelfUpdate(current: String): Boolean = parse(current) != null

    /** Returns true one day after the last check. A clock that moved back also gives true. */
    fun dueForCheck(nowMs: Long, lastMs: Long): Boolean = nowMs - lastMs >= DAY_MS || nowMs < lastMs

    /** Returns the address of the APK in a list of file names and addresses. */
    fun apkUrl(assets: List<Pair<String, String>>): String? =
        assets.firstOrNull { (name, url) -> name.endsWith(".apk") && url.startsWith(DOWNLOAD_PREFIX) }?.second
}

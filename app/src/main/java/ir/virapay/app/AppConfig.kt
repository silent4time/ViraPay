package ir.virapay.app

/** Central place for values you may want to edit before publishing. */
object AppConfig {
    /**
     * Licensing is fully implemented but switched OFF.
     * While false: no limits, no activation screen. Flip to true when you are ready.
     */
    const val LICENSE_ENABLED = false

    /** Unlicensed limits (used only when LICENSE_ENABLED = true). */
    const val FREE_MAX_BOTS = 1
    const val FREE_MAX_CARDS = 1

    /** Your license server (see /license-server) and its public key (X.509, Base64). */
    const val LICENSE_SERVER_URL = "https://license.example.com"
    const val LICENSE_PUBLIC_KEY = "REPLACE_WITH_BASE64_X509_PUBLIC_KEY"

    /** About screen. */
    const val BUILDER_NAME = "Nasrollah Valizadeh (silent4time)"
    const val GITHUB_URL = "https://github.com/silent4time/ViraPay"
    const val ISSUES_URL = "https://github.com/silent4time/ViraPay/issues"
    const val CONTACT_TELEGRAM = "silent4time"
}

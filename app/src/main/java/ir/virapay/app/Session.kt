package ir.virapay.app

/** In-memory unlock state: reset whenever the process dies. */
object Session {
    @Volatile var unlocked = false
    @Volatile var failedAttempts = 0
    @Volatile var lockedUntil = 0L
}

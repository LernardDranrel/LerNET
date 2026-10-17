package app.lernet.config.net

import java.security.SecureRandom

/**
 * Login and password of a LerNET listener on this device (127.0.0.1).
 * Without them sing-box refuses the connection, so other programs cannot use the tunnel unasked.
 */
class LocalProxyAccess(
    val port: Int,
    val username: String,
    val password: String,
) {
    init {
        require(port in 1..65535) { "Неверный порт" }
        require(username.isNotBlank() && password.isNotBlank()) { "Нужны логин и пароль" }
    }

    val host: String get() = HOST
    val address: String get() = "$HOST:$port"

    /** Ready to paste into programs that accept a proxy URL. */
    fun url(): String = "http://${encode(username)}:${encode(password)}@$HOST:$port"

    override fun equals(other: Any?): Boolean =
        other is LocalProxyAccess && other.port == port && other.username == username && other.password == password

    override fun hashCode(): Int = (port * 31 + username.hashCode()) * 31 + password.hashCode()

    /** Never prints the password: snapshots and logs may be shared. */
    override fun toString(): String = "LocalProxyAccess($address, user=$username, password=***)"

    companion object {
        const val HOST = "127.0.0.1"
        const val USER_PORT = 2080
        private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()

        /** Fresh random login and password; called for every connection. */
        fun random(port: Int, rng: java.util.Random = random): LocalProxyAccess =
            LocalProxyAccess(port, "lernet-" + token(6, rng), token(20, rng))

        /** User's own persistent pair from the settings, or a random one if they left it empty. */
        fun forUserProxy(customUsername: String?, customPassword: String?): LocalProxyAccess {
            val user = customUsername?.trim().orEmpty()
            val password = customPassword.orEmpty()
            return if (user.isNotEmpty() && password.isNotEmpty() && valid(user) && valid(password)) {
                LocalProxyAccess(USER_PORT, user, password)
            } else {
                random(USER_PORT)
            }
        }

        /** Printable ASCII without spaces and ':' keeps every proxy client happy. */
        fun valid(value: String): Boolean = value.length in 1..64 && value.all { it in '!'..'~' && it != ':' }

        private fun token(length: Int, rng: java.util.Random): String =
            buildString(length) { repeat(length) { append(ALPHABET[rng.nextInt(ALPHABET.length)]) } }

        private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

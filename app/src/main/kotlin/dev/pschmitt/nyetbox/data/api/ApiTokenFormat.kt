package dev.pschmitt.nyetbox.data.api

/** The two-part token format used by current NetBox token creation screens. */
data class NamedApiToken(
    val prefix: String,
    val name: String,
    val value: String,
) {
    val serialized: String
        get() = "$prefix$name.$value"
}

fun composeNamedApiToken(name: String, value: String): String? {
    val normalizedName = name.trim().removePrefix(NAMED_API_TOKEN_PREFIX)
    val normalizedValue = value.trim()
    if (
        normalizedName.isBlank() ||
            normalizedValue.isBlank() ||
            normalizedName.any { it.isWhitespace() || it == '.' } ||
            normalizedValue.any { it.isWhitespace() || it == '.' }
    ) {
        return null
    }
    return "$NAMED_API_TOKEN_PREFIX$normalizedName.$normalizedValue"
}

/** Parses complete named tokens so pasted credentials can switch the login form automatically. */
fun parseNamedApiToken(token: String): NamedApiToken? {
    val match = NAMED_API_TOKEN_REGEX.matchEntire(token.trim()) ?: return null
    return NamedApiToken(
        prefix = match.groupValues[1],
        name = match.groupValues[2],
        value = match.groupValues[3],
    )
}

const val NAMED_API_TOKEN_PREFIX = "nbt_"

private val NAMED_API_TOKEN_REGEX = Regex("^(nbp_|nbt_)([^.\\s]+)\\.([^.\\s]+)$")

package dev.aether.theme

import dev.aether.model.theme.ThemePackage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

/** Portable themes are data-only JSON; unknown or duplicate keys and special floats are rejected. */
object ThemePackageCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        allowSpecialFloatingPointValues = false
        explicitNulls = false
    }

    fun decode(raw: String): Result<ThemePackage> {
        if (raw.isBlank()) {
            return Result.failure(ThemeValidationException(ThemeValidationError.MalformedJson("empty input")))
        }
        val bytes = raw.toByteArray(StandardCharsets.UTF_8).size
        if (bytes > ThemePackageValidator.MAX_THEME_JSON_BYTES) {
            return Result.failure(ThemeValidationException(ThemeValidationError.OversizedJson(bytes)))
        }
        return runCatching {
            rejectDuplicateKeys(raw)
            val decoded = try {
                json.decodeFromString<ThemePackage>(raw)
            } catch (failure: SerializationException) {
                throw ThemeValidationException(ThemeValidationError.MalformedJson(failure.message ?: "invalid JSON"))
            } catch (failure: IllegalArgumentException) {
                throw ThemeValidationException(ThemeValidationError.MalformedJson(failure.message ?: "invalid value"))
            }
            ThemePackageValidator.validate(decoded).getOrThrow()
            decoded
        }
    }

    fun encode(themePackage: ThemePackage): String {
        ThemePackageValidator.validate(themePackage).getOrThrow()
        return json.encodeToString(themePackage)
    }

    /** Guards against duplicate object keys overriding earlier values in a package. */
    private fun rejectDuplicateKeys(raw: String) {
        val keysAtDepth = ArrayDeque<MutableSet<String>>()
        var inString = false
        var escaped = false
        var stringStart = 0
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                inString && char == '"' -> {
                    inString = false
                    var next = index + 1
                    while (next < raw.length && raw[next].isWhitespace()) next++
                    if (next < raw.length && raw[next] == ':') {
                        val key = raw.substring(stringStart, index)
                        val keys = keysAtDepth.lastOrNull()
                            ?: throw ThemeValidationException(ThemeValidationError.MalformedJson("key outside object"))
                        if (!keys.add(key)) {
                            throw ThemeValidationException(ThemeValidationError.MalformedJson("duplicate key: $key"))
                        }
                    }
                }

                char == '"' -> {
                    inString = true
                    stringStart = index + 1
                }

                char == '{' -> keysAtDepth.addLast(HashSet())
                char == '}' -> if (keysAtDepth.isNotEmpty()) keysAtDepth.removeLast()
            }
            index++
        }
    }
}

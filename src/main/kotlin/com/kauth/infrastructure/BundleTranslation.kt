package com.kauth.infrastructure

import com.kauth.adapter.web.EnglishStrings
import com.kauth.domain.port.TranslationPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * `TranslationPort` backed by volume-mounted JSON bundles, with
 * [EnglishStrings] as the always-available English source of truth.
 *
 * Loaded once at startup from the directory pointed to by `bundleDir`.
 * Each `<locale>.json` file is a flat key→value map keyed by the
 * `EnglishStrings` field name:
 *
 * ```
 * # es.json
 * {
 *   "PASSWORD": "Contraseña",
 *   "NEW_PASSWORD": "Nueva contraseña"
 * }
 * ```
 *
 * Behavior:
 *   - English is baked-in. An `en.json` in the bundle dir is ignored with
 *     a WARN log — the JAR's English source of truth is authoritative.
 *   - Unknown locale → falls back to English transparently.
 *   - Locale present, key missing → falls back to English for that key.
 *   - Malformed JSON or non-string values → file is skipped with a WARN log.
 *     Other locales still load.
 */
class BundleTranslation(
    bundleDir: Path,
    private val fallback: TranslationPort = EnglishOnlyTranslation(),
) : TranslationPort {
    private val log = LoggerFactory.getLogger(BundleTranslation::class.java)

    private val bundles: Map<String, Map<String, String>> = loadBundles(bundleDir)

    override val availableLocales: Set<String> =
        // English is always available regardless of what bundles loaded.
        bundles.keys + setOf("en")

    override fun t(
        key: String,
        locale: String,
        vararg args: Any?,
    ): String {
        val template = bundles[locale]?.get(key) ?: return fallback.t(key, "en", *args)
        return substitute(template, args)
    }

    private fun loadBundles(dir: Path): Map<String, Map<String, String>> {
        if (!Files.isDirectory(dir)) {
            log.warn(
                "KAUTH_I18N_BUNDLE_DIR={} is not a directory — falling back to English only",
                dir,
            )
            return emptyMap()
        }
        val files =
            Files.list(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.extension.equals("json", ignoreCase = true) }
                    .toList()
            }
        return files
            .mapNotNull { path ->
                val locale = path.nameWithoutExtension.lowercase()
                if (locale == "en") {
                    log.warn(
                        "Ignoring {}: English is baked into the JAR and cannot be overridden via bundle dir",
                        path,
                    )
                    null
                } else {
                    parseBundle(path)?.let { locale to it }
                }
            }.toMap()
    }

    private fun parseBundle(path: Path): Map<String, String>? =
        try {
            val obj = Json.parseToJsonElement(path.readText(Charsets.UTF_8)).jsonObject
            obj
                .mapValues { (key, value) ->
                    val primitive = value as? JsonPrimitive ?: error("non-string value for key '$key'")
                    require(primitive.isString) { "non-string value for key '$key'" }
                    primitive.content
                }.also { map ->
                    val englishKnown = EnglishStrings.byKey.keys
                    val unknown = map.keys - englishKnown
                    if (unknown.isNotEmpty()) {
                        log.info(
                            "Bundle {} contains {} key(s) not present in EnglishStrings — they will be returned " +
                                "if requested but have no English fallback. Examples: {}",
                            path.fileName,
                            unknown.size,
                            unknown.take(5),
                        )
                    }
                }
        } catch (e: Exception) {
            log.warn("Failed to parse i18n bundle {}: {}", path, e.message, e)
            null
        }

    private fun substitute(
        template: String,
        args: Array<out Any?>,
    ): String {
        if (args.isEmpty()) return template
        return args.foldIndexed(template) { index, acc, value ->
            acc.replace("{$index}", value?.toString() ?: "")
        }
    }
}

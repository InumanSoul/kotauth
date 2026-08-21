package com.kauth.domain.scim

// RFC 7644 §3.5.2 path grammar:
//   PATH     := attrPath / valuePath [subAttr]
//   valuePath:= attrPath "[" valFilter "]"
//   attrPath := [URI ":"] ATTRNAME [subAttr]
//   subAttr  := "." ATTRNAME

sealed interface ScimPath {
    data class Attr(
        val urn: String?,
        val name: String,
        val sub: String?,
    ) : ScimPath

    data class Valued(
        val attr: Attr,
        val filter: ScimFilter,
        val sub: String?,
    ) : ScimPath
}

private val ATTR_NAME = Regex("^[A-Za-z][A-Za-z0-9_-]*$")

/** Parses an RFC 7644 §3.5.2 path. Never throws; malformed input is a failure value. */
fun parsePath(raw: String): Result<ScimPath> =
    try {
        if (raw.isBlank()) {
            throw PathSyntaxException("empty path")
        }
        Result.success(PathParser(raw).parse())
    } catch (e: PathSyntaxException) {
        Result.failure(ScimFailure(ScimErrorType.invalidPath, e.message ?: "invalid path: \"$raw\""))
    }

/** Internal-only: carries a human-readable parse failure up to [parsePath]. */
private class PathSyntaxException(
    message: String,
) : Exception(message)

private class PathParser(
    private val raw: String,
) {
    fun parse(): ScimPath {
        val bracketIndex = raw.indexOf('[')
        val attrPathEnd = if (bracketIndex >= 0) bracketIndex else raw.length
        val attr = parseAttrPath(raw.substring(0, attrPathEnd), 0)
        if (bracketIndex < 0) {
            return attr
        }
        val closeIndex = findMatchingBracket(bracketIndex)
        if (closeIndex < 0) {
            fail("unbalanced brackets", bracketIndex)
        }
        val filterText = raw.substring(bracketIndex + 1, closeIndex)
        // The caller of parsePath is patching, not querying, so a bad filter inside
        // brackets is reported as an invalid path, not surfaced as an invalid filter.
        val filter =
            parseFilter(filterText).getOrElse { e ->
                val detail = (e as? ScimFailure)?.detail ?: e.message ?: "invalid filter"
                fail("invalid filter in path: $detail", bracketIndex + 1)
            }
        var pos = closeIndex + 1
        var sub: String? = null
        if (pos < raw.length) {
            if (raw[pos] != '.') {
                fail("unexpected character after ']'", pos)
            }
            pos++
            sub = parseAttrName(raw.substring(pos), pos)
        }
        return ScimPath.Valued(attr, filter, sub)
    }

    private fun parseAttrPath(
        segment: String,
        offset: Int,
    ): ScimPath.Attr {
        if (segment.isEmpty()) {
            fail("empty attribute path", offset)
        }
        // A URN itself is colon-delimited, so the URN/attribute boundary is the LAST
        // colon in the segment, never the first.
        val colonIndex = segment.lastIndexOf(':')
        val urn = if (colonIndex >= 0) segment.substring(0, colonIndex) else null
        val rest = if (colonIndex >= 0) segment.substring(colonIndex + 1) else segment
        if (urn != null && urn.isBlank()) {
            fail("empty URN prefix", offset)
        }
        val restOffset = offset + colonIndex + 1
        if (rest.isEmpty()) {
            fail("empty attribute name", restOffset)
        }
        val dotIndex = rest.indexOf('.')
        if (dotIndex < 0) {
            return ScimPath.Attr(urn, parseAttrName(rest, restOffset), null)
        }
        val name = rest.substring(0, dotIndex)
        val subRest = rest.substring(dotIndex + 1)
        if (subRest.isEmpty() || subRest.contains('.')) {
            fail("malformed sub-attribute", restOffset + dotIndex)
        }
        val sub = parseAttrName(subRest, restOffset + dotIndex + 1)
        return ScimPath.Attr(urn, parseAttrName(name, restOffset), sub)
    }

    private fun parseAttrName(
        name: String,
        offset: Int,
    ): String {
        if (!ATTR_NAME.matches(name)) {
            fail("invalid attribute name '$name'", offset)
        }
        return name
    }

    private fun findMatchingBracket(openIndex: Int): Int {
        var i = openIndex + 1
        var inQuotes = false
        while (i < raw.length) {
            val c = raw[i]
            if (inQuotes) {
                if (c == '\\' && i + 1 < raw.length) {
                    i += 2
                    continue
                }
                if (c == '"') inQuotes = false
            } else {
                if (c == '"') {
                    inQuotes = true
                } else if (c == ']') {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun fail(
        message: String,
        position: Int,
    ): Nothing {
        val clamped = position.coerceIn(0, raw.length)
        val fragment = if (clamped < raw.length) raw.substring(clamped) else "<end of input>"
        throw PathSyntaxException("$message at position $clamped: '$fragment' in \"$raw\"")
    }
}

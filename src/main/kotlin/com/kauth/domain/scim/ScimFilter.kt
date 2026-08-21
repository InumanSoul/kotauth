package com.kauth.domain.scim

// RFC 7644 §3.4.2.2 grammar supported here: filter := orExpr; orExpr := andExpr ("or" andExpr)*;
// andExpr := primary ("and" primary)*; primary := "(" orExpr ")" | attr "eq" literal.
// `and` binds tighter than `or`.

private val SUPPORTED_ATTRIBUTES = setOf("userName", "externalId", "displayName", "id", "active", "value", "type")
private val KEYWORDS = setOf("and", "or", "eq", "true", "false")

// Bounds recursion depth against pathological nesting (e.g. thousands of "("); not a grammar rule.
private const val MAX_NESTING_DEPTH = 32

sealed interface ScimFilter {
    data class Eq(
        val attr: String,
        val value: ScimValue,
    ) : ScimFilter

    data class And(
        val left: ScimFilter,
        val right: ScimFilter,
    ) : ScimFilter

    data class Or(
        val left: ScimFilter,
        val right: ScimFilter,
    ) : ScimFilter

    /** Evaluates this filter against one element of a multi-valued attribute. */
    fun matches(value: ScimValue): Boolean =
        when (this) {
            is Eq -> resolveAttribute(attr, value) == this.value
            is And -> left.matches(value) && right.matches(value)
            is Or -> left.matches(value) || right.matches(value)
        }
}

private fun resolveAttribute(
    attr: String,
    value: ScimValue,
): ScimValue? = (value as? ScimValue.Complex)?.attributes?.get(attr)

/** Parses an RFC 7644 §3.4.2.2 filter. Never throws; malformed input is a failure value. */
fun parseFilter(raw: String): Result<ScimFilter> =
    try {
        if (raw.isBlank()) {
            throw FilterSyntaxException("empty filter")
        }
        Result.success(FilterParser(raw, tokenize(raw)).parseToEnd())
    } catch (e: FilterSyntaxException) {
        Result.failure(ScimFailure(ScimErrorType.invalidFilter, e.message ?: "invalid filter: \"$raw\""))
    }

/** Internal-only: carries a human-readable parse failure up to [parseFilter]. */
private class FilterSyntaxException(
    message: String,
) : Exception(message)

private sealed interface Token {
    val position: Int

    data class Ident(
        val text: String,
        override val position: Int,
    ) : Token

    data class Str(
        val text: String,
        override val position: Int,
    ) : Token

    data class Num(
        val text: String,
        override val position: Int,
    ) : Token

    data class LParen(
        override val position: Int,
    ) : Token

    data class RParen(
        override val position: Int,
    ) : Token

    data class End(
        override val position: Int,
    ) : Token
}

private fun tokenize(raw: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        when {
            c.isWhitespace() -> i++
            c == '(' -> {
                tokens += Token.LParen(i)
                i++
            }
            c == ')' -> {
                tokens += Token.RParen(i)
                i++
            }
            c == '"' -> {
                val start = i
                i++
                val text = StringBuilder()
                var closed = false
                while (i < raw.length) {
                    val ch = raw[i]
                    if (ch == '"') {
                        closed = true
                        i++
                        break
                    }
                    if (ch == '\\' && i + 1 < raw.length) {
                        text.append(raw[i + 1])
                        i += 2
                        continue
                    }
                    text.append(ch)
                    i++
                }
                if (!closed) {
                    throw FilterSyntaxException(
                        "unterminated string starting at position $start in \"$raw\"",
                    )
                }
                tokens += Token.Str(text.toString(), start)
            }
            c.isDigit() -> {
                val start = i
                while (i < raw.length && raw[i].isDigit()) i++
                tokens += Token.Num(raw.substring(start, i), start)
            }
            c.isLetter() -> {
                val start = i
                while (i < raw.length && (raw[i].isLetterOrDigit() || raw[i] == '_')) i++
                tokens += Token.Ident(raw.substring(start, i), start)
            }
            else -> throw FilterSyntaxException("unexpected character '$c' at position $i in \"$raw\"")
        }
    }
    tokens += Token.End(raw.length)
    return tokens
}

private class FilterParser(
    private val raw: String,
    private val tokens: List<Token>,
) {
    private var pos = 0
    private var depth = 0

    fun parseToEnd(): ScimFilter {
        val result = parseOr()
        val trailing = peek()
        if (trailing !is Token.End) {
            fail("unexpected trailing input", trailing.position)
        }
        return result
    }

    private fun parseOr(): ScimFilter {
        var left = parseAnd()
        while (isKeyword("or")) {
            advance()
            left = ScimFilter.Or(left, parseAnd())
        }
        return left
    }

    private fun parseAnd(): ScimFilter {
        var left = parsePrimary()
        while (isKeyword("and")) {
            advance()
            left = ScimFilter.And(left, parsePrimary())
        }
        return left
    }

    private fun parsePrimary(): ScimFilter {
        if (peek() is Token.LParen) {
            val open = advance()
            depth++
            if (depth > MAX_NESTING_DEPTH) {
                fail("filter nesting exceeds maximum depth of $MAX_NESTING_DEPTH", open.position)
            }
            val inner = parseOr()
            val close = peek()
            if (close !is Token.RParen) {
                fail("expected ')'", close.position)
            }
            advance()
            depth--
            return inner
        }
        return parseComparison()
    }

    private fun parseComparison(): ScimFilter {
        val attrToken = expectIdent("attribute name")
        if (attrToken.text in KEYWORDS) {
            fail("expected attribute name, found reserved word '${attrToken.text}'", attrToken.position)
        }
        if (attrToken.text !in SUPPORTED_ATTRIBUTES) {
            fail("unsupported attribute '${attrToken.text}'", attrToken.position)
        }
        val opToken = expectIdent("operator")
        if (opToken.text != "eq") {
            fail("unsupported operator '${opToken.text}'", opToken.position)
        }
        return ScimFilter.Eq(attrToken.text, parseLiteral())
    }

    private fun parseLiteral(): ScimValue {
        val token = advance()
        return when (token) {
            is Token.Str -> ScimValue.Str(token.text)
            is Token.Num -> ScimValue.Num(token.text.toLong())
            is Token.Ident ->
                when (token.text) {
                    "true" -> ScimValue.Bool(true)
                    "false" -> ScimValue.Bool(false)
                    else -> fail("expected literal, found '${token.text}'", token.position)
                }
            else -> fail("expected literal", token.position)
        }
    }

    private fun expectIdent(expected: String): Token.Ident {
        val token = advance()
        if (token !is Token.Ident) {
            fail("expected $expected", token.position)
        }
        return token
    }

    private fun isKeyword(word: String): Boolean = peek().let { it is Token.Ident && it.text == word }

    private fun peek(): Token = tokens[pos]

    private fun advance(): Token = tokens[pos++]

    private fun fail(
        message: String,
        position: Int,
    ): Nothing {
        val clamped = position.coerceIn(0, raw.length)
        val fragment = if (clamped < raw.length) raw.substring(clamped) else "<end of input>"
        throw FilterSyntaxException("$message at position $clamped: '$fragment' in \"$raw\"")
    }
}

package io.github.brunoccst.anothermacro

/**
 * The small expression language used in macro files.
 *
 *   numbers 1, 0.5   strings 'text'   true false
 *   names: params and variables (battles, mode, ...), stats.<name>
 *   + - * /   == != > >= < <=   and or not   ( )
 *   functions provided by the caller, e.g. found('battle_attack_button'), now()
 *
 * `and` / `or` short-circuit, so `walked and view_moved()` only checks the view
 * after a walk.
 */
class Expr(private val src: String) {
    interface Env {
        fun variable(name: String): Any?
        fun call(name: String, args: List<Any?>): Any?
    }

    private val tokens = tokenize(src)
    private var i = 0
    private lateinit var env: Env

    fun eval(env: Env): Any? {
        this.env = env
        i = 0
        val v = or(true)
        if (i < tokens.size) error("unexpected '${tokens[i]}'")
        return v
    }

    // each level takes `live`: false while skipping a short-circuited branch (parse only)
    private fun or(live: Boolean): Any? {
        var v = and(live)
        while (peek() == "or") {
            i++
            val l = live && !truthy(v)
            val r = and(l)
            if (live && !truthy(v)) v = r
        }
        return v
    }

    private fun and(live: Boolean): Any? {
        var v = not(live)
        while (peek() == "and") {
            i++
            val l = live && truthy(v)
            val r = not(l)
            if (live && truthy(v)) v = r
        }
        return v
    }

    private fun not(live: Boolean): Any? {
        if (peek() == "not") { i++; return !truthy(not(live)) }
        return cmp(live)
    }

    private fun cmp(live: Boolean): Any? {
        val a = add(live)
        val op = peek()
        if (op in setOf("==", "!=", ">", ">=", "<", "<=")) {
            i++
            val b = add(live)
            if (!live) return false
            return when (op) {
                "==" -> eq(a, b)
                "!=" -> !eq(a, b)
                ">" -> num(a) > num(b)
                ">=" -> num(a) >= num(b)
                "<" -> num(a) < num(b)
                else -> num(a) <= num(b)
            }
        }
        return a
    }

    private fun add(live: Boolean): Any? {
        var v = mul(live)
        while (peek() == "+" || peek() == "-") {
            val op = tokens[i++]
            val r = mul(live)
            if (live) v = if (op == "+") (if (v is String || r is String) "${str(v)}${str(r)}" else num(v) + num(r)) else num(v) - num(r)
        }
        return v
    }

    private fun mul(live: Boolean): Any? {
        var v = unary(live)
        while (peek() == "*" || peek() == "/") {
            val op = tokens[i++]
            val r = unary(live)
            if (live) v = if (op == "*") num(v) * num(r) else num(v) / num(r)
        }
        return v
    }

    private fun unary(live: Boolean): Any? {
        if (peek() == "-") { i++; val v = unary(live); return if (live) -num(v) else 0.0 }
        return primary(live)
    }

    private fun primary(live: Boolean): Any? {
        val t = tokens.getOrNull(i++) ?: error("expression ends too early")
        return when {
            t == "(" -> or(live).also { expect(")") }
            t.startsWith("'") -> t.substring(1, t.length - 1)
            t == "true" -> true
            t == "false" -> false
            t[0].isDigit() || t[0] == '.' -> t.toDouble()
            t[0].isLetter() || t[0] == '_' -> if (peek() == "(") {
                i++
                val args = mutableListOf<Any?>()
                if (peek() != ")") {
                    args += or(live)
                    while (peek() == ",") { i++; args += or(live) }
                }
                expect(")")
                if (live) env.call(t, args) else null
            } else if (live) env.variable(t) else null
            else -> error("unexpected '$t'")
        }
    }

    private fun peek() = tokens.getOrNull(i)
    private fun expect(t: String) { if (tokens.getOrNull(i++) != t) error("expected '$t'") }
    private fun error(msg: String): Nothing = throw IllegalArgumentException("in expression \"$src\": $msg")

    companion object {
        private val cache = HashMap<String, Expr>()

        fun eval(src: String, env: Env): Any? = synchronized(cache) { cache.getOrPut(src) { Expr(src) } }.eval(env)

        fun truthy(v: Any?): Boolean = when (v) {
            null -> false
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> v.isNotEmpty() && v != "false"
            else -> true
        }

        fun num(v: Any?): Double = when (v) {
            is Number -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            is String -> v.trim().toDoubleOrNull() ?: throw IllegalArgumentException("'$v' is not a number")
            null -> 0.0
            else -> throw IllegalArgumentException("$v is not a number")
        }

        fun str(v: Any?): String = when (v) {
            is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
            null -> ""
            else -> v.toString()
        }

        private fun eq(a: Any?, b: Any?): Boolean =
            if ((a is Number || a is Boolean) && (b is Number || b is Boolean)) num(a) == num(b) else str(a) == str(b)

        private fun tokenize(s: String): List<String> {
            val out = ArrayList<String>()
            var k = 0
            while (k < s.length) {
                val c = s[k]
                when {
                    c.isWhitespace() -> k++
                    c == '\'' || c == '"' -> {
                        val end = s.indexOf(c, k + 1)
                        require(end > 0) { "unterminated string in \"$s\"" }
                        out += "'" + s.substring(k + 1, end) + "'"
                        k = end + 1
                    }
                    c.isDigit() || (c == '.' && k + 1 < s.length && s[k + 1].isDigit()) -> {
                        val st = k
                        while (k < s.length && (s[k].isDigit() || s[k] == '.')) k++
                        out += s.substring(st, k)
                    }
                    c.isLetter() || c == '_' -> {
                        val st = k
                        while (k < s.length && (s[k].isLetterOrDigit() || s[k] == '_' || s[k] == '.')) k++
                        out += s.substring(st, k)
                    }
                    else -> {
                        val two = if (k + 1 < s.length) s.substring(k, k + 2) else ""
                        if (two in setOf("==", "!=", ">=", "<=")) { out += two; k += 2 } else { out += c.toString(); k++ }
                    }
                }
            }
            return out
        }
    }
}

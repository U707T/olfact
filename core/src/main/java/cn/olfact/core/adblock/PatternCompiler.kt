package cn.olfact.core.adblock

/**
 * ABP / uBO 的 pattern 语法 → 正则。
 *
 * 支持的语法：
 * - `||example.com^` 域名锚点 + 分隔符
 * - `|http://a` / `abc|` 首尾锚点
 * - `*` 任意串，`^` 分隔符（非 `[a-z0-9_.%-]` 或串尾）
 * - `/regex/` 原生正则（调用方传入时已去掉斜杠）
 */
internal object PatternCompiler {

    fun compile(pattern: String, matchCase: Boolean): Regex {
        val sb = StringBuilder(pattern.length * 2 + 32)
        var p = pattern

        if (p.startsWith("||")) {
            p = p.substring(2)
            // 协议 + 可选 userinfo + 可选子域；host 结束处由 pattern 自己保证边界
            sb.append("^[a-z][a-z0-9+.\\-]*://(?:[^/?#@]*@)?(?:[^/?#:]*\\.)?")
        } else if (p.startsWith("|")) {
            p = p.substring(1)
            sb.append('^')
        }

        var i = 0
        while (i < p.length) {
            when (val c = p[i]) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^a-z0-9_.%\\-]|$)")
                '|' -> if (i == p.length - 1) sb.append('$') else sb.append("\\|")
                else -> appendLiteral(sb, c)
            }
            i++
        }

        val options = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(sb.toString(), options)
    }

    /** `/^https?:\\/\\/ad\\./` → 正则本体 */
    fun compileRawRegex(body: String, matchCase: Boolean): Regex {
        val options = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(body, options)
    }

    private fun appendLiteral(sb: StringBuilder, c: Char) {
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') {
            sb.append(c)
            return
        }
        when (c) {
            '\\', '.', '+', '?', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
    }
}

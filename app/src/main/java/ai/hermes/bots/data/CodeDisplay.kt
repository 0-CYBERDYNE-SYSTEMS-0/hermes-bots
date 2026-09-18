package ai.hermes.bots.data

/**
 * Pure display helpers for fenced code blocks (agent-ux-p0-spec.md §2–3). Both halves of the
 * fence the renderer used to throw away: the info string becomes a language chip label, and
 * the body gets lightweight highlighting spans. No compose types here — the UI maps spans to
 * styles — so everything is JVM-unit-testable.
 */
object CodeLanguages {

    /** Canonical display name for a fence info string ("```kotlin"), null when unknown. */
    fun label(info: String): String? {
        val token = info.trim().substringBefore(' ').substringBefore(':').lowercase()
        return aliases[token]
    }

    private val aliases = mapOf(
        "kotlin" to "Kotlin", "kt" to "Kotlin", "kts" to "Kotlin",
        "java" to "Java",
        "python" to "Python", "py" to "Python", "py3" to "Python",
        "javascript" to "JavaScript", "js" to "JavaScript", "jsx" to "JavaScript",
        "typescript" to "TypeScript", "ts" to "TypeScript", "tsx" to "TypeScript",
        "bash" to "Bash", "sh" to "Bash", "shell" to "Bash", "zsh" to "Bash", "console" to "Bash",
        "go" to "Go", "golang" to "Go",
        "rust" to "Rust", "rs" to "Rust",
        "c" to "C", "cpp" to "C++", "c++" to "C++", "h" to "C", "hpp" to "C++",
        "csharp" to "C#", "cs" to "C#", "c#" to "C#",
        "swift" to "Swift",
        "sql" to "SQL",
        "json" to "JSON",
        "yaml" to "YAML", "yml" to "YAML",
        "toml" to "TOML",
        "ruby" to "Ruby", "rb" to "Ruby",
        "groovy" to "Groovy", "gradle" to "Groovy",
        "diff" to "Diff", "patch" to "Diff",
    )
}

/** One highlighted region of a code body: [start, end) over the raw string. */
data class CodeSpan(val start: Int, val end: Int, val kind: CodeTokenKind)

enum class CodeTokenKind { KEYWORD, STRING, COMMENT, NUMBER }

/**
 * Agent-ux-p0-spec.md §3: a deliberately small tokenizer — keywords, strings, comments,
 * numbers is the 90% of perceived quality. Single left-to-right scan with string/comment
 * states, so multi-line block comments and strings work; escape sequences don't end a
 * string. Unknown languages still get strings/comments/numbers (family-inferred comments).
 * Decoration only: a mis-scanned span can never change the text itself.
 */
object CodeTokenizer {

    /** Bodies above this render plain — highlighting is decoration, never a perf risk. */
    const val MAX_TOKENIZE_CHARS = 20_000

    fun tokenize(code: String, language: String?): List<CodeSpan> {
        if (code.length > MAX_TOKENIZE_CHARS || code.isEmpty()) return emptyList()
        val lang = CodeLanguages.label(language ?: "")?.lowercase()
        val keywords = keywordsFor(lang)
        val hashComments = lang in HASH_COMMENT_LANGS
        val spans = mutableListOf<CodeSpan>()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            val two = if (i + 1 < code.length) code.substring(i, i + 2) else ""
            when {
                two == "/*" && lang != "sql" -> {
                    val end = code.indexOf("*/", i + 2)
                    val stop = if (end < 0) code.length else end + 2
                    spans += CodeSpan(i, stop, CodeTokenKind.COMMENT)
                    i = stop
                }
                two == "//" -> {
                    val stop = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    spans += CodeSpan(i, stop, CodeTokenKind.COMMENT)
                    i = stop
                }
                c == '#' && hashComments -> {
                    val stop = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    spans += CodeSpan(i, stop, CodeTokenKind.COMMENT)
                    i = stop
                }
                two == "--" && lang == "sql" -> {
                    val stop = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    spans += CodeSpan(i, stop, CodeTokenKind.COMMENT)
                    i = stop
                }
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < code.length) {
                        if (code[j] == '\\' && c != '`') j += 2 // escape never ends the string
                        else if (code[j] == c || code[j] == '\n') break // strings may span lines
                        else j++
                    }
                    val stop = if (j < code.length && code[j] == c) j + 1 else j.coerceAtMost(code.length)
                    spans += CodeSpan(i, stop, CodeTokenKind.STRING)
                    i = stop
                }
                c.isDigit() -> {
                    var j = i
                    while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_' ||
                            (code[j] == '.' && j + 1 < code.length && code[j + 1].isDigit()))
                    ) j++
                    spans += CodeSpan(i, j, CodeTokenKind.NUMBER)
                    i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    if (keywords != null) {
                        val word = code.substring(i, j)
                        // SQL keywords are case-insensitive; every other family matches as written.
                        val hit = word in keywords || (lang == "sql" && word.lowercase() in keywords)
                        if (hit) spans += CodeSpan(i, j, CodeTokenKind.KEYWORD)
                    }
                    i = j
                }
                else -> i++
            }
        }
        return spans
    }

    private val HASH_COMMENT_LANGS = setOf("python", "bash", "yaml", "toml", "ruby", "shell")

    /** SQL line comments start with "--"; handled by the double-dash arm above. */
    private fun keywordsFor(lang: String?): Set<String>? = when (lang) {
        "kotlin" -> setOf(
            "as", "break", "by", "catch", "class", "companion", "const", "constructor", "continue",
            "crossinline", "data", "do", "dynamic", "else", "enum", "expect", "external", "false",
            "final", "finally", "for", "fun", "get", "if", "import", "in", "infix", "init",
            "inline", "inner", "interface", "internal", "is", "lateinit", "lazy", "null",
            "object", "open", "operator", "out", "override", "package", "private", "protected",
            "public", "reified", "return", "sealed", "set", "suspend", "this", "throw", "true",
            "try", "typealias", "val", "var", "vararg", "when", "where", "while",
        )
        "java" -> setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "null", "package", "private", "protected",
            "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "true", "false", "try", "void", "volatile",
            "while", "record", "var", "yield",
        )
        "python" -> setOf(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
            "elif", "else", "except", "false", "finally", "for", "from", "global", "if",
            "import", "in", "is", "lambda", "none", "nonlocal", "not", "or", "pass", "raise",
            "return", "true", "try", "while", "with", "yield", "self",
        )
        "javascript", "typescript" -> setOf(
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally",
            "for", "function", "if", "implements", "import", "in", "instanceof", "interface",
            "let", "new", "null", "of", "private", "protected", "public", "return", "static",
            "super", "switch", "this", "throw", "true", "try", "type", "typeof", "undefined",
            "var", "void", "while", "yield",
        )
        "bash" -> setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case",
            "esac", "function", "in", "return", "exit", "local", "export", "readonly", "echo",
            "cd", "set", "source", "trap",
        )
        "go" -> setOf(
            "break", "case", "chan", "const", "continue", "default", "defer", "else",
            "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map",
            "package", "range", "return", "select", "struct", "switch", "type", "var", "nil",
            "true", "false",
        )
        "rust" -> setOf(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
            "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
            "move", "mut", "pub", "ref", "return", "self", "static", "struct", "super", "trait",
            "true", "type", "unsafe", "use", "where", "while",
        )
        "c", "c++" -> setOf(
            "auto", "break", "case", "catch", "char", "class", "const", "continue", "default",
            "delete", "do", "double", "else", "enum", "explicit", "export", "extern", "false",
            "float", "for", "friend", "goto", "if", "inline", "int", "long", "mutable",
            "namespace", "new", "nullptr", "operator", "private", "protected", "public",
            "return", "short", "signed", "sizeof", "static", "struct", "switch", "template",
            "this", "throw", "true", "try", "typedef", "typename", "union", "unsigned",
            "using", "virtual", "void", "volatile", "while",
        )
        "c#" -> setOf(
            "abstract", "as", "async", "await", "base", "bool", "break", "byte", "case", "catch",
            "char", "checked", "class", "const", "continue", "decimal", "default", "delegate",
            "do", "double", "else", "enum", "event", "explicit", "extern", "false", "finally",
            "fixed", "float", "for", "foreach", "get", "goto", "if", "implicit", "in", "int",
            "interface", "internal", "is", "lock", "long", "namespace", "new", "null", "object",
            "operator", "out", "override", "params", "private", "protected", "public", "readonly",
            "ref", "return", "sealed", "set", "short", "sizeof", "static", "string", "struct",
            "switch", "this", "throw", "true", "try", "typeof", "uint", "ulong", "unchecked",
            "unsafe", "ushort", "using", "var", "virtual", "void", "volatile", "while", "yield",
        )
        "sql" -> setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
            "create", "table", "alter", "drop", "index", "view", "join", "inner", "left",
            "right", "outer", "full", "on", "group", "by", "order", "having", "limit", "offset",
            "union", "all", "distinct", "and", "or", "not", "null", "is", "in", "as", "asc",
            "desc", "primary", "key", "foreign", "references", "default", "unique", "with",
            "case", "when", "then", "else", "end", "begin", "commit", "rollback", "exists",
            "between", "like",
        )
        else -> null
    }
}

package com.lithic.gallery.core.text

import java.text.Normalizer

/**
 * bert-base-uncased's WordPiece tokenizer, as HuggingFace's `tokenizers` library runs it for
 * BLIP and for all-MiniLM-L6-v2: BertNormalizer (clean text, pad CJK characters, strip accents,
 * lowercase), BertPreTokenizer (split on whitespace and punctuation), WordPiece, and the
 * `[CLS] … [SEP]` template. [decode] is its WordPiece decoder with cleanup, which is how BLIP's
 * generated ids become a caption.
 *
 * @param vocab the vocabulary file's lines; a token's id is its line number.
 */
class BertTokenizer(vocab: List<String>) {
    private val idOf: Map<String, Int> = HashMap<String, Int>(vocab.size * 2).apply {
        vocab.forEachIndexed { i, t -> putIfAbsent(t, i) }
    }
    private val tokens: Array<String> = vocab.toTypedArray()

    val padId = id("[PAD]")
    val unkId = id("[UNK]")
    val clsId = id("[CLS]")
    val sepId = id("[SEP]")
    val maskId = id("[MASK]")
    private val specialIds = setOf(padId, unkId, clsId, sepId, maskId)

    private fun id(token: String) = idOf[token] ?: error("vocabulary has no $token")

    /**
     * The ids a model is given for [text]: `[CLS]`, the word pieces, `[SEP]`, keeping only the
     * first word pieces if there are more than [maxLength] ids in all.
     */
    fun encode(text: String, maxLength: Int): IntArray {
        require(maxLength >= 2) { "maxLength $maxLength leaves no room for [CLS] and [SEP]" }
        val pieces = ArrayList<Int>()
        // Special tokens written out in the text count as those tokens; they are matched in the
        // raw text, before normalizing, and the text between them is tokenized normally.
        var from = 0
        while (from <= text.length && pieces.size < maxLength - 2) {
            val (at, special) = nextSpecial(text, from)
            for (word in preTokenize(normalize(text.substring(from, at)))) {
                wordPiece(word, pieces)
                if (pieces.size >= maxLength - 2) break
            }
            if (special == null) break
            pieces.add(idOf.getValue(special))
            from = at + special.length
        }
        val n = minOf(pieces.size, maxLength - 2)
        val out = IntArray(n + 2)
        out[0] = clsId
        for (i in 0 until n) out[i + 1] = pieces[i]
        out[n + 1] = sepId
        return out
    }

    /**
     * The text for generated [ids], as thumbsup2 gets it: `decode(ids, skip_special_tokens=True)`
     * then `strip()`. Special tokens and ids outside the vocabulary (BLIP's `[DEC]` start token)
     * are dropped; a `##` piece joins the word before it; `.`, `?`, `!` and `,` join the word
     * before them.
     */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        var first = true
        for (id in ids) {
            if (id < 0 || id >= tokens.size || id in specialIds) continue
            var token = tokens[id]
            if (!first) {
                token = if (token.startsWith("##")) token.substring(2) else " $token"
            }
            first = false
            sb.append(cleanup(token))
        }
        return sb.toString().trim()
    }

    /** The first special token written in [text] at or after [from] (leftmost, then longest), or (end, null). */
    private fun nextSpecial(text: String, from: Int): Pair<Int, String?> {
        var best = text.length
        var token: String? = null
        for (s in SPECIAL_TOKENS) {
            val i = text.indexOf(s, from)
            if (i >= 0 && (i < best || (i == best && s.length > (token?.length ?: 0)))) { best = i; token = s }
        }
        return best to token
    }

    /** The decoder's per-token cleanup (tokenizers' `decoders::wordpiece::cleanup`). */
    private fun cleanup(token: String): String = token
        .replace(" .", ".")
        .replace(" ?", "?")
        .replace(" !", "!")
        .replace(" ,", ",")
        .replace(" ' ", "'")
        .replace(" n't", "n't")
        .replace(" 'm", "'m")
        .replace(" do not", " don't")
        .replace(" 's", "'s")
        .replace(" 've", "'ve")
        .replace(" 're", "'re")

    /** BertNormalizer with clean_text, handle_chinese_chars, strip_accents and lowercase on. */
    internal fun normalize(text: String): String {
        val cleaned = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            i += Character.charCount(c)
            if (c == 0 || c == 0xFFFD || isControl(c)) continue
            when {
                isWhitespace(c) -> cleaned.append(' ')
                isChinese(c) -> cleaned.append(' ').appendCodePoint(c).append(' ')
                else -> cleaned.appendCodePoint(c)
            }
        }
        val decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        i = 0
        while (i < decomposed.length) {
            val c = decomposed.codePointAt(i)
            i += Character.charCount(c)
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            // Rust's char::to_lowercase: the full mapping, which differs from Java's
            // per-character one only for İ (no final-sigma rule either way).
            if (c == 0x130) out.append("i̇") else out.appendCodePoint(Character.toLowerCase(c))
        }
        return out.toString()
    }

    /** BertPreTokenizer: words split on whitespace, each punctuation character a word of its own. */
    internal fun preTokenize(text: String): List<String> {
        val words = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            i += Character.charCount(c)
            when {
                isWhitespace(c) -> if (cur.isNotEmpty()) { words.add(cur.toString()); cur.setLength(0) }
                isPunctuation(c) -> {
                    if (cur.isNotEmpty()) { words.add(cur.toString()); cur.setLength(0) }
                    words.add(String(Character.toChars(c)))
                }
                else -> cur.appendCodePoint(c)
            }
        }
        if (cur.isNotEmpty()) words.add(cur.toString())
        return words
    }

    /** Appends [word]'s word pieces to [out]: greedy longest match, or `[UNK]` for all of it. */
    internal fun wordPiece(word: String, out: MutableList<Int>) {
        val chars = word.codePointCount(0, word.length)
        if (chars > MAX_WORD_CHARS) { out.add(unkId); return }
        val pieces = ArrayList<Int>()
        var start = 0 // a UTF-16 index, always on a code point boundary
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (end > start) {
                val sub = word.substring(start, end)
                val candidate = if (start > 0) "##$sub" else sub
                val id = idOf[candidate]
                if (id != null) { found = id; break }
                end = word.offsetByCodePoints(end, -1)
            }
            if (found < 0) { out.add(unkId); return }
            pieces.add(found)
            start = end
        }
        out.addAll(pieces)
    }

    companion object {
        private const val MAX_WORD_CHARS = 100
        private val SPECIAL_TOKENS = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")

        /** Rust's `char::is_whitespace`: the Unicode White_Space property. */
        internal fun isWhitespace(c: Int): Boolean = when (c) {
            in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
            else -> false
        }

        /** Unicode category C* (control, format, unassigned, private use, surrogate), except \t\n\r. */
        internal fun isControl(c: Int): Boolean {
            if (c == '\t'.code || c == '\n'.code || c == '\r'.code) return false
            return when (Character.getType(c).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.UNASSIGNED,
                Character.PRIVATE_USE, Character.SURROGATE -> true
                else -> false
            }
        }

        /** ASCII punctuation, or Unicode category P*. */
        internal fun isPunctuation(c: Int): Boolean {
            if (c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126) return true
            return when (Character.getType(c).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION -> true
                else -> false
            }
        }

        /** The CJK ideograph blocks BERT pads with spaces. */
        internal fun isChinese(c: Int): Boolean =
            c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x2A6DF ||
                c in 0x2A700..0x2B73F || c in 0x2B740..0x2B81F || c in 0x2B820..0x2CEAF ||
                c in 0xF900..0xFAFF || c in 0x2F800..0x2FA1F
    }
}

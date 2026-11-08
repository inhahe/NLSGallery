package com.lithic.gallery.core.ai

/**
 * The text thumbsup2's `ai_describe.py` embeds for an image:
 * `" ".join(filter(None, [caption.strip(), ocr.strip()]))`, or null when both are empty (then it
 * stores no embedding).
 */
fun embeddingText(caption: String, ocr: String): String? =
    listOf(caption.trim(), ocr.trim()).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }

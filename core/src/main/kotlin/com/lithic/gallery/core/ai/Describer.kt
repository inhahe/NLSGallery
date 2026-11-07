package com.lithic.gallery.core.ai

import com.lithic.gallery.core.ai.easyocr.EasyOcr
import com.lithic.gallery.core.image.RgbImage

/** What thumbsup2 records about an image: its caption, the text in it, and the search vector. */
class Description(
    val caption: String,
    val ocr: String,
    /** The MiniLM embedding of caption + OCR text, or null when both are empty. */
    val embedding: FloatArray?,
)

/**
 * thumbsup2's `ai_describe.py` worker: caption, OCR, and an embedding of
 * `" ".join(filter(None, [caption.strip(), ocr.strip()]))`.
 */
class Describer(
    private val captioner: BlipCaptioner?,
    private val ocr: EasyOcr?,
    private val embedder: MiniLmEmbedder?,
) {
    fun describe(img: RgbImage): Description {
        val caption = captioner?.caption(img)?.trim() ?: ""
        val text = ocr?.text(img)?.trim() ?: ""
        return Description(caption, text, embed(caption, text))
    }

    /** The embedding thumbsup2 makes from a caption and OCR text, or null if there is nothing to embed. */
    fun embed(caption: String, text: String): FloatArray? {
        val joined = listOf(caption.trim(), text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        return if (joined.isEmpty() || embedder == null) null else embedder.embed(joined)
    }
}

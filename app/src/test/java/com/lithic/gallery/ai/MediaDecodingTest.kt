package com.lithic.gallery.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaDecodingTest {
    @Test
    fun xmpDescriptionThenTitleThenLabel() {
        val alt = """<x:xmpmeta><rdf:RDF><rdf:Description>
            <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Title here</rdf:li></rdf:Alt></dc:title>
            <dc:description><rdf:Alt><rdf:li xml:lang="x-default">Grandma &amp; the cake</rdf:li></rdf:Alt></dc:description>
            </rdf:Description></rdf:RDF></x:xmpmeta>"""
        assertThat(MediaDecoding.xmpCaption(alt)).isEqualTo("Grandma & the cake")
        val titleOnly = """<dc:title><rdf:Alt><rdf:li xml:lang="x-default">Only a title</rdf:li></rdf:Alt></dc:title>"""
        assertThat(MediaDecoding.xmpCaption(titleOnly)).isEqualTo("Only a title")
        val attribute = """<rdf:Description xmp:Label="Red" />"""
        assertThat(MediaDecoding.xmpCaption(attribute)).isEqualTo("Red")
        assertThat(MediaDecoding.xmpCaption("<x:xmpmeta/>")).isNull()
        val empty = """<dc:description><rdf:Alt><rdf:li xml:lang="x-default"></rdf:li></rdf:Alt></dc:description>"""
        assertThat(MediaDecoding.xmpCaption(empty)).isNull()
    }

    @Test
    fun exifTextIsUtf8ElseLatin1() {
        assertThat(MediaDecoding.exifText("Café 東京".toByteArray() + byteArrayOf(0, 0))).isEqualTo("Café 東京")
        // Not valid UTF-8: an old camera's Latin-1.
        assertThat(MediaDecoding.exifText(byteArrayOf('C'.code.toByte(), 0xE9.toByte(), 0))).isEqualTo("Cé")
        assertThat(MediaDecoding.exifText(byteArrayOf(0))).isEqualTo("")
        // A fixed-size field: the string ends at its NUL, whatever fills the rest.
        assertThat(MediaDecoding.exifText("OLYMPUS DIGITAL CAMERA".toByteArray() + byteArrayOf(0, 32, 32, 0x41)))
            .isEqualTo("OLYMPUS DIGITAL CAMERA")
        assertThat(MediaDecoding.exifText(null)).isNull()
    }

    @Test
    fun xmlReferencesAreDecodedOnce() {
        assertThat(MediaDecoding.unescapeXml("&amp;lt;b&amp;gt; &lt;i&gt; &#xA9; &#10;x &quot;q&quot; &apos;")).isEqualTo("&lt;b&gt; <i> © \nx \"q\" '")
        assertThat(MediaDecoding.unescapeXml("plain & simple &unknown;")).isEqualTo("plain & simple &unknown;")
    }
}

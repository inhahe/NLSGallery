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
    fun xmlReferencesAreDecodedOnce() {
        assertThat(MediaDecoding.unescapeXml("&amp;lt;b&amp;gt; &lt;i&gt; &#xA9; &#10;x &quot;q&quot; &apos;")).isEqualTo("&lt;b&gt; <i> © \nx \"q\" '")
        assertThat(MediaDecoding.unescapeXml("plain & simple &unknown;")).isEqualTo("plain & simple &unknown;")
    }
}

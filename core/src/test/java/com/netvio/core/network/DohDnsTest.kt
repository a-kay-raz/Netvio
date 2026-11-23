package com.netvio.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DohDnsTest {

    @Test
    fun parseDnsJson_extractsIpv4ARecordsWithoutReflection() {
        val payload = """
            {
              "Status": 0,
              "Answer": [
                { "name": "image.tmdb.org.", "type": 5, "TTL": 300, "data": "tmdb.cdn.net." },
                { "name": "tmdb.cdn.net.", "type": 1, "TTL": 300, "data": "104.16.61.155" },
                { "name": "tmdb.cdn.net.", "type": 1, "TTL": 300, "data": "104.16.62.155" }
              ]
            }
        """.trimIndent()

        val addresses = DohDns().parseDnsJson(payload, "image.tmdb.org")
        assertEquals(2, addresses.size)
        assertEquals("image.tmdb.org", addresses[0].hostName)
        assertEquals("104.16.61.155", addresses[0].hostAddress)
        assertEquals("104.16.62.155", addresses[1].hostAddress)
    }

    @Test
    fun parseDnsJson_returnsEmptyOnNonZeroStatusOrMalformedPayload() {
        val nxdomain = """{"Status":3,"Answer":[]}"""
        assertTrue(DohDns().parseDnsJson(nxdomain, "missing.invalid").isEmpty())
        assertTrue(DohDns().parseDnsJson("not-json", "missing.invalid").isEmpty())
    }
}

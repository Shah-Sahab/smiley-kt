package com.timgroup.smileykt

import com.timgroup.eventstore.api.StreamId.streamId
import com.timgroup.smileykt.events.EventCodecs
import com.timgroup.smileykt.events.HappinessReceived
import org.apache.http.HttpStatus
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.message.BasicNameValuePair
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.stream.Collectors.toList
import kotlin.test.assertEquals

class RecordHappinessResourcesIntegrationTest {
    @get:Rule
    val server = ServerRule()

    @Test
    fun `gets empty happiness`() {
        server.execute(HttpGet("/happiness")).apply {
            assertEquals(HttpStatus.SC_OK, statusLine.statusCode)
            assertEquals("", entity.readText())
        }
    }

    @Test
    fun `gets happiness`() {
        server.eventSource.writeStream().write(streamId("happiness", "test@example.com"), listOf(
                EventCodecs.serializeEvent(HappinessReceived("test@example.com", LocalDate.parse("2017-12-08"), Emotion.HAPPY))
        ))
        server.execute(HttpGet("/happiness")).apply {
            assertEquals(HttpStatus.SC_OK, statusLine.statusCode)
            assertEquals("2017-12-08 test@example.com HAPPY\n", entity.readText())
        }
    }

    @Test
    fun `aggregates happiness of single user`() {
        server.eventSource.writeStream().write(streamId("happiness", "test@example.com"), listOf(
                EventCodecs.serializeEvent(HappinessReceived("test@example.com", LocalDate.parse("2017-12-08"), Emotion.HAPPY)),
                EventCodecs.serializeEvent(HappinessReceived("test@example.com", LocalDate.parse("2017-12-08"), Emotion.SAD))
        ))
        server.eventSource.writeStream().write(streamId("happiness", "zzzz@example.com"), listOf(
                EventCodecs.serializeEvent(HappinessReceived("zzzz@example.com", LocalDate.parse("2017-12-08"), Emotion.HAPPY))
        ))
        server.execute(HttpGet("/happiness")).apply {
            assertEquals(HttpStatus.SC_OK, statusLine.statusCode)
            assertEquals("2017-12-08 test@example.com SAD\n2017-12-08 zzzz@example.com HAPPY", entity.readText()!!.sortLines())
        }
    }

    @Test
    fun `uses correct date`() {
        server.eventSource.writeStream().write(streamId("happiness", "zzzz@example.com"), listOf(
                EventCodecs.serializeEvent(HappinessReceived("zzzz@example.com", LocalDate.parse("2017-12-08"), Emotion.HAPPY))
        ))

        server.clock.advanceTo(Instant.parse("2017-12-10T00:00:00Z"))

        server.eventSource.writeStream().write(streamId("happiness", "aaaa@example.com"), listOf(
                EventCodecs.serializeEvent(HappinessReceived("aaaa@example.com", LocalDate.parse("2017-12-10"), Emotion.SUICIDAL))
        ))

        server.execute(HttpGet("/happiness")).apply {
            assertEquals(HttpStatus.SC_OK, statusLine.statusCode)
            assertEquals("2017-12-08 zzzz@example.com HAPPY\n2017-12-10 aaaa@example.com SUICIDAL", entity.readText()!!.sortLines())
        }
    }

    @Test
    fun `records happiness`() {
        server.execute(HttpPost("/happiness").apply {
            entity = formEntity(
                    "email" to "test@example.com",
                    "emotion" to "ECSTATIC")
        }).apply {
            assertEquals(HttpStatus.SC_NO_CONTENT, statusLine.statusCode)
        }

        val streamId = streamId("happiness", "test@example.com")
        assertEquals(listOf(HappinessReceived("test@example.com", LocalDate.parse("2017-12-08"), Emotion.ECSTATIC)),
            server.eventSource.readStream().readStreamForwards(streamId).map { re -> EventCodecs.deserializeEvent(re.eventRecord()) }.collect(toList()))

        server.execute(HttpGet("/happiness")).apply {
            assertEquals(HttpStatus.SC_OK, statusLine.statusCode)
            assertEquals("2017-12-08 test@example.com ECSTATIC\n", entity.readText())
        }
    }

    @Test
    fun `records happiness by posting JSON`() {
        server.execute(HttpPost("/happiness").apply {
            entity = StringEntity("""{"email":"test@example.com", "emotion":"ECSTATIC"}""", ContentType.APPLICATION_JSON)
        }).apply {
            assertEquals(HttpStatus.SC_NO_CONTENT, statusLine.statusCode)
        }

        val streamId = streamId("happiness", "test@example.com")
        assertEquals(listOf(HappinessReceived("test@example.com", LocalDate.parse("2017-12-08"), Emotion.ECSTATIC)),
                server.eventSource.readStream().readStreamForwards(streamId).map { re -> EventCodecs.deserializeEvent(re.eventRecord()) }.collect(toList()))

        server.execute(HttpGet("/happiness")).apply {
            assertEquals(HttpStatus.SC_OK, statusLine.statusCode)
            assertEquals("2017-12-08 test@example.com ECSTATIC\n", entity.readText())
        }
    }

    @Test
    fun `rejects happiness in unsupported format`() {
        server.execute(HttpPost("/happiness").apply {
            entity = StringEntity("""<record-happiness><email>test@example.com</email><happiness>ECSTATIC</happiness></record-happiness>""", ContentType.APPLICATION_XML)
        }).apply {
            assertEquals(HttpStatus.SC_UNSUPPORTED_MEDIA_TYPE, statusLine.statusCode)
        }
    }

    @Test
    fun `rejects happiness not in specified list in form data`() {
        server.execute(HttpPost("/happiness").apply {
            entity = formEntity(
                    "email" to "test@example.com",
                    "emotion" to "NOT AT ALL HAPPY")
        }).apply {
            assertEquals(HttpStatus.SC_BAD_REQUEST, statusLine.statusCode)
        }
    }

    @Test
    fun `rejects happiness not in specified list in JSON`() {
        server.execute(HttpPost("/happiness").apply {
            entity = StringEntity("""{"email":"test@example.com", "emotion":"NOT AT ALL HAPPY"}""", ContentType.APPLICATION_JSON)
        }).apply {
            assertEquals(HttpStatus.SC_BAD_REQUEST, statusLine.statusCode)
        }
    }

    private fun formEntity(vararg entries: Pair<String, String>): UrlEncodedFormEntity =
            UrlEncodedFormEntity(entries.map { (key, value) -> BasicNameValuePair(key, value) }, Charsets.UTF_8)

    private fun String.sortLines() = split("\n").filter { it.isNotBlank() }.sorted().joinToString("\n")
}

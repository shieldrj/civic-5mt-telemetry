package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CSV escaping.
 *
 * Every failure here is silent: the file opens, the columns are wrong from some point
 * rightwards, and nothing says so. That is the whole reason this is a tested pure function
 * rather than a `joinToString(",")` at the export site.
 */
class CsvTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Fields")
    inner class Fields {

        @Test
        fun `An ordinary field is not quoted`() {
            assertEquals("35.8", Csv.escape("35.8"))
            assertEquals("yes", Csv.escape("yes"))
        }

        @Test
        fun `A comma is what quoting exists for`() {
            // Unquoted, this one field becomes two columns and shifts every column after it.
            assertEquals("\"7,137\"", Csv.escape("7,137"))
        }

        @Test
        fun `A quote is doubled, inside quotes`() {
            assertEquals("\"he said \"\"go\"\"\"", Csv.escape("he said \"go\""))
        }

        @Test
        fun `A line break is quoted rather than ending the record`() {
            assertEquals("\"two\r\nlines\"", Csv.escape("two\r\nlines"))
            assertEquals("\"two\nlines\"", Csv.escape("two\nlines"))
        }

        @Test
        fun `An absent reading is an empty field, not a zero`() {
            // The lambda column on a car with no wideband PID. Writing 0 there would be a
            // measurement of zero, which is a different claim from "never reported".
            assertEquals("", Csv.escape(null))
            assertEquals("a,,b", Csv.row(listOf("a", null, "b")))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Documents")
    inner class Documents {

        private val columns = listOf("trip", "miles", "lambda")

        @Test
        fun `Rows are written under the header they name`() {
            val doc = Csv.document(
                columns,
                listOf(
                    listOf("trip" to "1", "miles" to "12.4", "lambda" to "1.002"),
                    listOf("trip" to "2", "miles" to "0.8", "lambda" to null),
                ),
            )

            assertEquals(
                "trip,miles,lambda\r\n1,12.4,1.002\r\n2,0.8,\r\n",
                doc,
            )
        }

        @Test
        fun `A row that does not match the header is refused, not written`() {
            // The silent one: a column added to the values and not the header produces a file
            // that looks entirely normal and is wrong from that column onwards.
            assertFailsWith<IllegalArgumentException> {
                Csv.document(columns, listOf(listOf("trip" to "1", "miles" to "12.4")))
            }
        }

        @Test
        fun `An empty export is a header, not an empty file`() {
            // A zero-byte file is indistinguishable from an export that failed.
            val doc = Csv.document(columns, emptyList())
            assertEquals("trip,miles,lambda\r\n", doc)
            assertTrue(doc.isNotEmpty())
        }
    }
}

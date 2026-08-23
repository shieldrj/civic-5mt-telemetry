package com.shieldrj.civic5mt.core

/**
 * Writing CSV that survives contact with a spreadsheet.
 *
 * Small enough to look trivial, which is exactly why it is here rather than inline next to
 * the export: the failure mode is silent. A field containing a comma splits one column into
 * two, every column after it shifts left, and the file still opens - so a drive's economy
 * figure ends up under "duration" and nothing anywhere says so.
 *
 * RFC 4180: quote when the field contains a comma, a quote or a line break; double any quote
 * inside; separate records with CRLF.
 */
object Csv {

    const val LINE_SEP = "\r\n"

    /**
     * One field, quoted only when it has to be.
     *
     * Null becomes an empty field. That is the CSV spelling of "the car did not report this"
     * - the lambda column on a drive with no wideband PID - and it stays distinct from a
     * zero, which would read as a measurement of zero.
     */
    fun escape(field: String?): String {
        if (field == null) return ""
        val needsQuotes = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    fun row(fields: List<String?>): String = fields.joinToString(",") { escape(it) }

    /**
     * A whole file, with the header taken from the rows themselves.
     *
     * Each row arrives as column-name to value pairs, so the header cannot drift out of step
     * with the values under it. That drift is the other silent failure here - a column
     * inserted in one place and not the other produces a file that looks completely normal
     * and is wrong from that column rightwards.
     *
     * Returns just the header when there are no rows, rather than an empty file: an export
     * that produces nothing at all is indistinguishable from an export that failed.
     */
    fun document(
        columns: List<String>,
        rows: List<List<Pair<String, String?>>>,
    ): String {
        val out = StringBuilder()
        out.append(row(columns)).append(LINE_SEP)
        for (r in rows) {
            require(r.map { it.first } == columns) {
                "row columns ${r.map { it.first }} do not match header $columns"
            }
            out.append(row(r.map { it.second })).append(LINE_SEP)
        }
        return out.toString()
    }
}

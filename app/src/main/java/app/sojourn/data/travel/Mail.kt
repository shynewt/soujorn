package app.sojourn.data.travel

/** A shared text's subject and its text and HTML, as booking pages and shared texts come. */
data class MailMessage(val subject: String, val from: String, val html: String, val text: String)

/** Plain text out of HTML, for bookings shared as a page. */
object Mail {
    /** Plain text from HTML, for the text fallback when an email has no markup. */
    fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</tr>|</div>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
        .replace(Regex("[ \\t]+"), " ")
}

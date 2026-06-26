package com.inkpress.app

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import java.net.URL

object Scraper {

    class Article(
        val title: String,
        val contentHtml: String,
        val sourceUrl: String
    )

    fun fetchAndClean(url: String): Article {
        // Simple URL validation/formatting
        val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }

        // Fetch page with user agent to avoid basic crawler blocking
        val doc = Jsoup.connect(formattedUrl)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .timeout(15000)
            .followRedirects(true)
            .get()

        val title = doc.title().trim().ifEmpty { "Untitled Article" }

        // Find the main content element
        val mainContent = findMainContent(doc)

        // Clean content: remove scripts, styles, frames, and images
        mainContent.select("script, style, iframe, svg, noscript, form, header, footer, nav, aside, figure, figcaption").remove()
        mainContent.select("img").remove()

        // Clean attributes (remove styling, class, id, etc. to make it minimal for xteink x3)
        // Keep only structural tags and clean text
        val safelist = Safelist.relaxed()
            .removeTags("img") // ensure images are removed
            .addAttributes("a", "href")
            .removeAttributes("a", "target", "rel")

        val cleanHtml = Jsoup.clean(mainContent.html(), safelist)

        // Wrap in valid XHTML structure for EPUB (must use xml syntax and be closed properly)
        val xhtmlDoc = Document.createShell("")
        xhtmlDoc.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
        xhtmlDoc.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
        xhtmlDoc.charset(Charsets.UTF_8)
        
        val body = xhtmlDoc.body()
        body.append("<h1>${escapeHtml(title)}</h1>")
        body.append("<p><em>Source: <a href=\"$formattedUrl\">$formattedUrl</a></em></p>")
        body.append("<hr/>")
        body.append(cleanHtml)

        // Clean up empty paragraphs
        xhtmlDoc.select("p:empty").remove()

        return Article(
            title = title,
            contentHtml = body.html(),
            sourceUrl = formattedUrl
        )
    }

    private fun findMainContent(doc: Document): org.jsoup.nodes.Element {
        // Try standard structural container elements in order of specificity
        val selectors = listOf(
            "article",
            "[role=main]",
            "main",
            "#content",
            ".content",
            "#main",
            ".main"
        )

        for (selector in selectors) {
            val element = doc.select(selector).first()
            if (element != null && element.text().length > 300) {
                return element.clone()
            }
        }

        // Heuristics: find element with the most paragraph text
        val body = doc.body() ?: return doc
        var bestElement = body
        var maxTextLen = 0

        body.allElements.forEach { element ->
            // Skip general page layout containers if they span too much text but don't contain paragraphs directly
            if (element.tagName() == "div" || element.tagName() == "section") {
                val directTextLength = element.ownText().length
                val pTagsTextLength = element.select("p").sumOf { it.text().length }
                val totalLength = directTextLength + pTagsTextLength
                if (totalLength > maxTextLen) {
                    maxTextLen = totalLength
                    bestElement = element
                }
            }
        }

        return bestElement.clone()
    }

    private fun escapeHtml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

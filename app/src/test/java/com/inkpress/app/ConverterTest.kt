package com.inkpress.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ConverterTest {

    @Test
    fun testUrlExtraction() {
        // Chrome shares URL often with title prepended
        val sharedText1 = "Google News https://news.google.com/home?hl=en-US"
        val sharedText2 = "https://example.com/article"
        val sharedText3 = "Check this out: http://myblog.org/post/123?ref=share indeed!"

        val regex = Regex("""https?://[^\s]+""")

        val url1 = regex.find(sharedText1)?.value
        val url2 = regex.find(sharedText2)?.value
        val url3 = regex.find(sharedText3)?.value

        assertEquals("https://news.google.com/home?hl=en-US", url1)
        assertEquals("https://example.com/article", url2)
        assertEquals("http://myblog.org/post/123?ref=share", url3)
    }

    @Test
    fun testEpubBuilderStructure() {
        val title = "Test Title"
        val bodyHtml = "<h1>Hello</h1><p>This is a test paragraph.</p>"
        
        val byteOut = ByteArrayOutputStream()
        EpubBuilder.build(title, bodyHtml, byteOut)
        
        val epubBytes = byteOut.toByteArray()
        assertTrue(epubBytes.isNotEmpty())

        // Read the Zip entries to verify EPUB spec details
        val zipIn = ZipInputStream(ByteArrayInputStream(epubBytes))
        
        // 1. First entry MUST be mimetype
        val firstEntry = zipIn.nextEntry
        assertNotNull(firstEntry)
        assertEquals("mimetype", firstEntry!!.name)
        assertEquals(ZipEntry.STORED, firstEntry.method) // Must be uncompressed
        
        val mimeContent = zipIn.reader().readText()
        assertEquals("application/epub+zip", mimeContent)
        zipIn.closeEntry()

        // 2. Validate container.xml, content.opf, toc.ncx, content.xhtml exist and are valid XML
        val fileNames = mutableListOf<String>()
        var entry = zipIn.nextEntry
        val dbFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Disable loading of external DTDs to prevent test hangs/network lookups
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/validation", false)
        }
        val dBuilder = dbFactory.newDocumentBuilder()

        var entryCount = 0
        while (entry != null) {
            entryCount++
            fileNames.add(entry.name)
            
            // Read content to make sure it doesn't fail
            val content = zipIn.reader().readText()
            assertTrue(content.isNotEmpty())
            
            // Validate that the entry is well-formed XML (mimetype is text, not XML)
            if (entry.name.endsWith(".xml") || entry.name.endsWith(".opf") || entry.name.endsWith(".ncx") || entry.name.endsWith(".xhtml")) {
                try {
                    dBuilder.parse(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
                } catch (e: Exception) {
                    fail("XML parsing failed for entry '${entry.name}': ${e.message}\nContent was:\n$content")
                }
            }

            // Perform basic validation on xhtml
            if (entry.name == "OEBPS/content.xhtml") {
                assertTrue(content.contains("<title>Test Title</title>"))
                assertTrue(content.contains(bodyHtml))
            }
            
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }
        zipIn.close()

        assertTrue(fileNames.contains("META-INF/container.xml"))
        assertTrue(fileNames.contains("OEBPS/content.opf"))
        assertTrue(fileNames.contains("OEBPS/toc.ncx"))
        assertTrue(fileNames.contains("OEBPS/content.xhtml"))
    }

    @Test
    fun testRealUrlScrapingAndEpubGeneration() {
        val url = "https://en.wikipedia.org/wiki/EPUB"
        val article = Scraper.fetchAndClean(url)
        val file = java.io.File("../test_output.epub")
        val out = java.io.FileOutputStream(file)
        EpubBuilder.build(article.title, article.contentHtml, out)
        out.close()
        assertTrue(file.exists())
        println("Generated test EPUB at: ${file.absolutePath}")
    }
}

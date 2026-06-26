package com.inkpress.app

import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object EpubBuilder {

    fun build(title: String, bodyHtml: String, outputStream: OutputStream) {
        val zipOut = ZipOutputStream(outputStream)
        val uuid = UUID.randomUUID().toString()

        try {
            // 1. Write mimetype (MUST be first and MUST be STORED/uncompressed)
            val mimeContent = "application/epub+zip"
            val mimeBytes = mimeContent.toByteArray(Charsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                crc = CRC32().apply { update(mimeBytes) }.value
            }
            zipOut.putNextEntry(mimeEntry)
            zipOut.write(mimeBytes)
            zipOut.closeEntry()

            // Helper to write deflated text file
            fun writeTextEntry(name: String, content: String) {
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.DEFLATED
                }
                zipOut.putNextEntry(entry)
                zipOut.write(content.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
            }

            // 2. Write container.xml
            val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                    </rootfiles>
                </container>""".trimIndent()
            writeTextEntry("META-INF/container.xml", containerXml)

            // 3. Write content.opf (Package file)
            val escapedTitle = escapeXml(title)
            val contentOpf = """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookID" version="2.0">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                        <dc:title>$escapedTitle</dc:title>
                        <dc:language>en</dc:language>
                        <dc:identifier id="BookID">urn:uuid:$uuid</dc:identifier>
                        <dc:creator>InkPress</dc:creator>
                    </metadata>
                    <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="content" href="content.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine toc="ncx">
                        <itemref idref="content"/>
                    </spine>
                </package>""".trimIndent()
            writeTextEntry("OEBPS/content.opf", contentOpf)

            // 4. Write toc.ncx (Table of Contents)
            val tocNcx = """<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                    <head>
                        <meta name="dtb:uid" content="urn:uuid:$uuid"/>
                        <meta name="dtb:depth" content="1"/>
                        <meta name="dtb:totalPageCount" content="0"/>
                        <meta name="dtb:maxPageNumber" content="0"/>
                    </head>
                    <docTitle>
                        <text>$escapedTitle</text>
                    </docTitle>
                    <navMap>
                        <navPoint id="navPoint-1" playOrder="1">
                            <navLabel>
                                <text>$escapedTitle</text>
                            </navLabel>
                            <content src="content.xhtml"/>
                        </navPoint>
                    </navMap>
                </ncx>""".trimIndent()
            writeTextEntry("OEBPS/toc.ncx", tocNcx)

            // 5. Write content.xhtml
            val contentXhtml = """<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <title>$escapedTitle</title>
                    <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=utf-8"/>
                    <style type="text/css">
                        body { font-family: sans-serif; padding: 1em; line-height: 1.4; color: #000000; background-color: #ffffff; }
                        h1 { text-align: center; font-size: 1.5em; margin-bottom: 0.5em; }
                        p { margin: 0 0 1em 0; text-align: justify; }
                        hr { border: 0; border-top: 1px solid #000; margin: 1.5em 0; }
                        a { color: #000; text-decoration: underline; }
                        em { font-style: italic; }
                        strong { font-weight: bold; }
                    </style>
                </head>
                <body>
                    $bodyHtml
                </body>
                </html>""".trimIndent()
            writeTextEntry("OEBPS/content.xhtml", contentXhtml)

        } finally {
            zipOut.finish()
            zipOut.close()
        }
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

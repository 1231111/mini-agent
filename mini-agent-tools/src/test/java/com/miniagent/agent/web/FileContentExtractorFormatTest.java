package com.miniagent.agent.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileContentExtractorFormatTest {

    private final FileContentExtractor extractor = new FileContentExtractor();

    @Test
    void recognizesCommonWebAndStructuredTextFiles() {
        for (String name : new String[]{"page.html", "page.htm", "layout.xhtml", "feed.xml", "schema.xsd",
                "transform.xslt", "app.js", "types.ts", "settings.yaml", "values.toml", "data.jsonl"}) {
            assertEquals(FileContentExtractor.DocKind.TEXT, extractor.resolveKind(name, "application/octet-stream"), name);
            assertTrue(extractor.isExtractable(name, "application/octet-stream"), name);
        }
    }

    @Test
    void recognizesPdfAndModernOfficeDocuments() {
        assertEquals(FileContentExtractor.DocKind.PDF, extractor.resolveKind("report.pdf", "application/pdf"));
        assertEquals(FileContentExtractor.DocKind.DOCX, extractor.resolveKind("report.docx", "application/octet-stream"));
        assertEquals(FileContentExtractor.DocKind.XLSX, extractor.resolveKind("report.xlsx", "application/octet-stream"));
        assertEquals(FileContentExtractor.DocKind.PPTX, extractor.resolveKind("report.pptx", "application/octet-stream"));
    }
}

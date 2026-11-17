package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.browser.BrowserService;
import com.miniagent.agent.browser.BrowserSessionIdentity;
import com.miniagent.agent.web.WebSearchService;
import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BuiltinToolsBrowserExtractionMetadataTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEXT = "# 已核实的新闻\n\nhttps://news.example.com/article\n\n原文正文。";
    @TempDir Path workspace;

    @AfterEach void cleanup() {
        WorkspaceContext.clearAll();
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
    }

    @Test void extractionReceiptExposesCoverWithoutInjectingItIntoTheDocument() throws Exception {
        var browser = mock(BrowserService.class);
        ToolRegistry tools = register(browser, new BrowserService.ArticleExtractionResult(true, TEXT, List.of(
                new WebSearchService.PageMetadata("已核实的新闻", "2026-11-16",
                        "https://cdn.example.com/cover.jpg", "https://news.example.com/article"))));

        ToolResult result = tools.executeResult("browser_extract_text", "{}");
        assertEquals(ToolStatus.SUCCESS, result.status());
        JsonNode receipt = JSON.readTree(result.legacyText());
        JsonNode source = receipt.path("source_page_metadata").get(0);
        assertEquals("https://cdn.example.com/cover.jpg", source.path("thumbnail_url").asText());
        assertEquals("https://news.example.com/article", source.path("thumbnail_source_url").asText());
        assertEquals("2026-11-16", source.path("publication_date").asText());
        assertEquals(TEXT, Files.readString(workspace.resolve("_source.md")));
        assertEquals(TEXT, Files.readString(workspace.resolve("notes.md")));
        verify(browser).extractArticleWithMetadata(eq(BrowserSessionIdentity.resolve(null)), eq(workspace.resolve("images")));
    }

    @Test void wikiPagesKeepTheirOwnCoversAndDoNotAdvertiseTheLastChapterAsTheWholeDocument() throws Exception {
        var browser = mock(BrowserService.class);
        ToolRegistry tools = register(browser, new BrowserService.ArticleExtractionResult(true, TEXT, List.of(
                new WebSearchService.PageMetadata("章节一", null, "https://cdn.example.com/one.jpg", "https://docs.example.com/one"),
                new WebSearchService.PageMetadata("章节二", null, "https://cdn.example.com/two.jpg", "https://docs.example.com/two"))));

        ToolResult result = tools.executeResult("browser_extract_text", "{\"path\":\"wiki.md\"}");
        assertEquals(ToolStatus.SUCCESS, result.status());
        JsonNode receipt = JSON.readTree(result.legacyText());
        assertFalse(receipt.has("thumbnail_url"));
        assertEquals(2, receipt.path("source_page_metadata").size());
        assertEquals("https://docs.example.com/one", receipt.path("source_page_metadata").get(0).path("url").asText());
        assertEquals("https://cdn.example.com/two.jpg", receipt.path("source_page_metadata").get(1).path("thumbnail_url").asText());
        assertEquals(TEXT, Files.readString(workspace.resolve("wiki.md")));
        assertFalse(Files.exists(workspace.resolve("notes.md")));
    }

    @Test void failedBrowserExtractionCannotBeWrittenAsSuccessfulSourceText() throws Exception {
        String failure = "{\"success\":false,\"errorCode\":\"EMPTY_RESULT\",\"error\":\"抽取失败：没有正文\"}";
        ToolRegistry tools = register(mock(BrowserService.class),
                new BrowserService.ArticleExtractionResult(false, failure, List.of()));

        ToolResult result = tools.executeResult("browser_extract_text", "{}");
        assertEquals(ToolStatus.FAILED, result.status());
        assertEquals(failure, result.legacyText());
        assertFalse(Files.exists(workspace.resolve("_source.md")));
        assertFalse(Files.exists(workspace.resolve("notes.md")));
    }

    @Test void rejectedDocumentWriteDoesNotExposeMetadataOrOverwriteTheNotesMirror() throws Exception {
        String original = "原始的大文件内容".repeat(3000);
        Files.writeString(workspace.resolve("_source.md"), original);
        ToolRegistry tools = register(mock(BrowserService.class), new BrowserService.ArticleExtractionResult(true, TEXT, List.of(
                new WebSearchService.PageMetadata("新闻", null, "https://cdn.example.com/new.jpg", "https://news.example.com/new"))));

        ToolResult result = tools.executeResult("browser_extract_text", "{}");
        assertEquals(ToolStatus.FAILED, result.status());
        assertEquals(ToolErrorCode.CONFLICT, result.errorCode());
        assertFalse(JSON.readTree(result.legacyText()).has("source_page_metadata"));
        assertEquals(original, Files.readString(workspace.resolve("_source.md")));
        assertFalse(Files.exists(workspace.resolve("notes.md")));
    }

    @Test void pageWithoutCoverStillProducesAValidDocumentAndSourceReceipt() throws Exception {
        ToolRegistry tools = register(mock(BrowserService.class), new BrowserService.ArticleExtractionResult(true, TEXT, List.of(
                new WebSearchService.PageMetadata("无封面文章", null, null, "https://news.example.com/plain"))));

        ToolResult result = tools.executeResult("browser_extract_text", "{}");
        assertEquals(ToolStatus.SUCCESS, result.status());
        JsonNode source = JSON.readTree(result.legacyText()).path("source_page_metadata").get(0);
        assertFalse(source.has("thumbnail_url"));
        assertEquals("https://news.example.com/plain", source.path("url").asText());
        assertEquals(TEXT, Files.readString(workspace.resolve("_source.md")));
    }

    private ToolRegistry register(BrowserService browser, BrowserService.ArticleExtractionResult extracted) {
        OwnerContext.setCurrentUser(7L);
        OwnerContext.setCurrentTenant("thumbnail-test");
        WorkspaceContext.setSessionOverride("thumbnail-chat");
        WorkspaceContext.setRootOverride(workspace);
        when(browser.extractArticleWithMetadata(eq(BrowserSessionIdentity.resolve(null)), any(Path.class))).thenReturn(extracted);
        BuiltinTools builtins = new BuiltinTools();
        ToolRegistry registry = new ToolRegistry();
        ReflectionTestUtils.setField(builtins, "registry", registry);
        ReflectionTestUtils.setField(builtins, "browserService", browser);
        ReflectionTestUtils.invokeMethod(builtins, "registerBrowserTools");
        return registry;
    }
}

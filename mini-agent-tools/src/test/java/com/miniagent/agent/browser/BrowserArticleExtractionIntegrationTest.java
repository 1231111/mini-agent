package com.miniagent.agent.browser;

import com.miniagent.agent.security.NetworkGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Real Chromium, loopback fixtures only: ordinary articles must not wait for a nonexistent wiki. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class BrowserArticleExtractionIntegrationTest {
    private static final String SESSION = "article-extraction";
    @TempDir Path workspace;
    private BrowserService browser;
    private HttpServer server;
    private String base;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serve);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        browser = new BrowserService();
        ReflectionTestUtils.setField(browser, "headless", true);
        ReflectionTestUtils.setField(browser, "networkGuard", new NetworkGuard() {
            @Override public String validateUrl(String url) {
                URI target = URI.create(url);
                return "http".equals(target.getScheme()) && "127.0.0.1".equals(target.getHost())
                        && target.getPort() == server.getAddress().getPort() ? null : "fixture origin denied";
            }
            @Override public String validateWebSocketUrl(String url) { return "fixture websocket denied"; }
        });
    }

    @AfterEach void stop() {
        try { if (browser != null) browser.shutdown(); }
        finally { if (server != null) server.stop(0); }
    }

    @Test void readyArticleSkipsWikiWaitAndPreservesTextCodeAndLocalImages() throws Exception {
        navigate("/article");
        long started = System.nanoTime();
        String text = browser.extractArticleText(SESSION, workspace.resolve("images"));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(text.contains("Ordinary article content is already available"), text);
        assertTrue(text.contains("console.log('article');"), text);
        assertTrue(text.contains("![Fixture illustration](images/img_001.png)"), text);
        assertNotNull(ImageIO.read(workspace.resolve("images/img_001.png").toFile()));
        try (var images = Files.list(workspace.resolve("images"))) { assertEquals(1, images.count()); }
        assertTrue(browser.evaluate(SESSION, "window.accordionClicks === 0").contains("true"),
                "An ordinary article's collapsed controls are not a wiki catalog to expand");
        assertTrue(elapsedMs < 3000, "A ready local article must not spend 6.25 seconds waiting for wiki selectors: " + elapsedMs);
    }

    @Test void longArticleUsesOneForwardTraversalAndRetainsTheFinalParagraph() {
        navigate("/long");
        String text = browser.extractArticleText(SESSION);
        assertTrue(text.contains("Paragraph 0 contains the beginning of this article"), text);
        assertTrue(text.contains("Paragraph 11 contains the end of this article"), text);
        assertTrue(browser.evaluate(SESSION, "window.rewinds === 0").contains("true"),
                "Fallback text must be harvested on the first scroll, without a second rewind");
    }

    @Test void knownDocumentShellStillWaitsForDelayedContent() {
        navigate("/delayed-document");
        browser.evaluate(SESSION, "setTimeout(() => document.querySelector('.wiki-content').textContent = "
                + "'Delayed document content arrives after the initial empty document shell and must not be lost.', 350)");
        String text = browser.extractArticleText(SESSION);
        assertTrue(text.contains("Delayed document content arrives"), text);
    }

    @Test void knownEmptyCatalogStillWaitsForItsDelayedTreeAndDocument() {
        navigate("/delayed-catalog");
        browser.evaluate(SESSION, "setTimeout(() => {"
                + "document.querySelector('.wiki-tree-inner-container').innerHTML='<div data-node-uid=ready></div>';"
                + "document.querySelector('.wiki-content').textContent='Delayed catalog document content must remain available after the tree appears.';"
                + "}, 350)");
        String text = browser.extractArticleText(SESSION);
        assertTrue(text.contains("Delayed catalog document content"), text);
    }

    @Test void virtualDocumentKeepsBlocksThatDisappearWhileScrolling() {
        navigate("/virtual");
        String text = browser.extractArticleText(SESSION);
        for (int i = 0; i <= 4; i++) assertTrue(text.contains("Virtual document segment " + i), text);
    }

    private void navigate(String path) {
        String result = browser.navigate(SESSION, base + path);
        assertTrue(result.contains("Article fixture"), result);
    }

    private void serve(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/picture.png")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB), "png", bytes);
            respond(exchange, bytes.toByteArray(), "image/png");
            return;
        }
        String body = switch (path) {
            case "/long" -> longArticle();
            case "/delayed-document" -> "<div class=wiki-content></div>";
            case "/delayed-catalog" -> "<aside class=wiki-tree-inner-container style='width:220px;height:300px'></aside><div class=wiki-content></div>";
            case "/virtual" -> virtualDocument();
            default -> """
                    <article><h1>Ordinary article</h1>
                    <p>Ordinary article content is already available and does not need a wiki tree or document container.</p>
                    <pre>console.log('article');</pre>
                    <img alt='Fixture illustration' src='/picture.png' width=100 height=60></article>
                    <button aria-expanded=false onclick='window.accordionClicks++'>Unrelated accordion</button>
                    <script>window.accordionClicks=0;</script>
                    """;
        };
        String html = "<!doctype html><html><head><meta charset=utf-8><title>Article fixture</title>"
                + "<link rel=icon href='data:,'></head><body>" + body + "</body></html>";
        respond(exchange, html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
    }

    private static String longArticle() {
        StringBuilder html = new StringBuilder("<article>");
        for (int i = 0; i < 12; i++) html.append("<p style='height:400px'>Paragraph ").append(i)
                .append(i == 11 ? " contains the end of this article" : " contains the beginning of this article")
                .append(" with enough meaningful text to extract.</p>");
        return html.append("""
                </article><script>window.rewinds=0;let before=0;
                addEventListener('scroll',()=>{if(scrollY<before-100)window.rewinds++;before=scrollY;});</script>
                """).toString();
    }

    private static String virtualDocument() {
        return """
                <div class=wiki-content style='height:180px;overflow-y:auto' id=doc>
                  <div style='height:1800px;position:relative'><div id=rows></div></div>
                </div><script>
                const doc=document.getElementById('doc'), rows=document.getElementById('rows');
                function paint(){const i=Math.min(4,Math.floor(doc.scrollTop/400));
                  rows.innerHTML='<div class=docx-text data-block-id="segment-'+i+'"><span data-string="Virtual document segment '+i+' contains enough content for reliable extraction.">Virtual document segment '+i+' contains enough content for reliable extraction.</span></div>';}
                doc.addEventListener('scroll',()=>setTimeout(paint,20));paint();
                </script>
                """;
    }

    private static void respond(HttpExchange exchange, byte[] bytes, String type) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}

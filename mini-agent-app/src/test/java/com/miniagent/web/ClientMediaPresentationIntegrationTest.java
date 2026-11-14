package com.miniagent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real Chromium, production renderers and local media bytes; no live websites or model calls. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class ClientMediaPresentationIntegrationTest {
    private static final String ORIGIN = "http://127.0.0.1:18765";
    private static final String EXTERNAL = "https://media-fixture.test";
    private static final String TOKEN = "media-test-token-only";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] WAV = waveFixture();
    private static final String POSTER = "<svg xmlns='http://www.w3.org/2000/svg' width='320' height='480'>"
            + "<rect width='320' height='480' fill='#dcd7ff'/><circle cx='160' cy='180' r='65' fill='#7264d8'/>"
            + "<text x='160' y='340' text-anchor='middle' font-size='30'>本地电影封面</text></svg>";

    private record Fixture(Page page, Map<String, Map<String, String>> requestHeaders,
                           AtomicReference<byte[]> video) {}

    private static String resource(String name) throws Exception {
        try (var input = ClientMediaPresentationIntegrationTest.class.getResourceAsStream("/static/" + name)) {
            if (input == null) throw new IllegalStateException("Missing client resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Playwright runtime() {
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(repository.resolve("mini-agent-desktop"))) repository = repository.getParent();
        Path bundled = repository.resolve("mini-agent-desktop/browsers");
        var env = new HashMap<>(System.getenv());
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        if (Files.isDirectory(bundled)) env.put("PLAYWRIGHT_BROWSERS_PATH", bundled.toString());
        return Playwright.create(new Playwright.CreateOptions().setEnv(env));
    }

    private Fixture prepare(Browser browser, int width) throws Exception {
        Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(width, 1100));
        Map<String, Map<String, String>> headers = new HashMap<>();
        AtomicReference<byte[]> video = new AtomicReference<>(new byte[0]);
        // Intercept every request, including unexpected ones, so the test never contacts a live service.
        page.route("**/*", route -> {
            String url = route.request().url();
            headers.put(url, new HashMap<>(route.request().headers()));
            String path = URI.create(url).getPath();
            if (path.endsWith(".wav")) {
                route.fulfill(new Route.FulfillOptions().setContentType("audio/wav").setBodyBytes(WAV));
            } else if (path.endsWith(".webm")) {
                route.fulfill(new Route.FulfillOptions().setContentType("video/webm").setBodyBytes(video.get()));
            } else if (path.endsWith(".svg")) {
                route.fulfill(new Route.FulfillOptions().setContentType("image/svg+xml").setBody(POSTER));
            } else if (url.equals(ORIGIN + "/")) {
                route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("""
                        <!doctype html><html data-theme="light"><meta charset="utf-8">
                        <body style="margin:0;display:block">
                        <main style="width:100%;max-width:840px;margin:0 auto;padding:20px;box-sizing:border-box">
                        <div class="bot-row"><div class="bot-body" id="reply"></div></div>
                        </main></body></html>
                        """));
            } else {
                route.fulfill(new Route.FulfillOptions().setStatus(404).setBody("Local fixture: unknown resource"));
            }
        });
        page.navigate(ORIGIN + "/");
        for (String css : new String[]{"chat.css", "shell.css", "ai-workspace.css", "chat-artifacts.css"})
            page.addStyleTag(new Page.AddStyleTagOptions().setContent(resource("css/" + css)));
        page.addScriptTag(new Page.AddScriptTagOptions().setContent("""
                var currentId='media-fixture-chat';
                window.__externalOpens=[];
                window.electronAPI={openExternal:async url=>{window.__externalOpens.push(url);return {ok:true};}};
                """));
        for (String js : new String[]{"js/security.js", "js/chat/04-media.js", "js/chat/12-message.js",
                "js/chat/13-markdown.js", "js/chat/14-media-src.js"})
            page.addScriptTag(new Page.AddScriptTagOptions().setContent(resource(js)));
        page.evaluate("token => MiniAuth.setToken(token)", TOKEN);
        return new Fixture(page, headers, video);
    }

    private static String mediaMarkdown(List<Map<String, Object>> items) throws Exception {
        return "```miniagent-media\n" + JSON.writeValueAsString(Map.of("title", "为你推荐", "items", items)) + "\n```";
    }

    private static void render(Page page, String markdown) {
        page.evaluate("text => {document.getElementById('reply').innerHTML=renderMD(text);}", markdown);
    }

    private static List<Map<String, Object>> recommendations() {
        return List.of(
                Map.of("type", "movie", "title", "星海旅程", "description", "这是一条本地电影测试数据。",
                        "posterUrl", EXTERNAL + "/poster.svg", "pageUrl", EXTERNAL + "/movie/1",
                        "releaseDate", "2026-11-13", "creator", "测试导演"),
                Map.of("type", "song", "title", "晨光", "creator", "测试歌手",
                        "mediaUrl", "/api/generated-media/fixture/song.wav", "mimeType", "audio/wav",
                        "pageUrl", EXTERNAL + "/song/1"),
                Map.of("type", "video", "title", "一分钟看星空", "description", "本地生成的视频素材。",
                        "mediaUrl", EXTERNAL + "/clip.webm", "mimeType", "video/webm",
                        "pageUrl", EXTERNAL + "/video/1"));
    }

    @ParameterizedTest @ValueSource(ints = {1366, 390})
    void recommendationsRenderPlayableMediaAndResponsiveMovieCards(int width) throws Exception {
        try (Playwright playwright = runtime(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Fixture fixture = prepare(browser, width);
            Page page = fixture.page();
            fixture.video().set(recordVideo(page));
            render(page, mediaMarkdown(recommendations()));
            assertEquals(1, page.locator("#reply audio[controls]").count());
            assertEquals(1, page.locator("#reply video[controls]").count());
            assertEquals(0, page.locator("#reply article[data-media-type='movie'] video").count());
            assertEquals(1, page.locator("#reply img[src='" + EXTERNAL + "/poster.svg']").count());
            assertTrue(page.locator("#reply").innerText().contains("星海旅程"));
            assertEquals(1, page.locator("#reply a[href='" + EXTERNAL + "/movie/1']").count());
            // Movie metadata has no stream: only the separate video recommendation may create a video element.
            assertFalse(page.locator("#reply").innerText().contains("miniagent-media"));
            page.waitForFunction("() => [...document.querySelectorAll('#reply audio,#reply video')].every(m=>m.readyState>=2)");
            assertTrue((Boolean) page.locator("#reply video").evaluate("video => video.videoWidth > 0"));
            assertEquals(true, page.evaluate("() => document.documentElement.scrollWidth <= innerWidth"));
            saveEvidence(page, "media-recommendations-" + width + ".png");

            Locator audio = page.locator("#reply audio");
            audio.click(new Locator.ClickOptions().setPosition(24, 27));
            page.waitForFunction("() => document.querySelector('#reply audio').currentTime > .05");
            assertEquals(false, audio.evaluate("audio=>audio.paused"));
            audio.evaluate("audio=>audio.pause()");

            Locator video = page.locator("#reply video");
            var bounds = video.boundingBox();
            video.click(new Locator.ClickOptions().setPosition(24, bounds.height - 48));
            page.waitForFunction("() => document.querySelector('#reply video').currentTime > .03");
            assertEquals(false, video.evaluate("video=>video.paused"));
            page.close();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void playableMarkdownLinksBecomeNativePlayersAndRetainPlaybackAcrossRenders(boolean withoutMoveBefore) throws Exception {
        try (Playwright playwright = runtime(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = prepare(browser, 900).page();
            if (withoutMoveBefore) page.evaluate("() => Object.defineProperty(Element.prototype,'moveBefore',{value:undefined,configurable:true,writable:true})");
            String markdown = "[播放歌曲](" + EXTERNAL + "/external.wav)\n\n补充说明。";
            page.evaluate("text => setMarkdownContent(document.getElementById('reply'),renderMD(text))", markdown);
            Locator audio = page.locator("#reply audio");
            assertEquals(1, audio.count());
            audio.evaluate("audio=>window.__originalAudio=audio");
            page.waitForFunction("() => window.__originalAudio.readyState>=2");
            audio.click(new Locator.ClickOptions().setPosition(24, 27));
            page.waitForFunction("() => window.__originalAudio.currentTime > .05");
            double before = ((Number) audio.evaluate("audio=>audio.currentTime")).doubleValue();
            page.evaluate("text => setMarkdownContent(document.getElementById('reply'),renderMD(text))",
                    markdown + "\n\n新增的流式文字。");
            assertEquals(true, page.evaluate("() => document.querySelector('#reply audio')===window.__originalAudio"));
            assertEquals(false, audio.evaluate("audio=>audio.paused"));
            page.waitForFunction("before => document.querySelector('#reply audio').currentTime > before", before);
            page.close();
        }
    }

    @Test void failedMediaShowsUsefulFallbackAndBrokenPosterKeepsItsPlaceholder() throws Exception {
        try (Playwright playwright = runtime(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = prepare(browser, 900).page();
            page.route("**/broken.*", route -> route.fulfill(new Route.FulfillOptions().setStatus(404).setBody("Missing local fixture")));
            render(page, mediaMarkdown(List.of(
                    Map.of("type", "movie", "title", "封面加载失败的电影", "posterUrl", EXTERNAL + "/broken.svg",
                            "pageUrl", EXTERNAL + "/movie/missing-poster"),
                    Map.of("type", "song", "title", "站内失效音频", "mediaUrl", "/api/generated-media/fixture/broken.wav",
                            "mimeType", "audio/wav"),
                    Map.of("type", "video", "title", "站外失效视频", "mediaUrl", EXTERNAL + "/broken.webm",
                            "mimeType", "video/webm", "pageUrl", EXTERNAL + "/video/missing"))));
            page.waitForFunction("() => document.querySelectorAll('#reply .media-preview-error').length===2");
            Locator protectedFallback = page.locator("#reply article[data-media-type='song'] .media-preview-error");
            assertEquals(1, protectedFallback.locator("a[data-auth-download='/api/generated-media/fixture/broken.wav']").count());
            Locator externalFallback = page.locator("#reply article[data-media-type='video'] .media-preview-error");
            assertEquals(1, externalFallback.locator("a[href='" + EXTERNAL + "/broken.webm']").count());
            page.waitForFunction("() => document.querySelector('#reply img[data-media-poster]').hidden");
            Locator placeholder = page.locator("#reply article[data-media-type='movie'] .media-cover");
            assertTrue(placeholder.isVisible());
            assertTrue(placeholder.locator("svg").isVisible());
            assertTrue(page.locator("#reply a[href='" + EXTERNAL + "/movie/missing-poster']").isVisible());
            assertEquals(0, page.locator("#reply article[data-media-type='movie'] audio,#reply article[data-media-type='movie'] video").count());
            saveEvidence(page, "media-failed-resources.png");
            page.close();
        }
    }

    @Test void movieMediaIsExplicitlyLabeledAsTrailer() throws Exception {
        try (Playwright playwright = runtime(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Fixture fixture = prepare(browser, 900);
            fixture.video().set(recordVideo(fixture.page()));
            render(fixture.page(), mediaMarkdown(List.of(Map.of("type", "movie", "title", "星海旅程",
                    "mediaUrl", EXTERNAL + "/clip.webm", "mimeType", "video/webm",
                    "pageUrl", EXTERNAL + "/movie/trailer"))));
            assertEquals(1, fixture.page().locator("#reply video[controls]").count());
            assertTrue(fixture.page().locator("#reply").innerText().contains("预告片"));
            fixture.page().close();
        }
    }

    @Test void protectedMediaUsesBearerWhileExternalMediaNeverReceivesIt() throws Exception {
        try (Playwright playwright = runtime(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Fixture fixture = prepare(browser, 900);
            Page page = fixture.page();
            String local = "/api/generated-media/fixture/local.wav";
            String external = EXTERNAL + "/external.wav";
            render(page, mediaMarkdown(List.of(
                    Map.of("type", "song", "title", "站内音频", "mediaUrl", local, "mimeType", "audio/wav"),
                    Map.of("type", "song", "title", "站外音频", "mediaUrl", external, "mimeType", "audio/wav"))));
            page.waitForFunction("() => [...document.querySelectorAll('#reply audio')].length===2 && [...document.querySelectorAll('#reply audio')].every(m=>m.readyState>=1)");
            assertEquals("Bearer " + TOKEN, fixture.requestHeaders().get(ORIGIN + local).get("authorization"));
            assertNotNull(fixture.requestHeaders().get(external));
            assertFalse(fixture.requestHeaders().get(external).containsKey("authorization"));
            assertTrue(page.locator("#reply audio").first().getAttribute("src").startsWith("blob:"));
            page.close();
        }
    }

    @Test void persistedMediaHtmlRehydratesAuthenticatedSourceAfterBlobCacheIsGone() throws Exception {
        try (Playwright playwright = runtime(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Fixture fixture = prepare(browser, 900);
            Page page = fixture.page();
            String local = "/api/generated-media/fixture/history.wav";
            render(page, mediaMarkdown(List.of(Map.of("type", "song", "title", "历史音频",
                    "mediaUrl", local, "mimeType", "audio/wav"))));
            page.waitForFunction("() => document.querySelector('#reply audio').readyState>=2");
            String originalSource = page.locator("#reply audio").getAttribute("src");
            assertTrue(originalSource.startsWith("blob:"));
            String storedHtml = page.locator("#reply").innerHTML();
            page.evaluate("() => {document.getElementById('reply').replaceChildren();MEDIA_BLOB_CACHE.forEach(entry=>{if(entry.objectUrl)URL.revokeObjectURL(entry.objectUrl)});MEDIA_BLOB_CACHE.clear();mediaBlobCacheBytes=0;}");
            fixture.requestHeaders().remove(ORIGIN + local);
            page.evaluate("html=>document.getElementById('reply').innerHTML=html", storedHtml);
            page.waitForFunction("old => {const audio=document.querySelector('#reply audio');return audio.readyState>=2&&audio.getAttribute('src')!==old;}", originalSource);
            assertNotNull(fixture.requestHeaders().get(ORIGIN + local));
            assertEquals("Bearer " + TOKEN, fixture.requestHeaders().get(ORIGIN + local).get("authorization"));
            assertEquals(0, page.locator("#reply .media-preview-error").count());
            assertEquals(local, page.locator("#reply audio").getAttribute("data-media-origin"));
            page.close();
        }
    }

    @Test void ordinaryWebLinksOpenThroughDesktopBridgeWithoutLosingChat() throws Exception {
        try (Playwright playwright = runtime(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = prepare(browser, 900).page();
            render(page, "[查看电影详情](" + EXTERNAL + "/movie/1?q=film&lang=zh)");
            page.locator("#reply a").click();
            page.waitForFunction("() => window.__externalOpens.length===1");
            assertEquals(EXTERNAL + "/movie/1?q=film&lang=zh", page.evaluate("() => window.__externalOpens[0]"));
            assertEquals(ORIGIN + "/", page.url());
            assertEquals("noopener noreferrer", page.locator("#reply a").getAttribute("rel"));
            assertEquals(0, page.locator(".chat-link-error").count());
            page.close();
        }
    }

    @Test void unsafeProtocolsAndMarkupCannotBecomeExecutableMedia() throws Exception {
        try (Playwright playwright = runtime(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Fixture fixture = prepare(browser, 900);
            Page page = fixture.page();
            List<Map<String, Object>> items = new ArrayList<>();
            for (String url : List.of("javascript:window.__pwned=1", "data:text/html,<script>window.__pwned=1</script>",
                    "file:///C:/Windows/win.ini", "https://user:password@media-fixture.test/stream.wav")) {
                items.add(Map.of("type", "song", "title", "<img src=x onerror=window.__pwned=1>",
                        "mediaUrl", url, "posterUrl", url, "pageUrl", url, "mimeType", "audio/wav"));
            }
            render(page, mediaMarkdown(items));
            assertEquals(0, page.locator("#reply audio,#reply video,#reply img,#reply a[href]").count());
            assertEquals(0, page.locator("#reply script,#reply [onerror],#reply [onclick]").count());
            assertEquals(null, page.evaluate("() => window.__pwned || null"));
            assertFalse(fixture.requestHeaders().keySet().stream().anyMatch(url -> url.contains("password")));
            page.close();
        }
    }

    @Test void incompleteStreamingPayloadStaysHiddenAndCompletedHistoryRendersTheSameCard() throws Exception {
        try (Playwright playwright = runtime(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = prepare(browser, 900).page();
            String complete = mediaMarkdown(List.of(Map.of("type", "movie", "title", "历史电影推荐",
                    "pageUrl", EXTERNAL + "/movie/history")));
            page.evaluate("text=>document.getElementById('reply').innerHTML=renderMDStreaming(text)",
                    "正在整理。\n\n```miniagent-media\n{\"title\":\"推荐\",\"items\":[{\"type\":\"movie\"");
            String pending = page.locator("#reply").innerText();
            assertTrue(pending.contains("正在整理"));
            assertFalse(pending.contains("miniagent-media"));
            assertFalse(pending.contains("\"items\""));
            assertEquals(0, page.locator("#reply pre").count());
            String completedStream = (String) page.evaluate("text=>renderMDStreaming(text)", complete);
            String restoredHistory = (String) page.evaluate("text=>renderMD(text)", complete);
            assertEquals(completedStream, restoredHistory);
            render(page, complete);
            assertTrue(page.locator("#reply").innerText().contains("历史电影推荐"));
            assertEquals(1, page.locator("#reply a[href='" + EXTERNAL + "/movie/history']").count());
            assertEquals(0, page.locator("#reply audio,#reply video,#reply pre").count());
            page.close();
        }
    }

    /** Deterministic four-second PCM tone, independently decodable by Chromium. */
    private static byte[] waveFixture() {
        int sampleRate = 8000, samples = sampleRate * 4;
        ByteBuffer wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        for (int sample = 0; sample < samples; sample++)
            wav.putShort((short) (Math.sin(2 * Math.PI * 440 * sample / sampleRate) * 800));
        return wav.array();
    }

    /** Make a genuine short WebM fixture using the same Chromium's VP8 encoder, without external binaries. */
    private static byte[] recordVideo(Page page) {
        String encoded = (String) page.evaluate("""
                async () => {
                  const canvas=document.createElement('canvas');canvas.width=320;canvas.height=180;
                  const context=canvas.getContext('2d');
                  const stream=canvas.captureStream(12),chunks=[];
                  const recorder=new MediaRecorder(stream,{mimeType:'video/webm;codecs=vp8'});
                  recorder.ondataavailable=event=>{if(event.data.size)chunks.push(event.data);};
                  const stopped=new Promise(resolve=>recorder.onstop=resolve);
                  let frame=0;
                  const draw=()=>{context.fillStyle='#6659ca';context.fillRect(0,0,320,180);
                    context.fillStyle='white';context.font='24px sans-serif';context.fillText('MiniAgent '+frame++,36,95);};
                  draw();recorder.start();const timer=setInterval(draw,60);
                  await new Promise(resolve=>setTimeout(resolve,900));
                  recorder.stop();clearInterval(timer);await stopped;stream.getTracks().forEach(track=>track.stop());
                  const blob=new Blob(chunks,{type:'video/webm'});
                  return await new Promise(resolve=>{const reader=new FileReader();
                    reader.onload=()=>resolve(reader.result.split(',')[1]);reader.readAsDataURL(blob);});
                }
                """);
        return Base64.getDecoder().decode(encoded);
    }

    private static void saveEvidence(Page page, String name) throws Exception {
        String evidenceDir = System.getProperty("miniagent.ui.evidence");
        if (evidenceDir != null) {
            Path output = Path.of(evidenceDir); Files.createDirectories(output);
            page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve(name)).setFullPage(true));
        }
    }
}

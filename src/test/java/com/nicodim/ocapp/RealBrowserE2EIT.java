package com.nicodim.ocapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nicodim.ocapp.browser.BrowserFactory;
import com.nicodim.ocapp.browser.BrowserSession;
import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.pagemodel.BlockType;
import com.nicodim.ocapp.pagemodel.DomPageExtractor;
import com.nicodim.ocapp.pagemodel.PageModel;
import com.nicodim.ocapp.pagemodel.PageModelDiagnostics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealBrowserE2EIT {
    private HttpServer fixture;
    private ConfigurableApplicationContext application;
    private HttpClient client;
    private String fixtureBase;
    private String applicationBase;
    private Set<Path> profilesBefore;
    private PrintStream originalErr;
    private ByteArrayOutputStream capturedErr;

    @BeforeAll void startLocalFixtureAndApplication() throws Exception {
        originalErr=System.err;capturedErr=new ByteArrayOutputStream();
        System.setErr(new PrintStream(new OutputStream(){@Override public void write(int b)throws IOException{originalErr.write(b);capturedErr.write(b);}@Override public void write(byte[] b,int o,int l)throws IOException{originalErr.write(b,o,l);capturedErr.write(b,o,l);}},true,StandardCharsets.UTF_8));
        Path chrome = findChrome();
        Path driver = findDriver(chrome);
        boolean failClosed = Boolean.parseBoolean(System.getProperty("ocapp.e2e.failClosed", "false"))
            || Boolean.parseBoolean(System.getenv().getOrDefault("OCAPP_E2E_FAIL_CLOSED", "false"));
        if (chrome == null || driver == null) {
            String reason = "Compatible local Chromium/ChromeDriver executables are unavailable; no download is attempted";
            if (failClosed) throw new IllegalStateException("Real-browser fail-closed: " + reason);
            assumeTrue(false, "Real-browser local opt-in skipped: " + reason
                + "; use -Docapp.e2e.failClosed=true or OCAPP_E2E_FAIL_CLOSED=true in CI");
        }
        profilesBefore = temporaryProfiles();
        fixture = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fixture.createContext("/delayed", this::delayed);
        fixture.createContext("/redirect/0", exchange -> redirect(exchange, "/redirect/1"));
        fixture.createContext("/redirect/1", exchange -> redirect(exchange, "/delayed"));
        fixture.createContext("/blocked", this::blocked);
        fixture.createContext("/status/404", exchange -> targetStatus(exchange, 404));
        fixture.createContext("/status/500", exchange -> targetStatus(exchange, 500));
        fixture.createContext("/late-status", this::lateStatus);
        fixture.createContext("/pagemodel", this::pageModelFixture);
        fixture.start();
        fixtureBase = "http://localhost:" + fixture.getAddress().getPort();

        application = SpringApplication.run(OcappApplication.class,
            "--server.port=0",
            "--converter.security.egress-mode=UNSAFE",
            "--converter.security.unsafe-acknowledge-risk=true",
            "--converter.security.allow-private-addresses=true",
            "--converter.security.denied-hosts=169.254.169.254",
            "--converter.security.max-redirects=1",
            "--converter.browser.binary=" + chrome,
            "--converter.browser.driver-path=" + driver,
            "--converter.browser.readiness-selector=#render-ready",
            "--converter.browser.dom-quiet-period=0ms",
            "--converter.browser.settle-delay=400ms",
            "--converter.browser.readiness-timeout=10s",
            "--converter.browser.page-load-timeout=10s",
            "--converter.limits.operation-timeout=30s");
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        applicationBase = "http://localhost:" + port;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll void stopEverything() throws Exception {
        if (application != null) application.close();
        if (fixture != null) fixture.stop(0);
        System.err.flush();System.setErr(originalErr);
        String stderr=capturedErr.toString(StandardCharsets.UTF_8);
        assertThat(stderr).doesNotContain("Fetch domain is not enabled","Exception in thread \"CDP Connection\"");
        if (profilesBefore != null) {
            Thread.sleep(100);
            assertThat(temporaryProfiles()).containsExactlyInAnyOrderElementsOf(profilesBefore);
        }
    }

    @Test void convertsDelayedPageThroughCanonicalMhtmlPdfAndPptxEndpoints() throws Exception {
        long started = System.nanoTime();
        Response mhtml = post("/MakeMHTML", fixtureBase + "/delayed");
        assertThat(mhtml.status()).withFailMessage("MHTML response: %s", new String(mhtml.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(mhtml.contentType()).startsWith("application/x-mimearchive");
        assertThat(mhtml.disposition()).contains(".mhtml");
        String serialized = new String(mhtml.body(), StandardCharsets.UTF_8);
        assertThat(serialized).containsIgnoringCase("multipart/related");
        assertThat(serialized).contains("delayed chart rendered");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(250));

        Response pdf = post("/MakePDF", fixtureBase + "/delayed");
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.contentType()).startsWith("application/pdf");
        assertThat(pdf.disposition()).contains(".pdf");
        assertThat(pdf.body()).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));

        Response pptx = post("/MakePPTX", fixtureBase + "/delayed");
        assertThat(pptx.status()).isEqualTo(200);
        assertThat(pptx.contentType()).startsWith("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        assertThat(pptx.disposition()).contains(".pptx");
        assertThat(pptx.body()).startsWith((byte) 'P', (byte) 'K', (byte) 3, (byte) 4);
        assertThat(presentationContainsBlueCanvas(pptx.body())).isTrue();
    }

    @Test void enforcesBrowserGetRedirectLimitWhenHeadReturns200() throws Exception {
        Response response = post("/MakeMHTML", fixtureBase + "/redirect/0");
        assertThat(response.status()).withFailMessage("Redirect response: %s", new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(422);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("TOO_MANY_REDIRECTS");
    }

    @Test void rejectsRealTopLevelGet404And500WhenHeadDisagrees() throws Exception {
        for (int status : new int[]{404, 500}) {
            Response response = post("/MakeMHTML", fixtureBase + "/status/" + status);
            assertThat(response.status()).isEqualTo(422);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("TARGET_HTTP_ERROR");
        }
        Response late = post("/MakeMHTML", fixtureBase + "/late-status");
        assertThat(late.status()).isEqualTo(422);
        assertThat(new String(late.body(), StandardCharsets.UTF_8)).contains("TARGET_HTTP_ERROR");
    }

    @Test void extractsDeterministicPageModelFromLocalComplexFixture() throws Exception {
        BrowserFactory browserFactory = application.getBean(BrowserFactory.class);
        DomPageExtractor extractor = new DomPageExtractor(application.getBean(ConverterProperties.class).getPageModel());
        URI source = URI.create(fixtureBase + "/pagemodel");
        try (BrowserSession session = browserFactory.open()) {
            session.driver().get(source.toASCIIString());
            session.driver().executeScript("scrollTo(0, 120)");
            PageModel first = extractor.extract(session.driver(), source);
            PageModel second = extractor.extract(session.driver(), source);
            assertThat(PageModelDiagnostics.safeCanonicalJson(first)).isEqualTo(PageModelDiagnostics.safeCanonicalJson(second));
            assertThat(first.blocks()).extracting(b -> b.type()).contains(BlockType.TEXT, BlockType.IMAGE, BlockType.LIST,
                BlockType.TABLE, BlockType.SVG, BlockType.CANVAS, BlockType.CHART, BlockType.CONTAINER, BlockType.FALLBACK);
            assertThat(first.blocks()).noneMatch(b -> b.textRuns().stream().anyMatch(r -> r.text().contains("hidden-secret")));
            assertThat(first.blocks()).anyMatch(b -> b.transform().transformed());
            assertThat(first.blocks()).anyMatch(b -> b.style().flexContainer());
            assertThat(first.blocks()).anyMatch(b -> b.style().gridContainer());
            assertThat(first.blocks()).anyMatch(b -> b.style().position().equals("absolute"));
            assertThat(first.blocks()).anyMatch(b -> b.clipBounds() != null);
            assertThat(first.geometry().scrollY()).isGreaterThan(0);
            assertThat(first.blocks()).allMatch(b -> b.domOrder() >= 0 && b.visualOrder() >= 0);
            assertThat(first.blocks()).anyMatch(b -> b.textRuns().stream().anyMatch(r -> r.text().contains("direct body text")));
            assertThat(first.blocks()).anyMatch(b -> b.textRuns().stream().anyMatch(r -> r.text().contains("explicitly visible")));
            assertThat(first.blocks()).filteredOn(b -> b.table()!=null).singleElement().satisfies(b ->
                assertThat(b.table().cells()).anyMatch(c -> c.row()==1 && c.column()==1));
        }
    }

    @Test void blocksPrivateSubresourceWithoutExternalNetwork() throws Exception {
        Response response = post("/MakeMHTML", fixtureBase + "/blocked");
        assertThat(response.status()).withFailMessage("Blocked response: %s", new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(403);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("URL_NOT_ALLOWED");
    }

    private Response post(String path, String target) throws Exception {
        String json = "{\"url\":\"" + target + "\"}";
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(applicationBase + path))
            .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(response.statusCode(), response.headers().firstValue("content-type").orElse(""),
            response.headers().firstValue("content-disposition").orElse(""), response.body());
    }

    private void delayed(HttpExchange exchange) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(200, -1); exchange.close(); return; }
        sendHtml(exchange, """
            <!doctype html><html><body><canvas id="chart" width="320" height="180"></canvas>
            <script>setTimeout(() => { const c=document.querySelector('#chart'); const x=c.getContext('2d');
            x.fillStyle='#36c'; x.fillRect(0,0,320,180); const m=document.createElement('div');
            m.id='render-ready'; m.textContent=['delayed',' chart',' rendered'].join(''); document.body.appendChild(m); }, 300);</script>
            </body></html>
            """);
    }

    private void pageModelFixture(HttpExchange exchange) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(200, -1); exchange.close(); return; }
        sendHtml(exchange, """
            <!doctype html><html lang='en'><head><style>
            body{height:1400px;margin:0}.flex{display:flex;gap:8px}.grid{display:grid;grid-template-columns:1fr 1fr}
            .clip{width:140px;height:35px;overflow:hidden;position:relative}.wide{width:260px}.abs{position:absolute;left:20px;top:700px;z-index:5}
            .turn{transform:rotate(2deg)}.hidden{display:none}
            </style></head><body>direct body text
            <article class='flex'><section class='grid'><h1>Article</h1><p>Text <a href='/safe-link?q=local'>link</a></p></section>
            <img src='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=='></article>
            <div class='clip'><div class='wide'>clipped text</div></div><div class='turn'>transformed</div><div class='abs'>absolute</div>
            <ul><li>one</li><li>two</li></ul><table><tr><th rowspan='2'>H</th><th>X</th></tr><tr><td>V</td></tr></table>
            <div style='visibility:hidden'><span style='visibility:visible'>explicitly visible</span></div>
            <svg width='20' height='20'><rect width='20' height='20'/></svg><canvas width='20' height='20'></canvas>
            <div class='chart' data-pptx-chart><canvas width='20' height='20'></canvas></div><video width='20' height='20'></video>
            <p class='hidden'>hidden-secret</p><p data-pptx-ignore>ignored-secret</p><div id='render-ready'>ready</div>
            </body></html>
            """);
    }

    private void blocked(HttpExchange exchange) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(200, -1); exchange.close(); return; }
        sendHtml(exchange, "<!doctype html><html><body><div id='render-ready'>ready</div><img src='http://169.254.169.254/latest/meta-data/'></body></html>");
    }

    private void lateStatus(HttpExchange exchange) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(200, -1); exchange.close(); return; }
        sendHtml(exchange, "<!doctype html><html><body><div id='render-ready'>initial</div>"
            + "<script>setTimeout(()=>location.href='/status/500',250)</script></body></html>");
    }

    private void targetStatus(HttpExchange exchange, int status) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(200, -1); exchange.close(); return; }
        byte[] body = ("status " + status).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(200, -1); exchange.close(); return; }
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void sendHtml(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static boolean presentationContainsBlueCanvas(byte[] bytes) throws IOException {
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            for (var picture : show.getPictureData()) {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(picture.getData()));
                if (image == null) continue;
                for (int y = 0; y < image.getHeight(); y += 4) {
                    for (int x = 0; x < image.getWidth(); x += 4) {
                        int rgb = image.getRGB(x, y);
                        int red = (rgb >>> 16) & 255, green = (rgb >>> 8) & 255, blue = rgb & 255;
                        if (blue > red + 40 && blue > green + 20) return true;
                    }
                }
            }
            return false;
        }
    }

    private static Path findChrome() {
        String configured = System.getenv("OCAPP_E2E_CHROME");
        for (String value : new String[]{configured, "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium", "/usr/bin/google-chrome", "/usr/bin/chromium"}) {
            if (value != null && Files.isExecutable(Path.of(value))) return Path.of(value);
        }
        return null;
    }

    private static Path findDriver(Path chrome) throws IOException {
        if (chrome == null) return null;
        Integer browserMajor = executableMajor(chrome);
        if (browserMajor == null) return null;
        String configured = System.getenv("OCAPP_E2E_CHROMEDRIVER");
        if (configured != null && Files.isExecutable(Path.of(configured))) {
            Path driver = Path.of(configured);
            return browserMajor.equals(executableMajor(driver)) ? driver : null;
        }
        for (String value : new String[]{"/opt/homebrew/bin/chromedriver", "/usr/local/bin/chromedriver", "/usr/bin/chromedriver"}) {
            Path driver = Path.of(value);
            if (Files.isExecutable(driver) && browserMajor.equals(executableMajor(driver))) return driver;
        }
        Path cache = Path.of(System.getProperty("user.home"), ".cache", "selenium", "chromedriver");
        if (!Files.isDirectory(cache)) return null;
        try (var paths = Files.walk(cache)) {
            return paths.filter(Files::isExecutable).filter(path -> browserMajor.equals(executableMajor(path)))
                .max(Comparator.comparing(Path::toString)).orElse(null);
        }
    }

    private static Integer executableMajor(Path executable) {
        try {
            Process process = new ProcessBuilder(executable.toString(), "--version").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) return null;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\b(\\d{2,3})\\.").matcher(output);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
        } catch (Exception ex) { return null; }
    }

    private static Set<Path> temporaryProfiles() throws IOException {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"));
        try (var paths = Files.list(temporary)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("ocapp-chrome-"))
                .collect(Collectors.toSet());
        }
    }

    private record Response(int status, String contentType, String disposition, byte[] body) { }
}

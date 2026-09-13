package com.harupaper.server.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Playwright/Chromium을 사용해 HTML을 PNG 스크린샷으로 변환한다.
 * (docs/server/rendering.md 4절 [기본값])
 *
 * - Browser는 애플리케이션 시작 시 1개 띄워 재사용
 * - BrowserContext는 렌더마다 새로 생성/닫기
 * - JS 비활성화, 네트워크 전부 차단, 타임아웃 30초
 * - 동시 렌더 1개 (직렬 큐)
 */
@Slf4j
@Component
public class PlaywrightRenderer {

    private Playwright playwright;
    private Browser browser;
    private static final int RENDER_TIMEOUT_MS = 30000;
    private static final int VIEWPORT_HEIGHT = 100;  // 임시 높이, fullPage=true로 전체 높이 캡처

    @PostConstruct
    public void init() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch();
            log.info("Playwright browser initialized");
        } catch (Exception e) {
            log.error("Failed to initialize Playwright", e);
            throw new RuntimeException("Playwright initialization failed", e);
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (browser != null) browser.close();
            if (playwright != null) playwright.close();
            log.info("Playwright browser closed");
        } catch (Exception e) {
            log.warn("Error closing Playwright", e);
        }
    }

    /**
     * HTML을 PNG로 캡처한다.
     * - JS 비활성화
     * - 네트워크 전부 차단
     * - fullPage=true로 전체 높이 캡처
     * - 타임아웃 30초
     *
     * @param html          HTML 콘텐츠
     * @param viewportWidth 뷰포트 너비(px) = printableWidthPx
     * @return PNG 바이트 배열
     */
    public synchronized byte[] captureScreenshot(String html, int viewportWidth) {
        try {
            // BrowserContext 생성 (매 렌더마다)
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setJavaScriptEnabled(false)
                    .setViewportSize(viewportWidth, VIEWPORT_HEIGHT)
                    .setDeviceScaleFactor(1.0));

            // 네트워크 차단 (route callback)
            // [확인됨] route에 걸린 요청은 반드시 resume()/abort()/fulfill() 중 하나로 끝내야 한다.
            // 아무것도 호출하지 않으면 요청이 응답 없이 매달려 이미지가 영원히 로드되지 않는다
            // (data: URI도 이 route를 거친다 — rendering.md의 "미검증" 항목, 실사 확인함).
            context.route("**/*", route -> {
                String url = route.request().url();
                if (url.startsWith("data:")) {
                    route.resume();
                } else {
                    // 모든 http/https/file 요청 차단
                    route.abort();
                }
            });

            Page page = context.newPage();
            page.setDefaultTimeout(RENDER_TIMEOUT_MS);

            try {
                // HTML 콘텐츠 설정
                page.setContent(html);

                // 스크린샷 캡처 (fullPage=true로 전체 높이)
                byte[] pngBytes = page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(true));

                log.debug("Screenshot captured: {} bytes, viewport={}x{}",
                        pngBytes.length, viewportWidth, VIEWPORT_HEIGHT);

                return pngBytes;

            } finally {
                page.close();
                context.close();
            }

        } catch (com.microsoft.playwright.PlaywrightException e) {
            log.error("Playwright rendering failed", e);
            throw new RuntimeException("Screenshot capture failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error during rendering", e);
            throw new RuntimeException("Screenshot capture failed: " + e.getMessage(), e);
        }
    }
}

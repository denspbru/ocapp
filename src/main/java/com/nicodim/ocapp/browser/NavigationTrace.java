package com.nicodim.ocapp.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nicodim.ocapp.support.ConversionException;
import java.net.URI;
import java.util.Map;
import org.openqa.selenium.devtools.Command;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.Event;
import org.springframework.http.HttpStatus;

final class NavigationTrace {
    private static final ObjectMapper JSON = new ObjectMapper();

    Recorder observe(DevTools devTools) {
        Recorder recorder = new Recorder();
        devTools.send(new Command<>("Network.enable", Map.of()));
        devTools.addListener(mapEvent("Network.requestWillBeSent"), recorder::request);
        devTools.addListener(mapEvent("Network.responseReceived"), recorder::response);
        return recorder;
    }

    @SuppressWarnings("unchecked")
    private static Event<Map<String, Object>> mapEvent(String method) {
        return new Event<>(method, input -> (Map<String, Object>) input.read(Map.class));
    }

    Result parse(Iterable<String> messages) {
        Recorder recorder = new Recorder();
        try {
            for (String text : messages) {
                JsonNode message = JSON.readTree(text).path("message");
                String method = message.path("method").asText();
                @SuppressWarnings("unchecked")
                Map<String, Object> params = JSON.convertValue(message.path("params"), Map.class);
                if ("Network.requestWillBeSent".equals(method)) recorder.request(params);
                if ("Network.responseReceived".equals(method)) recorder.response(params);
            }
        } catch (Exception ex) {
            throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "NAVIGATION_TRACE_INVALID", "Browser navigation status could not be parsed", ex);
        }
        return recorder.result();
    }

    static final class Recorder {
        private String mainFrame;
        private boolean navigationStarted;
        private int redirects;
        private Integer status;
        private String finalUrl;

        synchronized void request(Map<String, Object> params) {
            if (!"Document".equals(params.get("type"))) return;
            Object requestValue = params.get("request");
            String url = requestValue instanceof Map<?, ?> request ? String.valueOf(request.get("url")) : "";
            if (!isHttp(url)) return;
            String frame = String.valueOf(params.get("frameId"));
            if (mainFrame == null) mainFrame = frame;
            if (!mainFrame.equals(frame)) return;
            if (!navigationStarted) navigationStarted = true;
            else if (params.containsKey("redirectResponse")) redirects++;
            else {
                redirects = 0;
                status = null;
                finalUrl = null;
            }
        }

        @SuppressWarnings("unchecked")
        synchronized void response(Map<String, Object> params) {
            if (!"Document".equals(params.get("type"))) return;
            String frame = String.valueOf(params.get("frameId"));
            Object value = params.get("response");
            if (!(value instanceof Map<?, ?> response)) return;
            Object url = response.get("url");
            if (!isHttp(url == null ? "" : url.toString())) return;
            if (mainFrame == null) mainFrame = frame;
            if (!mainFrame.equals(frame)) return;
            Object statusValue = response.get("status");
            if (statusValue instanceof Number number) status = number.intValue();
            finalUrl = url.toString();
        }

        private static boolean isHttp(String value) {
            try {
                String scheme = URI.create(value).getScheme();
                return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            } catch (IllegalArgumentException ex) { return false; }
        }

        synchronized Result result() {
            if (status == null || status < 100) {
                throw new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "NAVIGATION_STATUS_UNAVAILABLE", "Browser did not report the top-level response status");
            }
            return new Result(status, redirects, finalUrl);
        }
    }

    record Result(int status, int redirects, String finalUrl) { }
}

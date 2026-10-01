package com.nicodim.ocapp.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import com.nicodim.ocapp.support.ConversionException;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.Event;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.json.JsonInput;
import org.mockito.ArgumentCaptor;

class NavigationTraceTest {
    @Test void parsesTopLevelGetStatusAndRedirectsIndependentlyOfHead() {
        NavigationTrace.Result result = new NavigationTrace().parse(List.of(
            event("Network.requestWillBeSent", "\"type\":\"Document\",\"frameId\":\"main\",\"request\":{\"url\":\"http://fixture/start\"}"),
            event("Network.requestWillBeSent", "\"type\":\"Document\",\"frameId\":\"main\",\"request\":{\"url\":\"http://fixture/one\"},\"redirectResponse\":{\"status\":302}"),
            event("Network.requestWillBeSent", "\"type\":\"Document\",\"frameId\":\"main\",\"request\":{\"url\":\"http://fixture/two\"},\"redirectResponse\":{\"status\":301}"),
            event("Network.responseReceived", "\"type\":\"Document\",\"frameId\":\"main\",\"response\":{\"status\":200,\"url\":\"http://fixture/final\"}"),
            event("Network.responseReceived", "\"type\":\"Image\",\"frameId\":\"main\",\"response\":{\"status\":500,\"url\":\"http://fixture/image\"}")));
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.redirects()).isEqualTo(2);
        assertThat(result.finalUrl()).isEqualTo("http://fixture/final");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test void registersGenericCdpListenersAndRejectsMissingOrMalformedTrace() throws Exception {
        DevTools devTools = mock(DevTools.class);
        NavigationTrace.Recorder recorder = new NavigationTrace().observe(devTools);
        assertThat(recorder).isNotNull();
        ArgumentCaptor<Event> events = ArgumentCaptor.forClass(Event.class);
        verify(devTools, times(2)).addListener(events.capture(), any(java.util.function.Consumer.class));
        assertThat(invokeMapper(events.getAllValues().getFirst(), "{\"type\":\"Document\"}"))
            .containsEntry("type", "Document");
        assertThatThrownBy(recorder::result).isInstanceOf(ConversionException.class)
            .extracting("code").isEqualTo("NAVIGATION_STATUS_UNAVAILABLE");
        assertThatThrownBy(() -> new NavigationTrace().parse(List.of("not-json")))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("NAVIGATION_TRACE_INVALID");
    }

    @Test void aNewTopLevelNavigationInvalidatesThePreviousStatusUntilItsResponseArrives() {
        NavigationTrace.Recorder recorder = new NavigationTrace.Recorder();
        recorder.request(Map.of("type", "Document", "frameId", "main", "request", Map.of("url", "http://fixture/one")));
        recorder.response(Map.of("type", "Document", "frameId", "main", "response", Map.of("status", 200, "url", "http://fixture/one")));
        assertThat(recorder.result().status()).isEqualTo(200);
        recorder.request(Map.of("type", "Document", "frameId", "main", "request", Map.of("url", "http://fixture/two")));
        assertThatThrownBy(recorder::result).isInstanceOf(ConversionException.class)
            .extracting("code").isEqualTo("NAVIGATION_STATUS_UNAVAILABLE");
        recorder.response(Map.of("type", "Document", "frameId", "main", "response", Map.of("status", 500, "url", "http://fixture/two")));
        assertThat(recorder.result()).isEqualTo(new NavigationTrace.Result(500, 0, "http://fixture/two"));
    }

    @Test void recorderIgnoresSubframesAndIncompleteResponses() {
        NavigationTrace.Recorder recorder = new NavigationTrace.Recorder();
        recorder.request(Map.of("type", "Image", "frameId", "other"));
        recorder.request(Map.of("type", "Document", "frameId", "main", "request", Map.of("url", "http://fixture/")));
        recorder.response(Map.of("type", "Document", "frameId", "main", "response", "invalid"));
        recorder.response(Map.of("type", "Document", "frameId", "other", "response", Map.of("status", 500, "url", "http://fixture/frame")));
        recorder.response(Map.of("type", "Document", "frameId", "main", "response", Map.of("status", 204, "url", "http://fixture/")));
        assertThat(recorder.result()).isEqualTo(new NavigationTrace.Result(204, 0, "http://fixture/"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeMapper(Event<?> event, String json) throws Exception {
        Method method = Event.class.getDeclaredMethod("getMapper");
        method.setAccessible(true);
        Function<JsonInput, ?> mapper = (Function<JsonInput, ?>) method.invoke(event);
        try (JsonInput input = new Json().newInput(new StringReader(json))) {
            return (Map<String, Object>) mapper.apply(input);
        }
    }

    private static String event(String method, String params) {
        return "{\"message\":{\"method\":\"" + method + "\",\"params\":{" + params + "}}}";
    }
}

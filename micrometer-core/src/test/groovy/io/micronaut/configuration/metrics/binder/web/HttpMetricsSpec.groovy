package io.micronaut.configuration.metrics.binder.web

import groovy.transform.InheritConstructors
import io.micrometer.common.lang.NonNull
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.distribution.HistogramSnapshot
import io.micrometer.core.instrument.search.MeterNotFoundException
import io.micronaut.context.ApplicationContext
import io.micronaut.core.util.CollectionUtils
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Error
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.uri.UriBuilder
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.websocket.WebSocketBroadcaster
import io.micronaut.websocket.WebSocketClient
import io.micronaut.websocket.WebSocketSession
import io.micronaut.websocket.annotation.*
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import spock.lang.Specification
import spock.lang.Unroll

import jakarta.validation.constraints.NotBlank

import static io.micronaut.configuration.metrics.micrometer.MeterRegistryFactory.MICRONAUT_METRICS_BINDERS
import static io.micronaut.configuration.metrics.micrometer.MeterRegistryFactory.MICRONAUT_METRICS_ENABLED
import static io.micronaut.http.HttpStatus.CONFLICT
import static io.micronaut.http.HttpStatus.NOT_FOUND

class HttpMetricsSpec extends Specification {

    @Unroll
    void "test client / server metrics"() {
        when:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [(cfg): setting])
        def context = embeddedServer.applicationContext
        TestClient client = context.getBean(TestClient)

        then:
        client.index() == 'ok'

        when:
        MeterRegistry registry = context.getBean(MeterRegistry)

        Timer serverTimer = registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri', '/test-http-metrics').timer()
        Timer clientTimer = registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('uri', '/test-http-metrics').timer()
        HistogramSnapshot serverSnapshot = serverTimer.takeSnapshot()
        HistogramSnapshot clientSnapshot = clientTimer.takeSnapshot()

        then:
        serverTimer
        serverTimer.count() == 1
        clientTimer.count() == 1
        serverSnapshot.percentileValues().length == serverPercentilesCount
        clientSnapshot.percentileValues().length == clientPercentilesCount

        when: "A request is sent to the root route"

        then:
        client.root() == 'root'
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('uri', 'root').timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri', 'root').timer()

        when: "A request is sent with a uri template"
        String result = client.template("foo")

        then:
        result == 'ok foo'
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('uri', '/test-http-metrics/{id}').timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri', '/test-http-metrics/{id}').timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags('serviceId', 'embedded-server').timer()

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri', '/test-http-metrics/foo').timer()

        then:
        thrown(MeterNotFoundException)

        when: "A request is made that returns an error response"
        client.error()

        then:
        thrown(HttpClientResponseException)

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "409").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "409").timer()

        then:
        noExceptionThrown()

        when: "A request is made that throws an exception"
        client.throwable()

        then:
        thrown(HttpClientResponseException)

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "500").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "500").timer()

        then:
        noExceptionThrown()

        when: "A request is made that throws an exception that is handled"
        client.exceptionHandling()

        then:
        thrown(HttpClientResponseException)

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "400").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "400").timer()

        then:
        noExceptionThrown()

        when: "A request is made that does not match a route"
        HttpResponse response = client.notFound()

        then:
        noExceptionThrown()
        response.status() == NOT_FOUND

        when:
        registry.get(WebMetricsPublisher.METRIC_HTTP_CLIENT_REQUESTS).tags("status", "404").timer()
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags("status", "404").timer()

        then:
        noExceptionThrown()

        cleanup:
        embeddedServer.close()

        where:
        cfg                                                   | setting     | serverPercentilesCount | clientPercentilesCount
        MICRONAUT_METRICS_BINDERS + ".web.client.percentiles" | "0.95,0.99" | 0                      | 2
        MICRONAUT_METRICS_BINDERS + ".web.server.percentiles" | "0.95,0.99" | 2                      | 0
    }

    @Unroll
    void "test getting the beans #cfg #setting"() {
        when:
        ApplicationContext context = ApplicationContext.run([(cfg): setting])

        then:
        context.findBean(ClientRequestMetricRegistryFilter).isPresent() == setting
        context.findBean(ServerRequestMeterRegistryFilter).isPresent() == setting

        cleanup:
        context.close()

        where:
        cfg                           | setting
        MICRONAUT_METRICS_ENABLED     | true
        MICRONAUT_METRICS_ENABLED     | false
        (WebMetricsPublisher.ENABLED) | true
        (WebMetricsPublisher.ENABLED) | false
    }

    void "test websocket"() {
        when:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [(MICRONAUT_METRICS_ENABLED): true])
        MeterRegistry registry = embeddedServer.getApplicationContext().getBean(MeterRegistry)
        createWebSocketClient(embeddedServer.getApplicationContext(), embeddedServer.getPort(), "Travolta")

        then:
        registry.get(WebMetricsPublisher.METRIC_HTTP_SERVER_REQUESTS).tags('uri', '/ws/{username}').timer()
    }

    @ClientWebSocket
    static abstract class TestWebSocketClient implements AutoCloseable {
        abstract void send(@NonNull @NotBlank String message);

        @OnMessage
        void onMessage(String message) {}
    }


    private TestWebSocketClient createWebSocketClient(ApplicationContext context, int port, String username) {
        WebSocketClient webSocketClient = context.getBean(WebSocketClient.class)
        URI uri = UriBuilder.of("ws://localhost")
                .port(port)
                .path("ws")
                .path("{username}")
                .expand(CollectionUtils.mapOf("username", username))
        Publisher<TestWebSocketClient> client = webSocketClient.connect(TestWebSocketClient.class, uri)
        return Flux.from(client).blockFirst()
    }

    @Client('/')
    static interface TestClient {
        @Get
        String root()

        @Get('/test-http-metrics')
        String index()

        @Get("/test-http-metrics/{id}")
        String template(String id)

        @Get("/test-http-metrics/error")
        HttpResponse error()

        @Get("/test-http-metrics/throwable")
        HttpResponse throwable()

        @Get("/test-http-metrics/exception-handling")
        HttpResponse exceptionHandling()

        @Get("/test-http-metrics-not-found")
        HttpResponse notFound()
    }

    @Controller('/')
    static class TestController {
        @Get
        String root() { "root" }

        @Get('/test-http-metrics')
        String index() { "ok" }

        @Get("/test-http-metrics/{id}")
        String template(String id) { "ok " + id }

        @Get("/test-http-metrics/error")
        HttpResponse error() {
            HttpResponse.status(CONFLICT)
        }

        @Get("/test-http-metrics/throwable")
        HttpResponse throwable() {
            throw new RuntimeException("error")
        }

        @Get("/test-http-metrics/exception-handling")
        HttpResponse exceptionHandling() {
            throw new MyException("my custom exception")
        }

        @Error(exception = MyException)
        HttpResponse<?> myExceptionHandler() {
            return HttpResponse.badRequest()
        }
    }

    @ServerWebSocket("/ws/{username}")
    static class TestWSController {

        private final WebSocketBroadcaster broadcaster

        @OnOpen
        Publisher<String> onOpen(String username, WebSocketSession session) {
            return broadcaster.broadcast(String.format("Joined %s!", username))
        }

        @OnMessage
        Publisher<String> onMessage(
                String username,
                String message,
                WebSocketSession session) {
            return broadcaster.broadcast(String.format("[%s] %s", username, message))
        }

        @OnClose
        Publisher<String> onClose(
                String username,
                WebSocketSession session) {
            return broadcaster.broadcast(String.format("Leaving %s!", username))
        }

    }

    @InheritConstructors
    static class MyException extends RuntimeException {
    }
}

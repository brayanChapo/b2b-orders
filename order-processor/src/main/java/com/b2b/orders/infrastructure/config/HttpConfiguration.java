package com.b2b.orders.infrastructure.config;

import com.b2b.orders.infrastructure.http.HttpClientCatalog;
import com.b2b.orders.infrastructure.http.HttpProductCatalog;
import com.b2b.orders.infrastructure.http.HttpRetrier;
import com.b2b.orders.infrastructure.http.RetryPolicy;
import com.b2b.orders.infrastructure.observability.ProcessingMetrics;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpConfiguration {

    @Bean
    HttpRetrier httpRetrier(AppProperties props, ProcessingMetrics metrics) {
        AppProperties.Http http = props.http();
        RetryPolicy policy = new RetryPolicy(http.maxAttempts(), http.initialBackoff(), http.backoffMultiplier(),
                http.jitter(), http.maxRetryAfter());
        return new HttpRetrier(policy, (dependency, code, attempt) -> metrics.retry(dependency));
    }

    @Bean
    HttpClientCatalog clientCatalog(RestClient.Builder builder, HttpRetrier retrier, AppProperties props) {
        return new HttpClientCatalog(restClient(builder, props.clientsApi().baseUrl(), props.http()), retrier);
    }

    @Bean
    HttpProductCatalog productCatalog(RestClient.Builder builder, HttpRetrier retrier, AppProperties props) {
        return new HttpProductCatalog(restClient(builder, props.productsApi().baseUrl(), props.http()), retrier,
                props.http().productParallelism());
    }

    public static RestClient restClient(RestClient.Builder builder, String baseUrl, AppProperties.Http http) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(http.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(http.readTimeout());
        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }
}

package com.tc.uploader;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.File;
import java.io.IOException;

public class HttpAPI {

    private HttpClient client;

    public HttpAPI() {
        client = HttpClientBuilder.create().build();
    }

    public HttpAPI(HttpClient client) {
        this.client = client;
    }

    /**
     * Executes an HTTP GET request.
     */
    protected HttpResponse get(String url, int connectTimeout, int connectionRequestTimeout, int socketTimeout) throws IOException {
        HttpGet request = new HttpGet(url);
        request.setConfig(RequestConfig.custom().setConnectTimeout(connectTimeout).setConnectionRequestTimeout(connectionRequestTimeout).setSocketTimeout(socketTimeout).build());
        return client.execute(request);
    }

    /**
     * Executes an HTTP POST request.
     */
    protected HttpResponse postFile(String name, File file, String url) throws IOException {
        HttpEntity entity = MultipartEntityBuilder.create().addBinaryBody(name, file).build();
        HttpPost request = new HttpPost(url);
        request.setEntity(entity);
        return client.execute(request);
    }
}

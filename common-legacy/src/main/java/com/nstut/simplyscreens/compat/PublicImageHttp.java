package com.nstut.simplyscreens.compat;

import com.nstut.simplyscreens.helpers.UrlSecurity;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;

/** Java 8 HTTP import with the same public-host policy, no redirects and bounded timeouts. */
public final class PublicImageHttp {
    private PublicImageHttp() {}
    public static Response get(String url) throws IOException {
        final HttpURLConnection connection=(HttpURLConnection)UrlSecurity.requirePublicHttpUrl(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        connection.setUseCaches(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent","Simply-Screens/0.10.0");
        connection.setRequestProperty("Accept","image/png,image/jpeg,image/gif,image/*;q=0.8");
        connection.setRequestProperty("Accept-Language","en-US,en;q=0.9");
        try {
            final int status=connection.getResponseCode();
            InputStream stream=status==200?connection.getInputStream():connection.getErrorStream();
            if(stream==null)stream=new ByteArrayInputStream(new byte[0]);
            return new Response(status,new FilterInputStream(stream) {
                @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } }
            });
        } catch(IOException|RuntimeException failure) { connection.disconnect();throw failure; }
    }
    public static final class Response {
        private final int status;private final InputStream body;
        Response(int status,InputStream body) { this.status=status;this.body=body; }
        public int statusCode() { return status; }
        public InputStream body() { return body; }
    }
}

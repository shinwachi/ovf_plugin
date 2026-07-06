package org.ovf.serverconnector.client;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.ovf.serverconnector.model.WorkflowNode;
import org.ovf.serverconnector.model.WorkflowNode.Location;
import org.ovf.serverconnector.model.WorkflowNode.NodeType;
import org.ovf.serverconnector.util.JsonHelper;
import org.ovf.serverconnector.util.WorkflowContentIndex.Field;

/**
 * REST backend that talks to the Flask workflow server. Behavior is
 * unchanged from the previous {@code ServerClient}; this class just
 * isolates the HTTP path from the new {@link LocalServerBackend}.
 */
public class HttpServerBackend implements ServerBackend {

    private final String baseUrl;
    private final boolean allowSelfSigned;
    private volatile SSLSocketFactory trustAllSocketFactory;

    public HttpServerBackend(String baseUrl, boolean allowSelfSigned) {
        this.baseUrl = baseUrl;
        this.allowSelfSigned = allowSelfSigned;
    }

    @Override
    public boolean isHealthy() {
        try {
            String json = httpGet("/api/v1/health");
            Map<String, Object> resp = JsonHelper.parseJsonObject(json);
            return resp != null && "ok".equals(resp.get("status"));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, Object> testConnection() throws IOException {
        String json = httpGet("/api/v1/health");
        Map<String, Object> resp = JsonHelper.parseJsonObject(json);
        if (resp == null || !"ok".equals(resp.get("status"))) {
            throw new IOException("Server responded but did not report status=ok. "
                    + "Body: " + (json == null ? "(empty)" : json));
        }
        return resp;
    }

    @Override
    public List<WorkflowNode> browse(String path) throws IOException {
        String endpoint = "/api/v1/browse";
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            String clean = path.startsWith("/") ? path.substring(1) : path;
            endpoint += "/" + clean;
        }

        String json = httpGet(endpoint);
        Map<String, Object> response = JsonHelper.parseJsonObject(json);
        if (response == null) return new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) response.get("items");
        if (items == null) return new ArrayList<>();

        List<WorkflowNode> nodes = new ArrayList<>();
        for (Object item : items) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) item;
            String name = (String) m.get("name");
            String itemPath = (String) m.get("path");
            String type = (String) m.get("type");
            long size = m.get("size") instanceof Number ? ((Number) m.get("size")).longValue() : 0;
            long modified = m.get("modified") instanceof Number ? ((Number) m.get("modified")).longValue() : 0;

            NodeType nodeType;
            switch (type) {
                case "workflow": nodeType = NodeType.WORKFLOW; break;
                case "directory": nodeType = NodeType.DIRECTORY; break;
                default: nodeType = NodeType.FILE; break;
            }

            nodes.add(new WorkflowNode(name, itemPath, nodeType, Location.SERVER, size, modified));
        }
        return nodes;
    }

    @Override
    public byte[] download(String path) throws IOException {
        String clean = path.startsWith("/") ? path.substring(1) : path;
        return httpGetBytes("/api/v1/download/" + clean);
    }

    @Override
    public void upload(String path, byte[] zipData) throws IOException {
        String clean = path.startsWith("/") ? path.substring(1) : path;
        httpPostBytes("/api/v1/upload-raw/" + clean, zipData);
    }

    @Override
    public void delete(String path) throws IOException {
        String clean = path.startsWith("/") ? path.substring(1) : path;
        httpDelete("/api/v1/delete/" + clean);
    }

    @Override
    public void mkdir(String path) throws IOException {
        String clean = path.startsWith("/") ? path.substring(1) : path;
        httpPost("/api/v1/mkdir/" + clean, "");
    }

    @Override
    public void move(String srcPath, String destPath) throws IOException {
        String src = srcPath.startsWith("/") ? srcPath.substring(1) : srcPath;
        String dest = destPath.startsWith("/") ? destPath.substring(1) : destPath;
        httpPost("/api/v1/move/" + src + "?to=" + URLEncoder.encode(dest, "UTF-8"), "");
    }

    @Override
    public String urlFor(String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String clean = path.startsWith("/") ? path.substring(1) : path;
        return base + "/api/v1/download/" + clean;
    }

    @Override
    public String stateSignature() throws IOException {
        String json = httpGet("/api/v1/state");
        Map<String, Object> resp = JsonHelper.parseJsonObject(json);
        if (resp == null) return "";
        Object v = resp.get("version");
        return v == null ? "" : v.toString();
    }

    @Override
    public List<WorkflowNode> deepSearch(String query, EnumSet<Field> fields) throws IOException {
        List<WorkflowNode> hits = new ArrayList<>();
        if (query == null || query.isEmpty() || fields == null || fields.isEmpty()) {
            return hits;
        }
        // Build comma-separated field list for the server -- the server
        // accepts "title,annotation" and ignores unknown values.
        StringBuilder fld = new StringBuilder();
        if (fields.contains(Field.NODE_TITLE)) fld.append("title");
        if (fields.contains(Field.ANNOTATION)) {
            if (fld.length() > 0) fld.append(",");
            fld.append("annotation");
        }
        String endpoint = "/api/v1/search?q="
                + URLEncoder.encode(query, "UTF-8")
                + "&fields=" + fld.toString();
        String json = httpGet(endpoint);
        Map<String, Object> response = JsonHelper.parseJsonObject(json);
        if (response == null) return hits;
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) response.get("items");
        if (items == null) return hits;
        for (Object o : items) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            String name = (String) m.get("name");
            String path = (String) m.get("path");
            long modified = m.get("modified") instanceof Number
                    ? ((Number) m.get("modified")).longValue() : 0L;
            hits.add(new WorkflowNode(name, path, NodeType.WORKFLOW,
                    Location.SERVER, 0L, modified));
        }
        return hits;
    }

    // ---- HTTP plumbing ----

    private HttpURLConnection openConnection(String endpoint) throws IOException {
        URL url = new URL(baseUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (allowSelfSigned && conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            https.setSSLSocketFactory(getTrustAllSocketFactory());
            https.setHostnameVerifier((host, session) -> true);
        }
        return conn;
    }

    private SSLSocketFactory getTrustAllSocketFactory() throws IOException {
        if (trustAllSocketFactory != null) return trustAllSocketFactory;
        synchronized (this) {
            if (trustAllSocketFactory != null) return trustAllSocketFactory;
            try {
                TrustManager[] tms = new TrustManager[] { new X509TrustManager() {
                    @Override public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                } };
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tms, new java.security.SecureRandom());
                trustAllSocketFactory = ctx.getSocketFactory();
                return trustAllSocketFactory;
            } catch (Exception e) {
                throw new IOException("Could not build trust-all SSL context: " + e.getMessage(), e);
            }
        }
    }

    private String httpGet(String endpoint) throws IOException {
        HttpURLConnection conn = openConnection(endpoint);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        try {
            return readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    private byte[] httpGetBytes(String endpoint) throws IOException {
        HttpURLConnection conn = openConnection(endpoint);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);
        try {
            if (conn.getResponseCode() != 200) {
                throw new IOException("HTTP " + conn.getResponseCode() + ": " + readResponse(conn));
            }
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) baos.write(buf, 0, len);
                return baos.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    private void httpPostBytes(String endpoint, byte[] data) throws IOException {
        HttpURLConnection conn = openConnection(endpoint);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(30000);
        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(data);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + readResponse(conn));
            }
        } finally {
            conn.disconnect();
        }
    }

    private void httpPost(String endpoint, String body) throws IOException {
        HttpURLConnection conn = openConnection(endpoint);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + readResponse(conn));
            }
        } finally {
            conn.disconnect();
        }
    }

    private void httpDelete(String endpoint) throws IOException {
        HttpURLConnection conn = openConnection(endpoint);
        conn.setRequestMethod("DELETE");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + readResponse(conn));
            }
        } finally {
            conn.disconnect();
        }
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        InputStream is;
        try {
            is = conn.getInputStream();
        } catch (IOException e) {
            is = conn.getErrorStream();
        }
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}

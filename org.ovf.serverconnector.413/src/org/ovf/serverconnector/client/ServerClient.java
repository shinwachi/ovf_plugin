package org.ovf.serverconnector.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.ovf.serverconnector.model.WorkflowNode;
import org.ovf.serverconnector.preferences.ServerConfig;
import org.ovf.serverconnector.util.WorkflowContentIndex.Field;

/**
 * Thin facade callers use to talk to the active backend. The actual
 * implementation lives in {@link HttpServerBackend} (REST) or
 * {@link LocalServerBackend} (filesystem). Swapping the active server
 * just swaps the wrapped backend -- callers (actions, drop adapter,
 * content provider) hold a single ServerClient reference and never
 * need to be rewired.
 */
public class ServerClient {

    private volatile ServerBackend backend;
    private volatile String baseUrl;
    private volatile boolean allowSelfSigned;
    private volatile boolean local;

    public ServerClient() {
        this((ServerConfig) null);
    }

    public ServerClient(String baseUrl) {
        this(baseUrl, false);
    }

    public ServerClient(String baseUrl, boolean allowSelfSigned) {
        reconfigure(baseUrl, allowSelfSigned, false);
    }

    /** cfg==null puts the client in "no active server" mode. */
    public ServerClient(ServerConfig cfg) {
        reconfigure(cfg);
    }

    public String getBaseUrl() { return baseUrl; }
    public boolean isAllowSelfSigned() { return allowSelfSigned; }
    public boolean isLocal() { return local; }
    public ServerBackend getBackend() { return backend; }

    /** Legacy reconfigure for HTTP-only callers. */
    public void reconfigure(String baseUrl, boolean allowSelfSigned) {
        reconfigure(baseUrl, allowSelfSigned, false);
    }

    public synchronized void reconfigure(String baseUrl, boolean allowSelfSigned, boolean local) {
        this.baseUrl = baseUrl;
        this.allowSelfSigned = allowSelfSigned;
        this.local = local;
        this.backend = local
                ? new LocalServerBackend(baseUrl)
                : new HttpServerBackend(baseUrl, allowSelfSigned);
    }

    public synchronized void reconfigure(ServerConfig cfg) {
        if (cfg == null) {
            this.baseUrl = "";
            this.allowSelfSigned = false;
            this.local = false;
            this.backend = null;
            return;
        }
        reconfigure(cfg.getUrl(), cfg.isAllowSelfSigned(),
                cfg.getType() == ServerConfig.Type.LOCAL);
    }

    /** True when no server is configured; callers should surface this
     *  as an empty state instead of throwing. */
    public boolean isUnconfigured() { return backend == null; }

    private static IOException unconfigured() {
        return new IOException("No server configured");
    }

    // ---- delegate to backend ----

    public boolean isHealthy() { return backend != null && backend.isHealthy(); }

    public Map<String, Object> testConnection() throws IOException {
        if (backend == null) throw unconfigured();
        return backend.testConnection();
    }

    public List<WorkflowNode> browse(String path) throws IOException {
        if (backend == null) return new ArrayList<WorkflowNode>();
        return backend.browse(path);
    }

    public byte[] download(String path) throws IOException {
        if (backend == null) throw unconfigured();
        return backend.download(path);
    }

    public void upload(String path, byte[] zipData) throws IOException {
        if (backend == null) throw unconfigured();
        backend.upload(path, zipData);
    }

    public void delete(String path) throws IOException {
        if (backend == null) throw unconfigured();
        backend.delete(path);
    }

    public void mkdir(String path) throws IOException {
        if (backend == null) throw unconfigured();
        backend.mkdir(path);
    }

    public void move(String srcPath, String destPath) throws IOException {
        if (backend == null) throw unconfigured();
        backend.move(srcPath, destPath);
    }

    public String urlFor(String path) {
        return backend == null ? "" : backend.urlFor(path);
    }

    public List<WorkflowNode> deepSearch(String query, EnumSet<Field> fields) throws IOException {
        if (backend == null) return Collections.emptyList();
        return backend.deepSearch(query, fields);
    }

    public String stateSignature() throws IOException {
        if (backend == null) return "";
        return backend.stateSignature();
    }
}

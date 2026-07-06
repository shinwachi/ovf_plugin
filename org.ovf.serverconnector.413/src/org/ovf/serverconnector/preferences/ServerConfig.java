package org.ovf.serverconnector.preferences;

import java.util.Objects;

/**
 * Configuration for a single Server Explorer mount. A mount is either
 * a remote HTTP(S) server or a local filesystem directory used as a
 * shared drop location.
 */
public class ServerConfig {

    /** What kind of backend this entry talks to. */
    public enum Type {
        REMOTE,  // url is http:// or https://
        LOCAL    // url is a filesystem path (may be prefixed with file://)
    }

    private String name;
    private String url;
    private boolean allowSelfSigned;
    private Type type = Type.REMOTE;

    public ServerConfig() {
    }

    public ServerConfig(String name, String url, boolean allowSelfSigned) {
        this(name, url, allowSelfSigned, Type.REMOTE);
    }

    public ServerConfig(String name, String url, boolean allowSelfSigned, Type type) {
        this.name = name;
        this.url = url;
        this.allowSelfSigned = allowSelfSigned;
        this.type = type == null ? Type.REMOTE : type;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public boolean isAllowSelfSigned() { return allowSelfSigned; }
    public void setAllowSelfSigned(boolean v) { this.allowSelfSigned = v; }

    public Type getType() { return type == null ? Type.REMOTE : type; }
    public void setType(Type type) { this.type = type == null ? Type.REMOTE : type; }

    public boolean isLocal() { return getType() == Type.LOCAL; }

    /**
     * Returns true if the URL uses HTTPS (only meaningful for REMOTE).
     */
    public boolean isHttps() {
        return getType() == Type.REMOTE
                && url != null && url.toLowerCase().startsWith("https://");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ServerConfig)) return false;
        ServerConfig that = (ServerConfig) o;
        return allowSelfSigned == that.allowSelfSigned
                && Objects.equals(name, that.name)
                && Objects.equals(url, that.url)
                && getType() == that.getType();
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, url, allowSelfSigned, getType());
    }

    @Override
    public String toString() {
        String prefix = getType() == Type.LOCAL ? "local:" : "";
        return name + " (" + prefix + url + ")" + (allowSelfSigned ? " [self-signed OK]" : "");
    }
}

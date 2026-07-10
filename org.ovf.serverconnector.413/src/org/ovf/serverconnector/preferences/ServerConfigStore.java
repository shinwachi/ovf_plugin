package org.ovf.serverconnector.preferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;

import org.ovf.serverconnector.ServerConnectorActivator;
import org.ovf.serverconnector.util.JsonHelper;

/**
 * Loads and saves the list of {@link ServerConfig} entries and the
 * currently active server name to the plugin's preference store.
 *
 * Servers are encoded as a single JSON-array string under
 * {@link PreferenceConstants#P_SERVERS}; the active server's name is held
 * under {@link PreferenceConstants#P_ACTIVE_SERVER}.
 */
public final class ServerConfigStore {

    private ServerConfigStore() {}

    public static List<ServerConfig> getAll() {
        IPreferenceStore store = prefs();
        if (store == null) return new ArrayList<>();
        String json = store.getString(PreferenceConstants.P_SERVERS);
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        List<Object> raw = JsonHelper.parseJsonArray(json);
        if (raw == null) return new ArrayList<>();
        List<ServerConfig> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            ServerConfig c = new ServerConfig(
                    asString(m.get("name")),
                    asString(m.get("url")),
                    Boolean.TRUE.equals(m.get("allowSelfSigned")),
                    parseType(asString(m.get("type"))));
            if (c.getName() != null && !c.getName().isEmpty()
                    && c.getUrl() != null && !c.getUrl().isEmpty()) {
                out.add(c);
            }
        }
        return out;
    }

    public static void setAll(List<ServerConfig> servers) {
        IPreferenceStore store = prefs();
        if (store == null) return;
        List<Object> raw = new ArrayList<>(servers.size());
        for (ServerConfig c : servers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            m.put("url", c.getUrl());
            m.put("allowSelfSigned", c.isAllowSelfSigned());
            m.put("type", c.getType().name());
            raw.add(m);
        }
        store.setValue(PreferenceConstants.P_SERVERS, JsonHelper.toJson(raw));
        flush(store);
    }

    public static String getActiveName() {
        IPreferenceStore store = prefs();
        return store != null ? store.getString(PreferenceConstants.P_ACTIVE_SERVER) : "";
    }

    public static void setActiveName(String name) {
        IPreferenceStore store = prefs();
        if (store == null) return;
        store.setValue(PreferenceConstants.P_ACTIVE_SERVER, name == null ? "" : name);
        flush(store);
    }

    /**
     * On first launch ever (no servers persisted yet AND active name
     * empty), write a "Default" HTTP entry to the store so the user
     * always sees a usable starting point in the Servers page. The URL
     * comes from {@code -Dknime.server.url} when set, otherwise the
     * shared traefik hostname {@code http://your-knime-server.example.com}
     * which both KNIME containers route through.
     *
     * <p>After the first seed the user may freely edit or delete this
     * entry; we never re-seed.
     */
    public static void seedDefaultIfEmpty() {
        IPreferenceStore store = prefs();
        if (store == null) return;
        String json = store.getString(PreferenceConstants.P_SERVERS);
        String active = store.getString(PreferenceConstants.P_ACTIVE_SERVER);
        boolean unseeded = (json == null || json.trim().isEmpty())
                && (active == null || active.isEmpty());
        if (!unseeded) return;
        String url = System.getProperty("knime.server.url", "http://your-knime-server.example.com");
        ServerConfig seed = new ServerConfig("Default", url, false, ServerConfig.Type.REMOTE);
        List<ServerConfig> one = new ArrayList<>(1);
        one.add(seed);
        setAll(one);
        setActiveName(seed.getName());
        System.out.println("[ServerExplorer] Seeded default server: " + seed);
    }

    /**
     * Return the active server, or {@code null} if nothing is configured.
     * Falls back to the first configured server if the active name has
     * gone stale (e.g., the active entry was renamed or removed and no
     * replacement was chosen).
     *
     * <p>Callers must handle the null case: previously we synthesized a
     * "Default" entry pointing at a URL only the plugin author's network
     * could resolve, which stuck fresh installs on a permanent
     * "Connecting..." state and hid any newly-added shares behind the
     * inactive default. Returning null makes the empty state explicit.
     */
    public static ServerConfig getActive() {
        List<ServerConfig> all = getAll();
        String active = getActiveName();
        if (!active.isEmpty()) {
            for (ServerConfig c : all) {
                if (active.equals(c.getName())) return c;
            }
        }
        if (!all.isEmpty()) return all.get(0);
        return null;
    }

    /**
     * Register a callback that fires when the server list or active name
     * changes in the preference store. Returns a remover.
     */
    public static Runnable addChangeListener(Runnable callback) {
        IPreferenceStore store = prefs();
        if (store == null) return () -> {};
        IPropertyChangeListener listener = event -> {
            String p = event.getProperty();
            if (PreferenceConstants.P_SERVERS.equals(p)
                    || PreferenceConstants.P_ACTIVE_SERVER.equals(p)) {
                callback.run();
            }
        };
        store.addPropertyChangeListener(listener);
        return () -> store.removePropertyChangeListener(listener);
    }

    private static IPreferenceStore prefs() {
        try {
            return ServerConnectorActivator.getInstance().getPreferenceStore();
        } catch (Exception e) {
            return null;
        }
    }

    private static void flush(IPreferenceStore store) {
        if (store instanceof IPersistentPreferenceStore) {
            try {
                ((IPersistentPreferenceStore) store).save();
            } catch (java.io.IOException e) {
                System.err.println("[ServerExplorer] Could not save preferences: " + e);
            }
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static ServerConfig.Type parseType(String raw) {
        if (raw == null) return ServerConfig.Type.REMOTE;
        try {
            return ServerConfig.Type.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return ServerConfig.Type.REMOTE;
        }
    }
}

package org.ovf.serverconnector;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * OSGi bundle activator for the OVF Server Explorer plugin.
 */
public class ServerConnectorActivator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "org.ovf.serverconnector";

    private static ServerConnectorActivator instance;

    public static ServerConnectorActivator getInstance() {
        return instance;
    }

    /** Backwards-compatible alias for legacy callers. */
    public static org.osgi.framework.BundleContext getContext() {
        return instance != null ? instance.getBundle().getBundleContext() : null;
    }

    @Override
    public void start(BundleContext bundleContext) throws Exception {
        super.start(bundleContext);
        instance = this;

        String serverUrl = System.getProperty("knime.server.url", "http://your-knime-server.example.com");
        System.out.println("OVF Server Explorer plugin started. "
                + "Legacy fallback URL (only used if never configured): " + serverUrl);
        // We intentionally do NOT seed a default server: it left every
        // fresh install trying to connect to a URL only the plugin author's
        // network resolves, showing a stuck "Connecting..." indicator and
        // hiding freshly-added local shares behind an inactive default.
        // The user configures servers via File > Preferences.
    }

    @Override
    public void stop(BundleContext bundleContext) throws Exception {
        System.out.println("OVF Server Explorer plugin stopping...");
        instance = null;
        super.stop(bundleContext);
    }
}

package org.ovf.serverconnector;

import java.io.File;
import java.net.URL;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.service.datalocation.Location;

/**
 * Where this plugin is installed, and so who else can see it. p2 decides this
 * from the KNIME folder's permissions: a user who can't write to it gets a
 * private copy under their home directory.
 */
public final class InstallInfo {
    private InstallInfo() {}

    /** The jar (or dropin folder) this bundle was loaded from, or null if unknown. */
    public static File bundleFile() {
        try {
            return FileLocator.getBundleFile(ServerConnectorActivator.getInstance().getBundle());
        } catch (Exception e) {
            return null;
        }
    }

    /** The KNIME folder, or null if unknown. */
    public static File knimeFolder() {
        Location loc = Platform.getInstallLocation();
        URL url = loc == null ? null : loc.getURL();
        return url == null || !"file".equals(url.getProtocol()) ? null : new File(url.getPath());
    }

    /** True if the plugin lives outside the KNIME folder, so only this user has it. */
    public static boolean isPrivate() {
        File bundle = bundleFile();
        File knime = knimeFolder();
        if (bundle == null || knime == null) return false;
        try {
            return !bundle.getCanonicalPath().startsWith(knime.getCanonicalPath() + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    public static String describe() {
        return (isPrivate()
                    ? "Installed for your account only. Other people using this KNIME don't have it."
                    : "Installed in the KNIME folder, for everyone who uses this KNIME.")
                + "\nPlugin file: " + bundleFile()
                + "\nKNIME folder: " + knimeFolder();
    }
}

package org.knime.serverconnector.actions;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.osgi.framework.FrameworkUtil;

import org.knime.serverconnector.preferences.ServersPreferencePage;

/**
 * Toolbar action: open Window > Preferences focused on the Server Connector
 * > Servers page. The gear icon (icons/gear.png) is loaded from this plugin
 * via {@link FrameworkUtil}.
 */
public class OpenServersPreferencesAction extends Action {

    private final Shell shell;

    public OpenServersPreferencesAction(Shell shell) {
        super("Configure servers...");
        this.shell = shell;
        setToolTipText("Configure servers (URL, HTTPS, self-signed)");
        ImageDescriptor gear = ImageDescriptor.createFromURL(
                FrameworkUtil.getBundle(getClass()).getEntry("icons/gear.png"));
        if (gear != null) setImageDescriptor(gear);
    }

    @Override
    public void run() {
        PreferencesUtil.createPreferenceDialogOn(shell,
                ServersPreferencePage.PAGE_ID,
                new String[] { ServersPreferencePage.PAGE_ID },
                null).open();
    }
}

package org.ovf.serverconnector.actions;

import org.eclipse.jface.action.Action;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import org.ovf.serverconnector.ServerConnectorActivator;

/**
 * Opens the user guide (help/user-guide.html in this bundle) in KNIME's help
 * window, the same page that Help > Help Contents lists as
 * "OVF Server Explorer User Guide".
 */
public class OpenHelpAction extends Action {

    /** Context id for F1 help; defined in help/contexts.xml. */
    public static final String CONTEXT_VIEW = ServerConnectorActivator.PLUGIN_ID + ".view";
    public static final String CONTEXT_SERVERS = ServerConnectorActivator.PLUGIN_ID + ".servers";
    public static final String CONTEXT_SETTINGS = ServerConnectorActivator.PLUGIN_ID + ".settings";

    private static final String GUIDE = "/" + ServerConnectorActivator.PLUGIN_ID + "/help/user-guide.html";

    public OpenHelpAction() {
        super("User guide");
        setToolTipText("Open the Server Explorer user guide");
        setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_LCL_LINKTO_HELP));
    }

    @Override
    public void run() {
        PlatformUI.getWorkbench().getHelpSystem().displayHelpResource(GUIDE);
    }
}

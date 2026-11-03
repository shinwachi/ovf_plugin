package org.ovf.serverconnector;

import org.eclipse.core.runtime.preferences.ConfigurationScope;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspective;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PerspectiveAdapter;
import org.eclipse.ui.PlatformUI;
import org.osgi.service.prefs.Preferences;

/**
 * Puts the Server Explorer tab next to KNIME Explorer.
 *
 * A {@code perspectiveExtension} only applies to a perspective that has no
 * saved layout, so a user who has already run KNIME would never get the tab.
 * Placement therefore happens here, after the workbench is up. It must not
 * happen while the view is rendering: moving the part from
 * {@code ViewPart.init} blanked the whole window on Reset Perspective.
 *
 * The tab stays in the saved layout on exit, so a KNIME without this plugin
 * shows it with an error. That error can't be avoided, as no code of ours runs
 * there, but the saved label and tooltip are all it has to go on, so they say
 * what is missing.
 */
public class ViewBootstrap implements IStartup {
    private static final String VIEW_ID = "org.ovf.serverconnector.views.ServerExplorerView";
    private static final String EXPLORER_ID = "org.knime.workbench.explorer.view";
    private static final String LABEL = "Server Explorer";
    private static final String LABEL_AT_EXIT = "Server Explorer (needs OVF plugin)";
    private static final String TOOLTIP_AT_EXIT = "This tab comes from the OVF Server Explorer plugin. "
            + "If it shows an error, this KNIME doesn't have the plugin: install it, or close this tab.";
    // Only a normal exit writes this, so a killed session can't look like a closed tab.
    private static final String P_CLOSED = "serverconnector.viewClosedAtExit";
    private static final String P_NOTICE = "serverconnector.privateInstallNoticeShown";

    private boolean checked;  // once-per-session guard

    @Override
    public void earlyStartup() {
        final IWorkbench wb = PlatformUI.getWorkbench();
        wb.addWorkbenchListener(new IWorkbenchListener() {
            @Override public boolean preShutdown(IWorkbench w, boolean forced) {
                try { prepareForExit(w); } catch (Throwable t) {
                    System.err.println("[ViewBootstrap] Exit handling failed (non-fatal): " + t);
                }
                return true;
            }
            @Override public void postShutdown(IWorkbench w) {}
        });
        wb.getDisplay().asyncExec(new Runnable() {
            @Override public void run() {
                try { start(wb); } catch (Throwable t) {
                    System.err.println("[ViewBootstrap] Startup failed (non-fatal): " + t);
                    t.printStackTrace();
                }
            }
        });
    }

    private void start(IWorkbench wb) throws Exception {
        IWorkbenchWindow win = wb.getActiveWorkbenchWindow();
        if (win == null && wb.getWorkbenchWindowCount() > 0) win = wb.getWorkbenchWindows()[0];
        if (win == null) return;
        win.addPerspectiveListener(new PerspectiveAdapter() {
            @Override public void perspectiveActivated(IWorkbenchPage page, IPerspectiveDescriptor d) {
                later(page, false);
            }
            @Override public void perspectiveChanged(IWorkbenchPage page, IPerspectiveDescriptor d, String id) {
                // Reset rebuilds the perspective from its definition, without our tab.
                if (IWorkbenchPage.CHANGE_RESET_COMPLETE.equals(id)) later(page, true);
            }
        });
        ensurePlaced(win);
        setLabels(win, LABEL, null);
        showPrivateInstallNotice(win);
    }

    private void later(final IWorkbenchPage page, final boolean afterReset) {
        page.getWorkbenchWindow().getShell().getDisplay().asyncExec(new Runnable() {
            @Override public void run() {
                try {
                    if (afterReset) place(page.getWorkbenchWindow());
                    else ensurePlaced(page.getWorkbenchWindow());
                } catch (Throwable t) {
                    System.err.println("[ViewBootstrap] Placement failed (non-fatal): " + t);
                }
            }
        });
    }

    /** Adds the tab once per session, unless it was closed when KNIME last exited normally. */
    private void ensurePlaced(IWorkbenchWindow win) throws Exception {
        if (checked) return;
        // No KNIME Explorer here (5.x Modern UI, another perspective): try
        // again when the user switches, and leave this layout alone.
        if (findInPerspective(win, EXPLORER_ID) == null) return;
        checked = true;
        IPreferenceStore prefs = ServerConnectorActivator.getInstance().getPreferenceStore();
        if (!prefs.getBoolean(P_CLOSED)) place(win);
    }

    private void place(IWorkbenchWindow win) throws Exception {
        IWorkbenchPage page = win.getActivePage();
        MUIElement explorer = findInPerspective(win, EXPLORER_ID);
        if (page == null || explorer == null) return;
        // A tab closed in a KNIME without this plugin is hidden, not removed:
        // showView brings it back, so only add one if there's none at all.
        if (findInPerspective(win, VIEW_ID) == null) {
            MUIElement cur = explorer;
            while (cur != null && !(cur instanceof MPartStack)) cur = cur.getParent();
            if (cur == null) return;
            EPartService partService = win.getService(EPartService.class);
            ((MPartStack) cur).getChildren().add(partService.createSharedPart(VIEW_ID));
        }
        page.showView(VIEW_ID, null, IWorkbenchPage.VIEW_CREATE);
    }

    private void prepareForExit(IWorkbench wb) {
        boolean open = false;
        for (IWorkbenchWindow w : wb.getWorkbenchWindows()) {
            for (IWorkbenchPage p : w.getPages()) {
                if (p.findViewReference(VIEW_ID) != null) open = true;
            }
            setLabels(w, LABEL_AT_EXIT, TOOLTIP_AT_EXIT);
        }
        // A session that never showed KNIME Explorer says nothing about the tab.
        if (checked) ServerConnectorActivator.getInstance().getPreferenceStore().setValue(P_CLOSED, !open);
    }

    private void setLabels(IWorkbenchWindow win, String label, String tooltip) {
        EModelService models = win.getService(EModelService.class);
        MWindow window = win.getService(MWindow.class);
        if (models == null || window == null) return;
        MUIElement found = models.find(VIEW_ID, window);
        if (found instanceof MPlaceholder) found = ((MPlaceholder) found).getRef();
        if (found instanceof MPart) {
            ((MPart) found).setLabel(label);
            ((MPart) found).setTooltip(tooltip);
        }
    }

    private MUIElement findInPerspective(IWorkbenchWindow win, String id) {
        EModelService models = win.getService(EModelService.class);
        MPerspective perspective = models.getActivePerspective(win.getService(MWindow.class));
        return perspective == null ? null : models.find(id, perspective);
    }

    /** A wizard install can't ask who it's for, so say once what it did. */
    private void showPrivateInstallNotice(IWorkbenchWindow win) throws Exception {
        if (!InstallInfo.isPrivate()) return;
        // Configuration scope, not the workspace: this is about the account.
        Preferences node = ConfigurationScope.INSTANCE.getNode(ServerConnectorActivator.PLUGIN_ID);
        if (node.getBoolean(P_NOTICE, false)) return;
        node.putBoolean(P_NOTICE, true);
        node.flush();
        MessageDialog.openInformation(win.getShell(), "OVF Server Explorer",
                InstallInfo.describe() + "\n\nTo install it for everyone, an administrator runs "
                + "install-ovf from the installer zip. See INSTALL.txt in that zip.");
    }
}

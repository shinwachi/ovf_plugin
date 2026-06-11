package org.knime.serverconnector;

import java.util.List;

import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.model.application.ui.basic.MStackElement;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * OSGi bundle activator for the KNIME Server Connector plugin.
 *
 * In addition to providing the plugin's preference store, this activator
 * runs a small e4-model fixup on workbench window open: it moves the
 * Server Explorer view's MPart into the same PartStack as KNIME
 * Explorer's view, so the two appear as tabs in the same pane. The
 * plain {@code <perspectiveExtension relationship="stack" ...>} in
 * plugin.xml isn't reliable on the e4 workbench (the e3-compat layer
 * sometimes synthesizes a new PartStack at the top level of the
 * perspective instead of tabbing into the relative view's stack).
 */
public class ServerConnectorActivator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "org.knime.serverconnector";

    private static final String SERVER_EXPLORER_VIEW_ID =
            "org.knime.serverconnector.views.ServerExplorerView";
    private static final String KNIME_EXPLORER_VIEW_ID =
            "org.knime.workbench.explorer.view";

    private static ServerConnectorActivator instance;

    private boolean coStackDone;  // once-per-session guard

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

        String serverUrl = System.getProperty("knime.server.url", "http://knimeserver.wachilab.com");
        System.out.println("KNIME Server Connector plugin started. Server URL: " + serverUrl);

        // First-time-ever seed: ensure the Servers list always shows a
        // usable starting entry instead of being empty after the user
        // experimented with their config.
        try {
            org.knime.serverconnector.preferences.ServerConfigStore.seedDefaultIfEmpty();
        } catch (Throwable t) {
            System.err.println("[ServerConnector] seedDefaultIfEmpty failed: " + t);
        }
    }

    @Override
    public void stop(BundleContext bundleContext) throws Exception {
        System.out.println("KNIME Server Connector plugin stopping...");
        instance = null;
        super.stop(bundleContext);
    }

    /**
     * Move our view's MPart into KNIME Explorer's PartStack if both are
     * present in the workbench model. Safe to call multiple times.
     * Public so the view can invoke it from {@code createPartControl}
     * via {@code Display.asyncExec}, by which point both views are in
     * the perspective model.
     */
    public void tryCoStack(IWorkbenchWindow window) {
        // Called from ServerExplorerView.init() -- BEFORE the renderer has
        // produced any SWT widget for our MPart. Moving the MStackElement
        // in the model at this stage is purely a model edit: the renderer
        // then materializes the widget in the new parent. Calling this
        // post-createPartControl reparents a live GTK widget tree and
        // crashes the JVM with no hs_err (silent SIGABRT in GTK).
        if (window == null || coStackDone) return;
        try {
            EModelService modelService = window.getService(EModelService.class);
            MWindow mwindow = window.getService(MWindow.class);
            if (modelService == null || mwindow == null) return;

            MUIElement ourElt = modelService.find(SERVER_EXPLORER_VIEW_ID, mwindow);
            MUIElement knimeElt = modelService.find(KNIME_EXPLORER_VIEW_ID, mwindow);
            if (ourElt == null || knimeElt == null) {
                System.out.println("[ServerConnector] Co-stack deferred: "
                        + "ours=" + (ourElt != null) + " knime=" + (knimeElt != null));
                return;
            }
            if (!(ourElt instanceof MStackElement) || !(knimeElt instanceof MStackElement)) {
                System.out.println("[ServerConnector] Co-stack skipped: not stack elements ("
                        + ourElt.getClass().getSimpleName() + " / "
                        + knimeElt.getClass().getSimpleName() + ")");
                return;
            }
            MStackElement ourStackEl = (MStackElement) ourElt;
            MStackElement knimeStackEl = (MStackElement) knimeElt;

            // Walk up from KNIME Explorer's element to find its enclosing
            // PartStack -- that's the tabbed pane we want to join.
            MPartStack targetStack = findEnclosingStack(knimeElt);
            if (targetStack == null) return;

            MPartStack ourStack = findEnclosingStack(ourElt);
            if (ourStack == targetStack) return;  // already there

            // Move our element into the target stack.
            MElementContainer<MUIElement> currentParent = ourElt.getParent();
            if (currentParent != null) {
                currentParent.getChildren().remove(ourElt);
            }
            targetStack.getChildren().add(ourStackEl);

            // Keep KNIME Explorer as the visible tab so the perspective
            // looks unchanged unless the user actively clicked our tab.
            targetStack.setSelectedElement(knimeStackEl);

            // If the old stack is now empty and synthetic, remove it.
            if (ourStack != null
                    && ourStack.getChildren().isEmpty()
                    && ourStack.getParent() != null) {
                @SuppressWarnings({ "unchecked", "rawtypes" })
                MElementContainer<MUIElement> oldParent =
                        (MElementContainer) ourStack.getParent();
                oldParent.getChildren().remove(ourStack);
            }

            coStackDone = true;
            System.out.println("[ServerConnector] Co-stacked Server Explorer with KNIME Explorer "
                    + "in stack " + targetStack.getElementId());
        } catch (Throwable t) {
            System.err.println("[ServerConnector] Co-stack failed (non-fatal): " + t);
            t.printStackTrace();
        }
    }

    private MPartStack findEnclosingStack(MUIElement elt) {
        MUIElement cur = elt;
        while (cur != null) {
            if (cur instanceof MPartStack) return (MPartStack) cur;
            cur = cur.getParent();
        }
        return null;
    }

    @SuppressWarnings("unused")
    private static MUIElement firstChild(List<MUIElement> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }
}

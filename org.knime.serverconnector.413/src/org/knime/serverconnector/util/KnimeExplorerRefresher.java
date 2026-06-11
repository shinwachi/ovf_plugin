package org.knime.serverconnector.util;

import java.lang.reflect.Method;
import java.util.Optional;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Refresh the KNIME Explorer view so it shows workflows newly created or
 * removed on disk by this plugin.
 *
 * KNIME Explorer maintains its own content model on top of
 * {@code ExplorerMountTable} / {@code AbstractContentProvider}, completely
 * independent of Eclipse's {@code IResource} model. Calling
 * {@code ResourcesPlugin.getWorkspace().getRoot().refreshLocal(...)} does
 * not update it.
 *
 * To force the explorer to re-scan the filesystem we have to:
 *   1. call {@code AbstractContentProvider.refresh()} on the LOCAL mount, AND
 *   2. call {@code TreeViewer.refresh()} on the ExplorerView's viewer.
 *
 * Step 1 alone only fires a label-changed event and will not surface newly
 * created directories; step 2 is what triggers a structural re-read.
 *
 * Done via reflection through the explorer bundle's own classloader to avoid
 * a hard {@code Require-Bundle} dependency on
 * {@code org.knime.workbench.explorer.view}.
 */
public final class KnimeExplorerRefresher {

    private static final String EXPLORER_BUNDLE = "org.knime.workbench.explorer.view";
    private static final String MOUNT_TABLE_CLASS = "org.knime.workbench.explorer.ExplorerMountTable";
    private static final String OPEN_ACTION_CLASS = "org.knime.workbench.explorer.view.actions.OpenWorkflowAction";
    private static final String FILE_STORE_CLASS = "org.knime.workbench.explorer.filesystem.AbstractExplorerFileStore";
    private static final String EXPLORER_VIEW_ID = "org.knime.workbench.explorer.view";
    private static final String LOCAL_MOUNT_ID = "LOCAL";

    private KnimeExplorerRefresher() {}

    /**
     * Look up the LOCAL-mount content provider via {@code ExplorerMountTable}.
     * KNIME 5.x exposes {@code getContentProvider(String) -> Optional<...>};
     * 4.1.x has only {@code getMountedContent() -> Map<String, ...>}. This
     * helper hides the difference so the rest of the file is API-agnostic.
     * Returns null if the bundle isn't active or the mount isn't registered.
     */
    private static Object lookupLocalProvider(ClassLoader cl) throws Exception {
        Class<?> mountTableClass = cl.loadClass(MOUNT_TABLE_CLASS);
        try {
            Method m = mountTableClass.getMethod("getContentProvider", String.class);
            Object opt = m.invoke(null, LOCAL_MOUNT_ID);
            if (opt instanceof Optional<?>) {
                Optional<?> o = (Optional<?>) opt;
                return o.isPresent() ? o.get() : null;
            }
            return opt;
        } catch (NoSuchMethodException notIn4x) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> mounts = (java.util.Map<String, Object>)
                    mountTableClass.getMethod("getMountedContent").invoke(null);
            return mounts == null ? null : mounts.get(LOCAL_MOUNT_ID);
        }
    }

    /**
     * Open a local workflow in the KNIME workflow editor by delegating to
     * KNIME's own static helper
     * {@code OpenWorkflowAction.openEditor(AbstractExplorerFileStore)}.
     * The path is relative to the LOCAL mount root (e.g. "/MyWorkflow").
     * Returns true if the call was successfully dispatched; the actual open
     * happens asynchronously on the SWT thread.
     */
    public static boolean openWorkflowInEditor(String relativePath) {
        try {
            ClassLoader cl = findExplorerClassLoader();
            if (cl == null) {
                System.err.println("[ServerExplorer] Cannot open workflow: explorer bundle not active");
                return false;
            }
            Object provider = lookupLocalProvider(cl);
            if (provider == null) return false;

            // Refresh first so a just-extracted workflow is recognized.
            provider.getClass().getMethod("refresh").invoke(provider);

            Method getFileStore = provider.getClass().getMethod("getFileStore", String.class);
            Object fileStore = getFileStore.invoke(provider, relativePath);
            if (fileStore == null) {
                System.err.println("[ServerExplorer] No file store for path: " + relativePath);
                return false;
            }

            Class<?> openAction = cl.loadClass(OPEN_ACTION_CLASS);
            Class<?> storeIface = cl.loadClass(FILE_STORE_CLASS);
            Method openEditor = openAction.getMethod("openEditor", storeIface);

            Display.getDefault().asyncExec(() -> {
                try {
                    openEditor.invoke(null, fileStore);
                    System.out.println("[ServerExplorer] Opened workflow in editor: " + relativePath);
                } catch (Throwable t) {
                    System.err.println("[ServerExplorer] openEditor failed for " + relativePath + ": " + t);
                    t.printStackTrace();
                }
            });
            return true;
        } catch (Throwable t) {
            System.err.println("[ServerExplorer] openWorkflowInEditor failed: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * Refresh the KNIME Explorer asynchronously. Safe to call from any thread.
     * Failures are logged but never thrown -- the on-disk change has already
     * happened by the time we get here, so refresh is best-effort.
     */
    public static void refreshAsync() {
        Job job = Job.create("Refresh KNIME Explorer", monitor -> {
            try {
                refreshContentProvider();
            } catch (Throwable t) {
                System.err.println("[ServerExplorer] KNIME Explorer content refresh failed: " + t);
                t.printStackTrace();
            }
            refreshTreeViewerOnUiThread();
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    private static void refreshContentProvider() throws Exception {
        ClassLoader explorerCL = findExplorerClassLoader();
        if (explorerCL == null) {
            System.err.println("[ServerExplorer] Bundle " + EXPLORER_BUNDLE
                    + " not found or not active; skipping content provider refresh");
            return;
        }

        Object provider = lookupLocalProvider(explorerCL);
        if (provider == null) {
            System.err.println("[ServerExplorer] LOCAL mount point not registered");
            return;
        }
        provider.getClass().getMethod("refresh").invoke(provider);
        System.out.println("[ServerExplorer] Refreshed KNIME Explorer content provider for LOCAL mount");
    }

    private static void refreshTreeViewerOnUiThread() {
        Display display = Display.getDefault();
        if (display == null) return;
        display.asyncExec(() -> {
            try {
                IWorkbench wb = PlatformUI.getWorkbench();
                if (wb == null) return;
                IWorkbenchWindow win = wb.getActiveWorkbenchWindow();
                if (win == null && wb.getWorkbenchWindowCount() > 0) {
                    win = wb.getWorkbenchWindows()[0];
                }
                if (win == null) return;
                IWorkbenchPage page = win.getActivePage();
                if (page == null) return;
                IViewPart view = page.findView(EXPLORER_VIEW_ID);
                if (view == null) {
                    System.out.println("[ServerExplorer] KNIME Explorer view not open; nothing to refresh in viewer");
                    return;
                }
                Object viewer = view.getClass().getMethod("getViewer").invoke(view);
                if (viewer == null) return;
                viewer.getClass().getMethod("refresh").invoke(viewer);
                System.out.println("[ServerExplorer] Refreshed KNIME Explorer tree viewer");
            } catch (Throwable t) {
                System.err.println("[ServerExplorer] KNIME Explorer viewer refresh failed: " + t);
                t.printStackTrace();
            }
        });
    }

    /**
     * Delete a path in the LOCAL mount through KNIME's own file-store delete,
     * which closes any open editors for the workflow, unloads it from the
     * WorkflowManager cache, and refreshes the KNIME Explorer view. This is
     * the same code path KNIME's Delete menu item uses.
     *
     * Avoids the crash seen when raw filesystem removal is followed by a
     * brute-force explorer refresh: KNIME's internal caches still pointed at
     * the now-missing workflow, and the refresh re-entered native code with
     * stale state.
     *
     * Returns true if the delete was dispatched through KNIME, false if we
     * couldn't reach the explorer bundle (caller should fall back to raw FS).
     * Runs synchronously; suitable for SWT UI thread for small workflows.
     */
    public static boolean tryDeleteViaKnime(String relativePath) {
        try {
            ClassLoader cl = findExplorerClassLoader();
            if (cl == null) return false;

            Object provider = lookupLocalProvider(cl);
            if (provider == null) return false;

            Object fileStore = provider.getClass().getMethod("getFileStore", String.class)
                    .invoke(provider, relativePath);
            if (fileStore == null) {
                System.err.println("[ServerExplorer] No file store for path: " + relativePath);
                return false;
            }

            Class<?> ipmClass = cl.loadClass("org.eclipse.core.runtime.IProgressMonitor");
            Method delete = fileStore.getClass().getMethod("delete", int.class, ipmClass);
            delete.invoke(fileStore, 0, null);
            System.out.println("[ServerExplorer] Deleted via KNIME file store: " + relativePath);

            // Ask KNIME to refresh its tree label/icons after the delete.
            try {
                provider.getClass().getMethod("refresh").invoke(provider);
            } catch (Throwable t) {
                System.err.println("[ServerExplorer] Post-delete refresh failed (non-fatal): " + t);
            }
            return true;
        } catch (Throwable t) {
            System.err.println("[ServerExplorer] tryDeleteViaKnime failed: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * Rename / move a path in the LOCAL mount through KNIME's own file-store
     * move. This is the same path KNIME uses internally for rename, so any
     * open editor's input and {@code WorkflowManager} cache are updated
     * cleanly. Avoids the class of crash documented in doc/delete-crash.md.
     *
     * Both paths are relative to the LOCAL mount root and begin with '/'.
     * Returns true if the move was dispatched, false if we couldn't reach
     * the explorer bundle (caller should fall back to a raw rename).
     */
    public static boolean tryRenameViaKnime(String oldPath, String newPath) {
        try {
            ClassLoader cl = findExplorerClassLoader();
            if (cl == null) return false;

            Object provider = lookupLocalProvider(cl);
            if (provider == null) return false;

            Method getFileStore = provider.getClass().getMethod("getFileStore", String.class);
            Object srcStore = getFileStore.invoke(provider, oldPath);
            Object destStore = getFileStore.invoke(provider, newPath);
            if (srcStore == null || destStore == null) return false;

            Class<?> ifileStore = cl.loadClass("org.eclipse.core.filesystem.IFileStore");
            Class<?> ipm = cl.loadClass("org.eclipse.core.runtime.IProgressMonitor");
            Method move = srcStore.getClass().getMethod("move", ifileStore, int.class, ipm);
            move.invoke(srcStore, destStore, 0, null);
            System.out.println("[ServerExplorer] Renamed via KNIME file store: "
                    + oldPath + " -> " + newPath);

            try {
                provider.getClass().getMethod("refresh").invoke(provider);
            } catch (Throwable t) {
                System.err.println("[ServerExplorer] Post-rename refresh failed (non-fatal): " + t);
            }
            return true;
        } catch (Throwable t) {
            System.err.println("[ServerExplorer] tryRenameViaKnime failed: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * Subscribe to LOCAL-mount content changes in KNIME Explorer. The
     * callback runs whenever KNIME's content provider fires its
     * {@code LabelProviderChangedEvent} -- which is what KNIME's
     * create/delete/rename actions all use to notify the view to redraw.
     * That lets our own Server Explorer's workspace branch stay in sync
     * without having to poll or wait for the user to refocus.
     *
     * Returns a removal hook; call it (e.g. from {@code ViewPart.dispose()})
     * to detach the listener. Returns null if the explorer bundle isn't
     * active or the LOCAL mount isn't registered yet -- the caller should
     * retry later (e.g. when the view is brought to front).
     */
    public static Runnable addLocalContentChangeListener(Runnable callback) {
        try {
            ClassLoader cl = findExplorerClassLoader();
            if (cl == null) return null;

            Object provider = lookupLocalProvider(cl);
            if (provider == null) return null;

            ILabelProviderListener listener = event -> {
                try {
                    callback.run();
                } catch (Throwable t) {
                    System.err.println("[ServerExplorer] content-change callback failed: " + t);
                }
            };

            provider.getClass().getMethod("addListener", ILabelProviderListener.class)
                    .invoke(provider, listener);
            System.out.println("[ServerExplorer] Subscribed to KNIME Explorer LOCAL content changes");

            return () -> {
                try {
                    provider.getClass().getMethod("removeListener", ILabelProviderListener.class)
                            .invoke(provider, listener);
                } catch (Throwable t) {
                    System.err.println("[ServerExplorer] remove listener failed: " + t);
                }
            };
        } catch (Throwable t) {
            System.err.println("[ServerExplorer] addLocalContentChangeListener failed: " + t);
            t.printStackTrace();
            return null;
        }
    }

    private static ClassLoader findExplorerClassLoader() {
        Bundle self = FrameworkUtil.getBundle(KnimeExplorerRefresher.class);
        if (self == null || self.getBundleContext() == null) return null;
        for (Bundle b : self.getBundleContext().getBundles()) {
            if (EXPLORER_BUNDLE.equals(b.getSymbolicName()) && b.getState() == Bundle.ACTIVE) {
                BundleWiring wiring = b.adapt(BundleWiring.class);
                if (wiring != null) return wiring.getClassLoader();
            }
        }
        return null;
    }
}

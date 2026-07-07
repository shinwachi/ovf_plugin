package org.ovf.serverconnector.actions;

import java.io.File;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import org.ovf.serverconnector.ServerConnectorActivator;
import org.ovf.serverconnector.client.ServerClient;
import org.ovf.serverconnector.model.WorkflowNode;
import org.ovf.serverconnector.model.WorkflowNode.Location;
import org.ovf.serverconnector.model.WorkflowNode.NodeType;
import org.ovf.serverconnector.preferences.PreferenceConstants;
import org.ovf.serverconnector.util.ZipHelper;

/**
 * Upload a workflow/directory from workspace to server.
 */
public class UploadAction extends Action {

    private final TreeViewer viewer;
    private final ServerClient client;
    private final String workspacePath;

    public UploadAction(TreeViewer viewer, ServerClient client, String workspacePath) {
        super("Upload to Server");
        setToolTipText("Upload selected workspace item to server");
        setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_ETOOL_SAVE_EDIT));
        this.viewer = viewer;
        this.client = client;
        this.workspacePath = workspacePath;
    }

    @Override
    public void run() {
        IStructuredSelection sel = viewer.getStructuredSelection();
        if (sel.isEmpty()) return;

        WorkflowNode node = (WorkflowNode) sel.getFirstElement();
        if (node.getLocation() != Location.WORKSPACE) {
            showError("Select a workspace item to upload.");
            return;
        }
        if (node.getType() != NodeType.WORKFLOW && node.getType() != NodeType.DIRECTORY) {
            showError("Only workflows and directories can be uploaded.");
            return;
        }
        uploadNode(node, null);
    }

    /**
     * Upload {@code node} from workspace to server.
     * @param targetServerPath server path beginning with '/'; or {@code null}
     *     to ask the user (or, if dialogs are suppressed, use "/" + source name).
     */
    public void uploadNode(WorkflowNode node, String targetServerPath) {
        Shell shell = viewer.getControl().getShell();
        IPreferenceStore prefs = prefs();
        boolean hideRename = prefs != null && prefs.getBoolean(PreferenceConstants.P_HIDE_RENAME_PROMPTS);

        if (targetServerPath == null) {
            if (hideRename) {
                targetServerPath = "/" + node.getName();
            } else {
                InputDialog dlg = new InputDialog(shell, "Upload to Server",
                        "Name on server:", node.getName(), null);
                if (dlg.open() != InputDialog.OK) return;
                String serverName = dlg.getValue().trim();
                if (serverName.isEmpty()) return;
                targetServerPath = "/" + serverName;
            }
        }

        // Run the zip+upload on a background Job. For a local-disk backend
        // the work is fast enough that a UI-thread call was invisible, but
        // for a Local Drive server pointing at an SMB or other network
        // filesystem it can block the SWT thread for tens of seconds while
        // each file round-trips over the network -- KNIME appears to freeze.
        final File localDir = new File(workspacePath + node.getPath());
        final String nodeName = node.getName();
        final String finalTarget = targetServerPath;
        final Display display = viewer.getControl().getDisplay();
        final IPreferenceStore fPrefs = prefs;

        Job job = new Job("Upload '" + nodeName + "' to server") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Uploading " + nodeName, IProgressMonitor.UNKNOWN);
                try {
                    System.out.println("[ServerExplorer] Uploading: " + localDir + " -> server:" + finalTarget);
                    byte[] zipData = ZipHelper.zipDirectoryContents(localDir);
                    client.upload(finalTarget, zipData);
                } catch (final Exception e) {
                    System.err.println("[ServerExplorer] Upload failed: " + e.getMessage());
                    e.printStackTrace();
                    display.asyncExec(() -> showError("Upload failed: " + e.getMessage()));
                    return new Status(IStatus.ERROR,
                            ServerConnectorActivator.PLUGIN_ID,
                            "Upload failed", e);
                } finally {
                    monitor.done();
                }
                display.asyncExec(() -> {
                    boolean hideSuccess = fPrefs != null
                            && fPrefs.getBoolean(PreferenceConstants.P_HIDE_SUCCESS_DIALOGS);
                    if (!hideSuccess) {
                        MessageDialogWithToggle dialog = MessageDialogWithToggle.openInformation(shell,
                                "Upload Complete",
                                "Uploaded '" + nodeName + "' to server as '" + finalTarget + "'",
                                "Don't show this again", false, null, null);
                        if (dialog.getToggleState() && fPrefs != null) {
                            fPrefs.setValue(PreferenceConstants.P_HIDE_SUCCESS_DIALOGS, true);
                            flush(fPrefs);
                        }
                    }
                    refreshServerRoot();
                });
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);   // show progress dialog with Cancel
        job.schedule();
    }

    private IPreferenceStore prefs() {
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
                System.err.println("[ServerExplorer] Could not persist preferences: " + e);
            }
        }
    }

    private void refreshServerRoot() {
        Object input = viewer.getInput();
        if (input instanceof WorkflowNode[]) {
            for (WorkflowNode root : (WorkflowNode[]) input) {
                if (root.getLocation() == Location.SERVER) {
                    root.clearChildren();
                    viewer.refresh(root);
                    break;
                }
            }
        }
    }

    private void showError(String msg) {
        MessageDialog.openError(viewer.getControl().getShell(), "Upload Error", msg);
    }
}

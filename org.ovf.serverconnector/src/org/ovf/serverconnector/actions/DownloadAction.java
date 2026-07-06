package org.ovf.serverconnector.actions;

import java.io.File;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import org.ovf.serverconnector.ServerConnectorActivator;
import org.ovf.serverconnector.client.ServerClient;
import org.ovf.serverconnector.model.WorkflowNode;
import org.ovf.serverconnector.model.WorkflowNode.Location;
import org.ovf.serverconnector.preferences.PreferenceConstants;
import org.ovf.serverconnector.util.KnimeExplorerRefresher;
import org.ovf.serverconnector.util.ZipHelper;

/**
 * Download a workflow/directory from server to workspace.
 *
 * Does NOT create an Eclipse IProject for the downloaded workflow.
 * KNIME Explorer uses its own content model based on filesystem scanning;
 * an Eclipse .project file inside a workflow directory prevents KNIME
 * Explorer from classifying it as a workflow (it shows as a plain folder).
 * See doc/knime-explorer-refresh.md.
 */
public class DownloadAction extends Action {

    private final TreeViewer viewer;
    private final ServerClient client;
    private final String workspacePath;

    public DownloadAction(TreeViewer viewer, ServerClient client, String workspacePath) {
        super("Download to Workspace");
        setToolTipText("Download selected server item to workspace");
        setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_ETOOL_SAVEALL_EDIT));
        this.viewer = viewer;
        this.client = client;
        this.workspacePath = workspacePath;
    }

    @Override
    public void run() {
        IStructuredSelection sel = viewer.getStructuredSelection();
        if (sel.isEmpty()) return;

        WorkflowNode node = (WorkflowNode) sel.getFirstElement();
        if (node.getLocation() != Location.SERVER) {
            showError("Select a server item to download.");
            return;
        }
        downloadNode(node, null);
    }

    /**
     * Download {@code node} from the server into the workspace.
     * @param targetName final name in workspace, or {@code null} to ask the
     *     user (or, if dialogs are suppressed, use the source name).
     */
    public void downloadNode(WorkflowNode node, String targetName) {
        Shell shell = viewer.getControl().getShell();
        IPreferenceStore prefs = prefs();
        boolean hideRename = prefs != null && prefs.getBoolean(PreferenceConstants.P_HIDE_RENAME_PROMPTS);

        if (targetName == null) {
            if (hideRename) {
                targetName = node.getName();
            } else {
                InputDialog dlg = new InputDialog(shell, "Download to Workspace",
                        "Name in workspace:", node.getName(), null);
                if (dlg.open() != InputDialog.OK) return;
                targetName = dlg.getValue().trim();
                if (targetName.isEmpty()) return;
            }
        }

        final String finalName = targetName;
        try {
            System.out.println("[ServerExplorer] Downloading: server:" + node.getPath() + " -> workspace/" + finalName);
            byte[] zipData = client.download(node.getPath());
            File targetDir = new File(workspacePath, finalName);

            File staleProject = new File(targetDir, ".project");
            if (staleProject.isFile() && staleProject.delete()) {
                System.out.println("[ServerExplorer] Removed stale .project from " + targetDir);
            }

            ZipHelper.unzipStripRoot(zipData, targetDir);
            System.out.println("[ServerExplorer] Extracted to: " + targetDir);

            boolean hideSuccess = prefs != null && prefs.getBoolean(PreferenceConstants.P_HIDE_SUCCESS_DIALOGS);
            if (!hideSuccess) {
                MessageDialogWithToggle dialog = MessageDialogWithToggle.openInformation(shell,
                        "Download Complete",
                        "Downloaded '" + node.getName() + "' to workspace as '" + finalName + "'",
                        "Don't show this again", false, null, null);
                if (dialog.getToggleState() && prefs != null) {
                    prefs.setValue(PreferenceConstants.P_HIDE_SUCCESS_DIALOGS, true);
                    flush(prefs);
                }
            }
            refreshWorkspaceRoot();
            KnimeExplorerRefresher.refreshAsync();
        } catch (Exception e) {
            System.err.println("[ServerExplorer] Download failed: " + e.getMessage());
            e.printStackTrace();
            showError("Download failed: " + e.getMessage());
        }
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

    private void refreshWorkspaceRoot() {
        Object input = viewer.getInput();
        if (input instanceof WorkflowNode[]) {
            for (WorkflowNode root : (WorkflowNode[]) input) {
                if (root.getLocation() == Location.WORKSPACE) {
                    root.clearChildren();
                    viewer.refresh(root);
                    break;
                }
            }
        }
    }

    private void showError(String msg) {
        MessageDialog.openError(viewer.getControl().getShell(), "Download Error", msg);
    }
}

package org.ovf.serverconnector.actions;

import java.io.File;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import org.ovf.serverconnector.ServerConnectorActivator;
import org.ovf.serverconnector.client.ServerClient;
import org.ovf.serverconnector.model.WorkflowNode;
import org.ovf.serverconnector.model.WorkflowNode.Location;
import org.ovf.serverconnector.preferences.PreferenceConstants;
import org.ovf.serverconnector.util.KnimeExplorerRefresher;

/**
 * Delete selected item from server or workspace.
 *
 * For workspace items we delegate to KNIME's own file-store delete so that
 * any loaded workflow, open editor, or cached explorer node is cleaned up by
 * KNIME itself. Falling back to a raw filesystem rm + brute-force explorer
 * refresh has been seen to crash KNIME (stale references re-touched during
 * tree refresh).
 */
public class DeleteAction extends Action {

    private final TreeViewer viewer;
    private final ServerClient client;
    private final String workspacePath;

    public DeleteAction(TreeViewer viewer, ServerClient client, String workspacePath) {
        super("Delete");
        setToolTipText("Delete selected item");
        setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_ETOOL_DELETE));
        this.viewer = viewer;
        this.client = client;
        this.workspacePath = workspacePath;
    }

    @Override
    public void run() {
        IStructuredSelection sel = viewer.getStructuredSelection();
        if (sel.isEmpty()) return;

        // Collect every selected non-root WorkflowNode. Multi-select is
        // valid; we run each delete sequentially and refresh once at the
        // end.
        java.util.List<WorkflowNode> targets = new java.util.ArrayList<>();
        for (java.util.Iterator<?> it = sel.iterator(); it.hasNext(); ) {
            Object o = it.next();
            if (!(o instanceof WorkflowNode)) continue;
            WorkflowNode n = (WorkflowNode) o;
            if (n.getType() == WorkflowNode.NodeType.ROOT) {
                showError("Cannot delete root nodes.");
                return;
            }
            targets.add(n);
        }
        if (targets.isEmpty()) return;

        if (!suppressDialogs()) {
            String prompt;
            if (targets.size() == 1) {
                WorkflowNode only = targets.get(0);
                prompt = "Delete '" + only.getName() + "' from "
                        + (only.getLocation() == Location.SERVER ? "server" : "workspace") + "?";
            } else {
                prompt = "Delete " + targets.size() + " selected items?";
            }
            boolean confirm = MessageDialog.openConfirm(viewer.getControl().getShell(),
                    "Confirm Delete", prompt);
            if (!confirm) return;
        }

        WorkflowNode anyDeletedRoot = null;
        java.util.List<String> failed = new java.util.ArrayList<>();
        for (WorkflowNode node : targets) {
            try {
                if (node.getLocation() == Location.SERVER) {
                    System.out.println("[ServerExplorer] Deleting from server: " + node.getPath());
                    client.delete(node.getPath());
                } else {
                    System.out.println("[ServerExplorer] Deleting from workspace: " + node.getPath());
                    if (!deleteWorkspaceItem(node)) {
                        failed.add(node.getName() + " (open in editor or file lock)");
                        continue;
                    }
                }
                anyDeletedRoot = findRoot(node);
            } catch (Exception e) {
                System.err.println("[ServerExplorer] Delete failed for "
                        + node.getPath() + ": " + e.getMessage());
                e.printStackTrace();
                failed.add(node.getName() + " (" + e.getMessage() + ")");
            }
        }

        // Refresh OUR own tree once after all deletes. (KNIME Explorer is
        // refreshed inside tryDeleteViaKnime, so we don't trigger a
        // second refresh here.)
        if (anyDeletedRoot != null) {
            // Refresh BOTH roots in case the selection spanned them.
            Object input = viewer.getInput();
            if (input instanceof WorkflowNode[]) {
                for (WorkflowNode r : (WorkflowNode[]) input) {
                    r.clearChildren();
                    viewer.refresh(r);
                }
            } else {
                viewer.refresh();
            }
        }

        if (!failed.isEmpty()) {
            StringBuilder sb = new StringBuilder("Some items could not be deleted:\n\n");
            for (String f : failed) sb.append("• ").append(f).append('\n');
            showError(sb.toString());
        }
    }

    private boolean deleteWorkspaceItem(WorkflowNode node) {
        if (KnimeExplorerRefresher.tryDeleteViaKnime(node.getPath())) {
            return true;
        }
        // Reflection path unavailable (explorer bundle not active, etc.).
        // Fall back to raw filesystem delete. Skip the explorer-refresh
        // call in this branch — it's what crashed previously.
        System.out.println("[ServerExplorer] Falling back to raw FS delete for " + node.getPath());
        File f = new File(workspacePath + node.getPath());
        return deleteRecursive(f);
    }

    private WorkflowNode findRoot(WorkflowNode node) {
        Object input = viewer.getInput();
        if (!(input instanceof WorkflowNode[])) return null;
        for (WorkflowNode r : (WorkflowNode[]) input) {
            if (r.getLocation() == node.getLocation()) return r;
        }
        return null;
    }

    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (!deleteRecursive(c)) return false;
                }
            }
        }
        return !f.exists() || f.delete();
    }

    private void showError(String msg) {
        MessageDialog.openError(viewer.getControl().getShell(), "Delete Error", msg);
    }

    private boolean suppressDialogs() {
        try {
            return ServerConnectorActivator.getInstance().getPreferenceStore()
                    .getBoolean(PreferenceConstants.P_HIDE_DELETE_CONFIRM);
        } catch (Exception e) {
            return false;
        }
    }
}

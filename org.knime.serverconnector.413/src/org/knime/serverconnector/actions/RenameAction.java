package org.knime.serverconnector.actions;

import java.io.File;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Shell;

import org.knime.serverconnector.client.ServerClient;
import org.knime.serverconnector.model.WorkflowNode;
import org.knime.serverconnector.model.WorkflowNode.Location;
import org.knime.serverconnector.model.WorkflowNode.NodeType;
import org.knime.serverconnector.util.KnimeExplorerRefresher;

/**
 * Rename the selected node in place (same parent directory).
 *
 * Workspace path: delegates to KNIME's file-store move so an open editor's
 * input and the WorkflowManager cache are updated cleanly. Falls back to a
 * raw {@code File.renameTo} if the explorer bundle isn't reachable.
 *
 * Server path: POST /api/v1/move/oldPath?to=newPath.
 */
public class RenameAction extends Action {

    private final TreeViewer viewer;
    private final ServerClient client;
    private final String workspacePath;

    public RenameAction(TreeViewer viewer, ServerClient client, String workspacePath) {
        super("Rename...");
        setToolTipText("Rename the selected item");
        this.viewer = viewer;
        this.client = client;
        this.workspacePath = workspacePath;
    }

    @Override
    public void run() {
        IStructuredSelection sel = viewer.getStructuredSelection();
        if (sel.isEmpty()) return;
        Object first = sel.getFirstElement();
        if (!(first instanceof WorkflowNode)) return;
        WorkflowNode node = (WorkflowNode) first;
        if (node.getType() == NodeType.ROOT) {
            showError("Cannot rename a root node.");
            return;
        }

        Shell shell = viewer.getControl().getShell();
        final String oldName = node.getName();
        InputDialog dlg = new InputDialog(shell, "Rename",
                "New name for '" + oldName + "':",
                oldName,
                new IInputValidator() {
                    @Override
                    public String isValid(String s) {
                        if (s == null || s.trim().isEmpty()) return "Name is required.";
                        if (s.contains("/") || s.contains("\\")) return "Slashes are not allowed.";
                        if (s.equals(oldName)) return "";  // empty = silent-disable OK
                        return null;
                    }
                });
        if (dlg.open() != InputDialog.OK) return;
        String newName = dlg.getValue().trim();
        if (newName.isEmpty() || newName.equals(oldName)) return;

        String oldPath = node.getPath();
        String parentPath = parentOf(oldPath);
        String newPath = joinPath(parentPath, newName);

        try {
            if (node.getLocation() == Location.SERVER) {
                System.out.println("[ServerExplorer] Renaming on server: " + oldPath + " -> " + newPath);
                client.move(oldPath, newPath);
                refreshBranch(Location.SERVER);
            } else {
                System.out.println("[ServerExplorer] Renaming in workspace: " + oldPath + " -> " + newPath);
                if (new File(workspacePath + newPath).exists()) {
                    showError("'" + newName + "' already exists.");
                    return;
                }
                if (!KnimeExplorerRefresher.tryRenameViaKnime(oldPath, newPath)) {
                    File src = new File(workspacePath + oldPath);
                    File dst = new File(workspacePath + newPath);
                    if (!src.renameTo(dst)) {
                        showError("Could not rename.");
                        return;
                    }
                    KnimeExplorerRefresher.refreshAsync();
                }
                refreshBranch(Location.WORKSPACE);
            }
        } catch (Exception e) {
            System.err.println("[ServerExplorer] Rename failed: " + e.getMessage());
            e.printStackTrace();
            showError("Rename failed: " + e.getMessage());
        }
    }

    private void refreshBranch(Location loc) {
        Object input = viewer.getInput();
        if (!(input instanceof WorkflowNode[])) return;
        for (WorkflowNode r : (WorkflowNode[]) input) {
            if (r.getLocation() == loc) {
                r.clearChildren();
                viewer.refresh(r);
                break;
            }
        }
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return "/";
        return path.substring(0, slash);
    }

    private static String joinPath(String dir, String name) {
        if (dir.endsWith("/")) return dir + name;
        return dir + "/" + name;
    }

    private void showError(String msg) {
        MessageDialog.openError(viewer.getControl().getShell(), "Rename Error", msg);
    }
}

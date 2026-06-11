package org.knime.serverconnector.actions;

import java.io.File;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import org.knime.serverconnector.client.ServerClient;
import org.knime.serverconnector.model.WorkflowNode;
import org.knime.serverconnector.model.WorkflowNode.Location;
import org.knime.serverconnector.model.WorkflowNode.NodeType;
import org.knime.serverconnector.util.KnimeExplorerRefresher;

/**
 * Create a new folder (workflow group) inside the selected directory on
 * either the server or the workspace side.
 *
 * Resolves the target directory like the drop adapter does:
 *   ROOT or DIRECTORY -> the node itself
 *   WORKFLOW or FILE  -> its parent
 */
public class NewFolderAction extends Action {

    private final TreeViewer viewer;
    private final ServerClient client;
    private final String workspacePath;

    public NewFolderAction(TreeViewer viewer, ServerClient client, String workspacePath) {
        super("New Folder");
        setToolTipText("Create a new folder in the selected location");
        setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_OBJ_FOLDER));
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
        WorkflowNode target = (WorkflowNode) first;
        WorkflowNode parentDir = resolveTargetDir(target);
        if (parentDir == null) return;

        Shell shell = viewer.getControl().getShell();
        InputDialog dlg = new InputDialog(shell, "New Folder",
                "Folder name (in " + locationLabel(parentDir) + "):",
                "new-folder",
                new IInputValidator() {
                    @Override
                    public String isValid(String s) {
                        if (s == null || s.trim().isEmpty()) return "Name is required.";
                        if (s.contains("/") || s.contains("\\")) return "Slashes are not allowed.";
                        return null;
                    }
                });
        if (dlg.open() != InputDialog.OK) return;
        String name = dlg.getValue().trim();

        String newPath = joinPath(parentDir.getPath(), name);
        try {
            if (parentDir.getLocation() == Location.SERVER) {
                System.out.println("[ServerExplorer] Creating server folder: " + newPath);
                client.mkdir(newPath);
                refreshBranch(Location.SERVER);
            } else {
                File f = new File(workspacePath + newPath);
                System.out.println("[ServerExplorer] Creating workspace folder: " + f);
                if (f.exists()) {
                    showError("'" + name + "' already exists at this location.");
                    return;
                }
                if (!f.mkdirs()) {
                    showError("Could not create folder.");
                    return;
                }
                // The filesystem watcher will refresh our tree; also tell
                // KNIME Explorer so the new folder shows up there too.
                refreshBranch(Location.WORKSPACE);
                KnimeExplorerRefresher.refreshAsync();
            }
        } catch (Exception e) {
            System.err.println("[ServerExplorer] New folder failed: " + e.getMessage());
            e.printStackTrace();
            showError("Create folder failed: " + e.getMessage());
        }
    }

    /**
     * Resolve the directory the new folder should be created inside.
     */
    private WorkflowNode resolveTargetDir(WorkflowNode node) {
        if (node.getType() == NodeType.ROOT || node.getType() == NodeType.DIRECTORY) {
            return node;
        }
        WorkflowNode parent = node.getParent();
        if (parent != null) return parent;
        // Fall back to the location's root.
        Object input = viewer.getInput();
        if (input instanceof WorkflowNode[]) {
            for (WorkflowNode r : (WorkflowNode[]) input) {
                if (r.getLocation() == node.getLocation()) return r;
            }
        }
        return null;
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

    private static String joinPath(String dir, String name) {
        if (dir.endsWith("/")) return dir + name;
        return dir + "/" + name;
    }

    private static String locationLabel(WorkflowNode n) {
        return n.getLocation() == Location.SERVER ? "Server" : "Workspace";
    }

    private void showError(String msg) {
        MessageDialog.openError(viewer.getControl().getShell(), "New Folder Error", msg);
    }
}

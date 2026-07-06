package org.ovf.serverconnector.actions;

import java.io.File;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;

import org.ovf.serverconnector.client.ServerClient;
import org.ovf.serverconnector.model.WorkflowNode;
import org.ovf.serverconnector.model.WorkflowNode.Location;
import org.ovf.serverconnector.model.WorkflowNode.NodeType;
import org.ovf.serverconnector.util.KnimeExplorerRefresher;
import org.ovf.serverconnector.util.ZipHelper;

/**
 * Open a workflow in the KNIME editor. For server-side workflows, also
 * downloads them to the workspace first if they are not already present.
 *
 * Uses KNIME's own {@code OpenWorkflowAction.openEditor(fileStore)} via
 * reflection rather than creating an Eclipse IProject + IFile, because
 * an Eclipse .project file inside a workflow directory makes KNIME
 * Explorer show it as a plain folder.
 */
public class OpenWorkflowAction extends Action {

    private final TreeViewer viewer;
    private final ServerClient client;
    private final String workspacePath;

    public OpenWorkflowAction(TreeViewer viewer, ServerClient client, String workspacePath) {
        super("Open Workflow");
        setToolTipText("Download and open workflow in editor");
        this.viewer = viewer;
        this.client = client;
        this.workspacePath = workspacePath;
    }

    @Override
    public void run() {
        IStructuredSelection sel = viewer.getStructuredSelection();
        if (sel.isEmpty()) return;

        WorkflowNode node = (WorkflowNode) sel.getFirstElement();

        if (node.getLocation() == Location.WORKSPACE) {
            openLocalWorkflow(node);
        } else if (node.getLocation() == Location.SERVER) {
            downloadAndOpen(node);
        }
    }

    private void openLocalWorkflow(WorkflowNode node) {
        try {
            String name = node.getName();
            System.out.println("[ServerExplorer] Opening local workflow: " + name);
            if (!KnimeExplorerRefresher.openWorkflowInEditor("/" + name)) {
                showError("Could not open workflow '" + name
                        + "'. Open it from KNIME Explorer instead.");
            }
        } catch (Exception e) {
            System.err.println("[ServerExplorer] Open local workflow failed: " + e.getMessage());
            e.printStackTrace();
            showError("Failed to open workflow: " + e.getMessage());
        }
    }

    private void downloadAndOpen(WorkflowNode node) {
        if (node.getType() != NodeType.WORKFLOW) {
            showError("Select a workflow to open.");
            return;
        }

        try {
            String name = node.getName();
            File targetDir = new File(workspacePath, name);

            if (!targetDir.exists() || !new File(targetDir, "workflow.knime").exists()) {
                System.out.println("[ServerExplorer] Downloading for open: " + node.getPath());
                byte[] zipData = client.download(node.getPath());

                File staleProject = new File(targetDir, ".project");
                if (staleProject.isFile() && staleProject.delete()) {
                    System.out.println("[ServerExplorer] Removed stale .project from " + targetDir);
                }

                ZipHelper.unzipStripRoot(zipData, targetDir);
            }

            // Refresh our own tree so the workspace branch reflects the new file.
            Object input = viewer.getInput();
            if (input instanceof WorkflowNode[]) {
                for (WorkflowNode r : (WorkflowNode[]) input) {
                    if (r.getLocation() == Location.WORKSPACE) {
                        r.clearChildren();
                        viewer.refresh(r);
                        break;
                    }
                }
            }

            // openWorkflowInEditor internally refreshes the LOCAL content
            // provider before looking up the file store, so the just-extracted
            // workflow becomes visible to KNIME Explorer too.
            if (!KnimeExplorerRefresher.openWorkflowInEditor("/" + name)) {
                showError("Downloaded but could not open '" + name
                        + "' automatically. Open it from KNIME Explorer.");
            }
        } catch (Exception e) {
            System.err.println("[ServerExplorer] Download and open failed: " + e.getMessage());
            e.printStackTrace();
            showError("Failed to open workflow: " + e.getMessage());
        }
    }

    private void showError(String msg) {
        MessageDialog.openError(viewer.getControl().getShell(), "Open Error", msg);
    }
}

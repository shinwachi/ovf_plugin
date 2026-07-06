package org.ovf.serverconnector.dnd;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerDropAdapter;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.widgets.Display;

import org.ovf.serverconnector.actions.DownloadAction;
import org.ovf.serverconnector.actions.UploadAction;
import org.ovf.serverconnector.client.ServerClient;
import org.ovf.serverconnector.model.WorkflowNode;
import org.ovf.serverconnector.model.WorkflowNode.Location;
import org.ovf.serverconnector.model.WorkflowNode.NodeType;

/**
 * Drag-and-drop routing for the Server Explorer dual tree.
 *
 * <pre>
 *   source              target               action
 *   ------              ------               ------
 *   SERVER     -->      WORKSPACE            download
 *   WORKSPACE  -->      SERVER               upload
 *   WORKSPACE  -->      WORKSPACE (dir)      local rename/move
 *   SERVER     -->      SERVER    (dir)      remote move via /api/v1/move
 * </pre>
 *
 * Heavy work (download/upload/move) runs in a background Job to keep the UI
 * responsive; tree refresh happens via the existing filesystem watcher and
 * the action's own refresh hooks.
 */
public class WorkflowDropAdapter extends ViewerDropAdapter {

    private final TreeViewer viewer;
    private final ServerClient client;
    private final String workspacePath;
    private final DownloadAction downloadAction;
    private final UploadAction uploadAction;

    public WorkflowDropAdapter(TreeViewer viewer, ServerClient client, String workspacePath,
            DownloadAction downloadAction, UploadAction uploadAction) {
        super(viewer);
        this.viewer = viewer;
        this.client = client;
        this.workspacePath = workspacePath;
        this.downloadAction = downloadAction;
        this.uploadAction = uploadAction;
        setFeedbackEnabled(true);
        setExpandEnabled(true);
    }

    @Override
    public boolean validateDrop(Object target, int operation, TransferData transferType) {
        if (!LocalSelectionTransfer.getTransfer().isSupportedType(transferType)) return false;
        List<WorkflowNode> sources = getDraggedNodes();
        if (sources.isEmpty()) return false;
        // All dragged items must share the same Location -- mixing
        // server + workspace selections in one drop isn't well-defined.
        Location commonLoc = sources.get(0).getLocation();
        for (WorkflowNode s : sources) {
            if (s.getType() == NodeType.ROOT) return false;
            if (s.getLocation() != commonLoc) return false;
        }
        WorkflowNode targetNode = asNode(target);
        if (targetNode == null) return false;
        // Disallow dropping a node onto itself or one of its descendants
        // (only meaningful for same-side; cross-side drops always OK).
        if (commonLoc == targetNode.getLocation()) {
            for (WorkflowNode s : sources) {
                if (isSelfOrDescendant(s, targetNode)) return false;
            }
        }
        return true;
    }

    @Override
    public boolean performDrop(Object data) {
        List<WorkflowNode> sources = getDraggedNodes();
        WorkflowNode targetNode = asNode(getCurrentTarget());
        if (sources.isEmpty() || targetNode == null) return false;

        Location from = sources.get(0).getLocation();
        Location to = targetNode.getLocation();
        boolean any = false;

        for (WorkflowNode source : sources) {
            if (from == Location.SERVER && to == Location.WORKSPACE) {
                downloadAction.downloadNode(source, source.getName());
                any = true;
                continue;
            }
            if (from == Location.WORKSPACE && to == Location.SERVER) {
                String serverPath = joinPath(directoryPathFor(targetNode, Location.SERVER), source.getName());
                uploadAction.uploadNode(source, serverPath);
                any = true;
                continue;
            }
            if (from == Location.WORKSPACE && to == Location.WORKSPACE) {
                any |= moveWorkspace(source, targetNode);
                continue;
            }
            if (from == Location.SERVER && to == Location.SERVER) {
                any |= moveServer(source, targetNode);
                continue;
            }
        }
        return any;
    }

    // ---- helpers --------------------------------------------------

    private List<WorkflowNode> getDraggedNodes() {
        List<WorkflowNode> out = new ArrayList<>();
        Object sel = LocalSelectionTransfer.getTransfer().getSelection();
        if (sel instanceof IStructuredSelection) {
            for (Iterator<?> it = ((IStructuredSelection) sel).iterator(); it.hasNext(); ) {
                Object o = it.next();
                if (o instanceof WorkflowNode) out.add((WorkflowNode) o);
            }
        }
        return out;
    }

    private WorkflowNode asNode(Object o) {
        return o instanceof WorkflowNode ? (WorkflowNode) o : null;
    }

    /**
     * Return the directory path that the target represents:
     *   - ROOT or DIRECTORY: the target's own path
     *   - WORKFLOW / FILE:   the target's parent path
     */
    private String directoryPathFor(WorkflowNode target, Location loc) {
        if (target.getType() == NodeType.ROOT) return "/";
        if (target.getType() == NodeType.DIRECTORY) return target.getPath();
        // Workflow or file: use its parent's path
        WorkflowNode parent = target.getParent();
        return parent != null ? (parent.getType() == NodeType.ROOT ? "/" : parent.getPath()) : "/";
    }

    private static String joinPath(String dir, String name) {
        if (dir.endsWith("/")) return dir + name;
        return dir + "/" + name;
    }

    private boolean isSelfOrDescendant(WorkflowNode source, WorkflowNode target) {
        for (WorkflowNode n = target; n != null; n = n.getParent()) {
            if (n == source) return true;
        }
        return false;
    }

    private boolean moveWorkspace(WorkflowNode source, WorkflowNode target) {
        String destDir = directoryPathFor(target, Location.WORKSPACE);
        File src = new File(workspacePath + source.getPath());
        File dest = new File(workspacePath + joinPath(destDir, source.getName()));
        if (src.equals(dest) || src.equals(dest.getParentFile())) return false;
        if (dest.exists()) {
            showError("'" + dest.getName() + "' already exists at the destination.");
            return false;
        }
        Job job = Job.create("Move " + source.getName(), monitor -> {
            try {
                if (!dest.getParentFile().exists()) dest.getParentFile().mkdirs();
                if (!src.renameTo(dest)) {
                    asyncError("Could not move workflow.");
                }
            } catch (Exception e) {
                asyncError("Move failed: " + e.getMessage());
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        job.schedule();
        return true;
    }

    private boolean moveServer(WorkflowNode source, WorkflowNode target) {
        String destDir = directoryPathFor(target, Location.SERVER);
        String destPath = joinPath(destDir, source.getName());
        if (destPath.equals(source.getPath())) return false;
        Job job = Job.create("Move " + source.getName(), monitor -> {
            try {
                client.move(source.getPath(), destPath);
                // Refresh server branch on the UI thread.
                Display.getDefault().asyncExec(() -> {
                    Object input = viewer.getInput();
                    if (input instanceof WorkflowNode[]) {
                        for (WorkflowNode r : (WorkflowNode[]) input) {
                            if (r.getLocation() == Location.SERVER) {
                                r.clearChildren();
                                viewer.refresh(r);
                                break;
                            }
                        }
                    }
                });
            } catch (Exception e) {
                asyncError("Server move failed: " + e.getMessage());
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        job.schedule();
        return true;
    }

    private void showError(String msg) {
        MessageDialog.openError(viewer.getControl().getShell(), "Drop Error", msg);
    }

    private void asyncError(String msg) {
        Display.getDefault().asyncExec(() -> {
            if (!viewer.getControl().isDisposed()) showError(msg);
        });
    }
}

package org.knime.serverconnector.views;

import java.io.File;
import java.util.List;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;

import org.knime.serverconnector.client.ServerClient;
import org.knime.serverconnector.model.WorkflowNode;
import org.knime.serverconnector.model.WorkflowNode.Location;
import org.knime.serverconnector.model.WorkflowNode.NodeType;

/**
 * Content provider for the dual-root tree (Server + Workspace).
 * Server children are loaded asynchronously to avoid blocking the UI thread.
 */
public class WorkflowTreeContentProvider implements ITreeContentProvider {

    private final ServerClient client;
    private final String workspacePath;
    private TreeViewer viewer;

    public WorkflowTreeContentProvider(ServerClient client, String workspacePath) {
        this.client = client;
        this.workspacePath = workspacePath;
    }

    @Override
    public Object[] getElements(Object inputElement) {
        if (inputElement instanceof WorkflowNode[]) {
            return (WorkflowNode[]) inputElement;
        }
        return new Object[0];
    }

    @Override
    public Object[] getChildren(Object parentElement) {
        if (!(parentElement instanceof WorkflowNode)) return new Object[0];
        WorkflowNode node = (WorkflowNode) parentElement;

        // Workflows are leaves: their internal node directories are KNIME
        // implementation details and shouldn't be exposed in the tree.
        if (node.getType() == NodeType.WORKFLOW) return new Object[0];

        if (!node.isChildrenLoaded()) {
            if (node.getLocation() == Location.SERVER) {
                // Show placeholder and load asynchronously
                if (node.getChildren().isEmpty()) {
                    node.addChild(new WorkflowNode(
                            "Loading...", "", NodeType.FILE, Location.SERVER));
                    loadServerChildrenAsync(node);
                }
                return node.getChildren().toArray();
            } else {
                loadWorkspaceChildren(node);
            }
        }
        return node.getChildren().toArray();
    }

    @Override
    public Object getParent(Object element) {
        if (element instanceof WorkflowNode) {
            return ((WorkflowNode) element).getParent();
        }
        return null;
    }

    @Override
    public boolean hasChildren(Object element) {
        if (element instanceof WorkflowNode) {
            WorkflowNode node = (WorkflowNode) element;
            if (!node.isChildrenLoaded()) {
                return node.hasChildren();
            }
            return !node.getChildren().isEmpty();
        }
        return false;
    }

    @Override
    public void dispose() {}

    @Override
    public void inputChanged(Viewer v, Object oldInput, Object newInput) {
        if (v instanceof TreeViewer) {
            this.viewer = (TreeViewer) v;
        }
    }

    private void loadServerChildrenAsync(final WorkflowNode node) {
        System.out.println("[ServerExplorer] Loading server children async for: " + node.getPath());
        new Thread(() -> {
            try {
                List<WorkflowNode> children = client.browse(node.getPath());
                if (viewer != null && !viewer.getControl().isDisposed()) {
                    viewer.getControl().getDisplay().asyncExec(() -> {
                        node.clearChildren();
                        for (WorkflowNode child : children) {
                            node.addChild(child);
                        }
                        node.setChildrenLoaded(true);
                        viewer.refresh(node);
                        System.out.println("[ServerExplorer] Loaded " + children.size() + " items from server");
                    });
                }
            } catch (Exception e) {
                System.err.println("[ServerExplorer] Failed to browse server: " + e.getMessage());
                if (viewer != null && !viewer.getControl().isDisposed()) {
                    viewer.getControl().getDisplay().asyncExec(() -> {
                        node.clearChildren();
                        node.addChild(new WorkflowNode(
                                "Error: " + e.getMessage(), "", NodeType.FILE, Location.SERVER));
                        node.setChildrenLoaded(true);
                        viewer.refresh(node);
                    });
                }
            }
        }, "ServerExplorer-Load-" + node.getPath()).start();
    }

    private void loadWorkspaceChildren(WorkflowNode node) {
        String fsPath;
        if (node.getType() == NodeType.ROOT) {
            fsPath = workspacePath;
        } else {
            fsPath = workspacePath + node.getPath();
        }

        File dir = new File(fsPath);
        if (!dir.isDirectory()) {
            node.setChildrenLoaded(true);
            return;
        }

        node.clearChildren();
        File[] files = dir.listFiles();
        if (files == null) {
            node.setChildrenLoaded(true);
            return;
        }

        for (File f : files) {
            if (f.getName().startsWith(".")) continue;

            String relPath = node.getPath().equals("/")
                    ? "/" + f.getName()
                    : node.getPath() + "/" + f.getName();

            NodeType type;
            if (f.isDirectory()) {
                if (new File(f, "workflow.knime").exists()) {
                    type = NodeType.WORKFLOW;
                } else {
                    type = NodeType.DIRECTORY;
                }
            } else {
                type = NodeType.FILE;
            }

            node.addChild(new WorkflowNode(
                    f.getName(), relPath, type, Location.WORKSPACE,
                    f.isFile() ? f.length() : 0,
                    f.lastModified()));
        }
        node.setChildrenLoaded(true);
    }
}

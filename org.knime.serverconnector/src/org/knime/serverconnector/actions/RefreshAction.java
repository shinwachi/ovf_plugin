package org.knime.serverconnector.actions;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import org.knime.serverconnector.model.WorkflowNode;

/**
 * Refresh the tree or a selected subtree.
 */
public class RefreshAction extends Action {

    private final TreeViewer viewer;

    public RefreshAction(TreeViewer viewer) {
        super("Refresh");
        setToolTipText("Refresh tree contents");
        setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
                .getImageDescriptor(ISharedImages.IMG_ELCL_SYNCED));
        this.viewer = viewer;
    }

    @Override
    public void run() {
        IStructuredSelection sel = viewer.getStructuredSelection();
        if (!sel.isEmpty() && sel.getFirstElement() instanceof WorkflowNode) {
            WorkflowNode node = (WorkflowNode) sel.getFirstElement();
            node.clearChildren();
            viewer.refresh(node);
        } else {
            // Refresh entire tree - clear all cached children
            Object input = viewer.getInput();
            if (input instanceof WorkflowNode[]) {
                for (WorkflowNode root : (WorkflowNode[]) input) {
                    root.clearChildren();
                }
            }
            viewer.refresh();
        }
    }
}

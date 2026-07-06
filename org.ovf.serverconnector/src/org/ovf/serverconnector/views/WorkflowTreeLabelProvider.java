package org.ovf.serverconnector.views;

import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

import org.ovf.serverconnector.model.WorkflowNode;

/**
 * Label provider for the workflow tree.
 * Shows appropriate icons for workflows, directories, and files.
 */
public class WorkflowTreeLabelProvider extends LabelProvider {

    @Override
    public String getText(Object element) {
        if (element instanceof WorkflowNode) {
            return ((WorkflowNode) element).getName();
        }
        return super.getText(element);
    }

    @Override
    public Image getImage(Object element) {
        if (!(element instanceof WorkflowNode)) return null;

        WorkflowNode node = (WorkflowNode) element;
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();

        switch (node.getType()) {
            case ROOT:
                return images.getImage(ISharedImages.IMG_OBJ_FOLDER);
            case DIRECTORY:
                return images.getImage(ISharedImages.IMG_OBJ_FOLDER);
            case WORKFLOW:
                // Use a project icon to distinguish workflows
                return images.getImage(ISharedImages.IMG_OBJ_ELEMENT);
            case FILE:
                return images.getImage(ISharedImages.IMG_OBJ_FILE);
            default:
                return null;
        }
    }
}

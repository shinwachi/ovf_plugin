package org.ovf.serverconnector.actions;

import java.util.Iterator;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;

import org.ovf.serverconnector.client.ServerClient;
import org.ovf.serverconnector.model.WorkflowNode;
import org.ovf.serverconnector.model.WorkflowNode.Location;
import org.ovf.serverconnector.model.WorkflowNode.NodeType;

/**
 * Copy the URL of the selected node(s) to the system clipboard.
 *
 *   Server item    -> {@code <baseUrl>/api/v1/download/<path>}
 *   Workspace item -> {@code knime://LOCAL/<path>}  (KNIME's native LOCAL-mount URI)
 *
 * Multi-selection produces newline-separated URLs.
 */
public class CopyUrlAction extends Action {

    private final TreeViewer viewer;
    private final ServerClient client;

    public CopyUrlAction(TreeViewer viewer, ServerClient client, String workspacePath) {
        super("Copy URL Location");
        setToolTipText("Copy the URL of the selected item to the clipboard");
        this.viewer = viewer;
        this.client = client;
        // workspacePath is accepted for signature parity with the other
        // actions but unused: LOCAL-mount URIs are derived from node.getPath().
    }

    @Override
    public void run() {
        IStructuredSelection sel = viewer.getStructuredSelection();
        if (sel.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (Iterator<?> it = sel.iterator(); it.hasNext(); ) {
            Object o = it.next();
            if (!(o instanceof WorkflowNode)) continue;
            WorkflowNode node = (WorkflowNode) o;
            if (node.getType() == NodeType.ROOT) continue;
            String url = urlFor(node);
            if (url == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(url);
        }
        if (sb.length() == 0) return;

        Clipboard cb = new Clipboard(Display.getCurrent());
        try {
            cb.setContents(new Object[] { sb.toString() },
                    new Transfer[] { TextTransfer.getInstance() });
            System.out.println("[ServerExplorer] Copied URL: " + sb);
        } finally {
            cb.dispose();
        }
    }

    private String urlFor(WorkflowNode node) {
        if (node.getLocation() == Location.SERVER) {
            // Delegates to the active backend: HTTP server -> download URL,
            // local filesystem mount -> file:// URI.
            return client.urlFor(node.getPath());
        } else {
            // KNIME's native URI for items in the LOCAL workspace mount.
            // Reader/Writer nodes accept this directly.
            String path = node.getPath();
            if (!path.startsWith("/")) path = "/" + path;
            return "knime://LOCAL" + path;
        }
    }
}

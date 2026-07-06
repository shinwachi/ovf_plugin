package org.ovf.serverconnector.views;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

import org.ovf.serverconnector.model.WorkflowNode;
import org.ovf.serverconnector.model.WorkflowNode.Location;
import org.ovf.serverconnector.model.WorkflowNode.NodeType;

/**
 * Case-insensitive substring filter on {@link WorkflowNode#getName()},
 * plus an optional deep-content match set populated by a background
 * scan (workflow.knime annotations + per-node settings.xml titles).
 *
 * <p>Pass rules:
 * <ul>
 *   <li>Empty pattern: pass everything.
 *   <li>Root nodes: always pass.
 *   <li>Otherwise: pass iff the node's name matches OR any descendant
 *       matches OR the node is a deep-content match OR any ancestor /
 *       descendant of the deep-match set lies on the path.
 * </ul>
 *
 * <p>The deep-match set is the full set of workflow paths the deep
 * scan returned for each side (server vs. workspace). The filter
 * pre-expands that set with ancestor directories so the surrounding
 * folder structure stays visible.
 */
public class WorkflowSearchFilter extends ViewerFilter {

    private String pattern = "";
    private Set<String> serverAllow = Collections.emptySet();
    private Set<String> workspaceAllow = Collections.emptySet();

    public void setPattern(String p) {
        this.pattern = p == null ? "" : p.toLowerCase();
    }

    /**
     * Update the deep-match allow-list. Each path should be a workflow
     * path that matched the deep search; this method expands the set
     * with all ancestor directories so they stay visible.
     */
    public void setDeepMatches(Set<String> serverWorkflows, Set<String> workspaceWorkflows) {
        this.serverAllow = expandAncestors(serverWorkflows);
        this.workspaceAllow = expandAncestors(workspaceWorkflows);
    }

    public boolean isActive() {
        return !pattern.isEmpty();
    }

    public boolean hasDeepMatches() {
        return !serverAllow.isEmpty() || !workspaceAllow.isEmpty();
    }

    @Override
    public boolean select(Viewer viewer, Object parent, Object element) {
        if (pattern.isEmpty()) return true;
        if (!(element instanceof WorkflowNode)) return true;
        WorkflowNode node = (WorkflowNode) element;
        if (node.getType() == NodeType.ROOT) return true;

        if (nameMatches(node)) return true;
        if (deepAllows(node)) return true;
        return hasMatchingDescendant(node);
    }

    private boolean nameMatches(WorkflowNode n) {
        return n.getName().toLowerCase().contains(pattern);
    }

    private boolean deepAllows(WorkflowNode n) {
        Set<String> allow = n.getLocation() == Location.WORKSPACE ? workspaceAllow : serverAllow;
        if (allow.isEmpty()) return false;
        String path = n.getPath();
        if (path == null) return false;
        // Direct match (the node itself is in the allow-set, e.g. a
        // matched workflow OR an ancestor we expanded into).
        if (allow.contains(path)) return true;
        // Descendant-of-match: keep subtrees of matched workflows expanded.
        for (String allowed : allow) {
            if (path.startsWith(allowed + "/")) return true;
        }
        return false;
    }

    private boolean hasMatchingDescendant(WorkflowNode n) {
        // Workflows are leaves; never descend.
        if (n.getType() == NodeType.WORKFLOW) return false;
        for (WorkflowNode child : n.getChildren()) {
            if (nameMatches(child)) return true;
            if (deepAllows(child)) return true;
            if (hasMatchingDescendant(child)) return true;
        }
        return false;
    }

    private static Set<String> expandAncestors(Set<String> paths) {
        if (paths == null || paths.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>(paths.size() * 2);
        for (String p : paths) {
            if (p == null || p.isEmpty()) continue;
            String norm = p.startsWith("/") ? p : "/" + p;
            out.add(norm);
            int slash = norm.lastIndexOf('/');
            while (slash > 0) {
                out.add(norm.substring(0, slash));
                slash = norm.lastIndexOf('/', slash - 1);
            }
            out.add("/");
        }
        return out;
    }
}

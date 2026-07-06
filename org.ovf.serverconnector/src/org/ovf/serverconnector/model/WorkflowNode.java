package org.ovf.serverconnector.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a node in the workflow tree - either a workflow, directory, or file.
 * Used by both the server tree and the local workspace tree.
 */
public class WorkflowNode {

    public enum NodeType {
        ROOT,
        DIRECTORY,
        WORKFLOW,
        FILE
    }

    public enum Location {
        SERVER,
        WORKSPACE
    }

    private final String name;
    private final String path;
    private final NodeType type;
    private final Location location;
    private final long size;
    private final long modified;
    private final List<WorkflowNode> children = new ArrayList<>();
    private WorkflowNode parent;
    private boolean childrenLoaded = false;

    public WorkflowNode(String name, String path, NodeType type, Location location) {
        this(name, path, type, location, 0, 0);
    }

    public WorkflowNode(String name, String path, NodeType type, Location location,
                         long size, long modified) {
        this.name = name;
        this.path = path;
        this.type = type;
        this.location = location;
        this.size = size;
        this.modified = modified;
    }

    public String getName() { return name; }
    public String getPath() { return path; }
    public NodeType getType() { return type; }
    public Location getLocation() { return location; }
    public long getSize() { return size; }
    public long getModified() { return modified; }
    public WorkflowNode getParent() { return parent; }
    public boolean isChildrenLoaded() { return childrenLoaded; }

    public List<WorkflowNode> getChildren() { return children; }

    public void addChild(WorkflowNode child) {
        child.parent = this;
        children.add(child);
    }

    public void clearChildren() {
        children.clear();
        childrenLoaded = false;
    }

    public void setChildrenLoaded(boolean loaded) {
        this.childrenLoaded = loaded;
    }

    public boolean hasChildren() {
        // Workflows are leaves in the Server Explorer tree. Their internal
        // node directories (e.g. "CSV Reader (#267)") are KNIME implementation
        // details and shouldn't be exposed as browseable folder contents.
        return type == NodeType.ROOT || type == NodeType.DIRECTORY;
    }

    @Override
    public String toString() {
        return name;
    }
}

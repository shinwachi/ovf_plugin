package org.ovf.serverconnector.client;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.ovf.serverconnector.model.WorkflowNode;
import org.ovf.serverconnector.util.WorkflowContentIndex.Field;

/**
 * Backend-agnostic operations exposed by Server Explorer. Implemented by
 * {@link HttpServerBackend} (talks to the Flask workflow server) and
 * {@link LocalServerBackend} (filesystem mount used as a shared drop
 * location). All paths are server-relative and begin with '/'.
 *
 * Upload/download use a zipped workflow as the transport so the caller
 * never needs to branch on backend kind -- {@code DownloadAction} writes
 * the zip; {@code UploadAction} hands one in. The local backend zips
 * directories on demand and unzips into the mount root.
 */
public interface ServerBackend {
    boolean isHealthy();

    /** Like {@link #isHealthy()} but throws so callers can show why. */
    Map<String, Object> testConnection() throws IOException;

    List<WorkflowNode> browse(String path) throws IOException;

    byte[] download(String path) throws IOException;

    void upload(String path, byte[] zipData) throws IOException;

    void delete(String path) throws IOException;

    void mkdir(String path) throws IOException;

    void move(String srcPath, String destPath) throws IOException;

    /**
     * Stable URL/string for the given path, used by Copy URL Location.
     * HTTP backends return a download URL; local backends return a
     * {@code file://} URI rooted at the mount.
     */
    String urlFor(String path);

    /**
     * Walk every workflow under the backend's root looking for one
     * whose chosen content fields contain {@code query} (case-insensitive
     * substring). Returns matching workflow paths as
     * {@link WorkflowNode}s with type {@code WORKFLOW}, ready to be
     * surfaced in the tree.
     *
     * <p>Backends decide how to scan: HTTP delegates to a server-side
     * endpoint so the client doesn't download every workflow; local
     * walks the filesystem. An empty {@code fields} or empty
     * {@code query} returns an empty list.
     */
    List<WorkflowNode> deepSearch(String query, EnumSet<Field> fields) throws IOException;

    /**
     * Cheap content-change signature for change-polling clients.
     * Equal signatures mean the backend's tree state is unchanged since
     * the last call. Format is backend-specific (only equality matters).
     * Empty string is a valid "unknown" answer that suppresses refresh.
     */
    String stateSignature() throws IOException;
}

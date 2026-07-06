package org.ovf.serverconnector.views;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;

import org.ovf.serverconnector.actions.CopyUrlAction;
import org.ovf.serverconnector.actions.DeleteAction;
import org.ovf.serverconnector.actions.DownloadAction;
import org.ovf.serverconnector.actions.NewFolderAction;
import org.ovf.serverconnector.actions.OpenServersPreferencesAction;
import org.ovf.serverconnector.actions.OpenWorkflowAction;
import org.ovf.serverconnector.actions.RefreshAction;
import org.ovf.serverconnector.actions.RenameAction;
import org.ovf.serverconnector.actions.UploadAction;
import org.ovf.serverconnector.client.ServerClient;
import org.ovf.serverconnector.dnd.WorkflowDropAdapter;
import org.ovf.serverconnector.model.WorkflowNode;
import org.ovf.serverconnector.model.WorkflowNode.Location;
import org.ovf.serverconnector.model.WorkflowNode.NodeType;
import org.ovf.serverconnector.ServerConnectorActivator;
import org.ovf.serverconnector.preferences.ServerConfig;
import org.ovf.serverconnector.preferences.ServerConfigStore;
import org.ovf.serverconnector.util.KnimeExplorerRefresher;
import org.ovf.serverconnector.util.WorkflowContentIndex;
import org.ovf.serverconnector.util.WorkflowContentIndex.Field;

/**
 * Server Explorer View - a dual-pane tree showing both the remote server's
 * workflow repository and the local KNIME workspace. Users can browse, upload,
 * download, and delete workflows between the two.
 *
 * Open via: Window > Show View > Other > Server Connector > Server Explorer
 */
public class ServerExplorerView extends ViewPart {

    public static final String ID = "org.ovf.serverconnector.views.ServerExplorerView";

    private TreeViewer viewer;
    private ServerClient client;
    private String workspacePath;

    // Actions
    private UploadAction uploadAction;
    private DownloadAction downloadAction;
    private DeleteAction deleteAction;
    private RefreshAction refreshAction;
    private OpenWorkflowAction openWorkflowAction;
    private NewFolderAction newFolderAction;
    private RenameAction renameAction;
    private CopyUrlAction copyUrlAction;
    private OpenServersPreferencesAction openServersPrefsAction;
    private Runnable serverConfigRemover;
    private ServerConfig currentServer;

    private IPartListener2 partListener;
    private Runnable contentListenerRemover;
    private Thread watcherThread;
    private volatile boolean watcherRunning;
    // Background poll of the active server's content signature so
    // changes made by other clients (other KNIME instances writing to
    // the same backend) appear without the user clicking refresh.
    // Cadence: 10s while the view is the focused part, 60s otherwise.
    private Job statePollJob;
    private volatile String lastServerSignature = "";
    private volatile boolean viewFocused;
    private static final long POLL_FOCUSED_MS = 10_000L;
    private static final long POLL_UNFOCUSED_MS = 60_000L;
    private WorkflowSearchFilter searchFilter;
    private Text searchText;
    private Object[] preSearchExpansion;
    private Label statusLabel;
    // Deep search options: when both off, only names are searched.
    private boolean searchTitles;
    private boolean searchAnnotations;
    // Generation counter so an in-flight scan's result is dropped if
    // the user typed more characters after we kicked off the scan.
    private final AtomicInteger searchGeneration = new AtomicInteger(0);

    @Override
    public void init(IViewSite site, IMemento memento) throws PartInitException {
        super.init(site, memento);
        // Move our MPart into KNIME Explorer's PartStack *before* the
        // renderer creates any SWT widgets. Reparenting in the model
        // here is purely a tree edit; the renderer then materializes the
        // widget under the new parent. Doing the same move after
        // createPartControl reparents a live GTK widget tree and SIGABRTs
        // the JVM with no hs_err.
        try {
            ServerConnectorActivator.getInstance().tryCoStack(site.getWorkbenchWindow());
        } catch (Throwable t) {
            System.err.println("[ServerExplorer] Co-stack at init() failed (non-fatal): " + t);
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        System.out.println("[ServerExplorer] createPartControl called");

        // Determine workspace path from KNIME runtime. On Windows the URL
        // form is file:/C:/Users/... and URL.getPath() yields
        // "/C:/Users/..." which is not a valid java.nio.file.Path
        // (java.nio.file.InvalidPathException at index 2 on the colon).
        // Convert via File(URI) which does the platform-correct thing.
        try {
            java.net.URL locUrl = org.eclipse.core.runtime.Platform
                    .getInstanceLocation().getURL();
            java.io.File dir;
            try {
                dir = new java.io.File(locUrl.toURI());
            } catch (Exception uriEx) {
                String raw = locUrl.getPath();
                if (raw.length() > 3 && raw.charAt(0) == '/'
                        && raw.charAt(2) == ':'
                        && java.io.File.separatorChar == '\\') {
                    raw = raw.substring(1);
                }
                dir = new java.io.File(raw);
            }
            workspacePath = dir.getAbsolutePath();
        } catch (Exception e) {
            workspacePath = System.getProperty("user.home", "/root")
                    + java.io.File.separator + "knime-workspace";
            System.err.println("[ServerExplorer] Could not get workspace path, using: " + workspacePath);
        }
        System.out.println("[ServerExplorer] Workspace path: " + workspacePath);

        currentServer = ServerConfigStore.getActive();
        System.out.println("[ServerExplorer] Active server: " + currentServer);
        client = new ServerClient(currentServer);

        // Layout: status strip on top, search box, tree fills the rest.
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 2;
        parent.setLayout(layout);

        // Status strip: connection state on the left, gear (configure
        // servers) button on the right. This is where the gear lives;
        // we don't add it to the view's action bar because action bars
        // are easy to lose track of (and were invisible after our
        // co-stacking move on some workbench layouts).
        Composite statusBar = new Composite(parent, SWT.NONE);
        GridLayout sbLayout = new GridLayout(2, false);
        sbLayout.marginWidth = 4;
        sbLayout.marginHeight = 2;
        sbLayout.horizontalSpacing = 4;
        statusBar.setLayout(sbLayout);
        statusBar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        statusLabel = new Label(statusBar, SWT.NONE);
        statusLabel.setText("Connecting...");
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        ToolBarManager statusToolBar = new ToolBarManager(SWT.FLAT);
        // openServersPrefsAction is created later in createActions(); we
        // need it now, so create early actions inline.
        openServersPrefsAction = new OpenServersPreferencesAction(getSite().getShell());
        statusToolBar.add(openServersPrefsAction);
        ToolBar tb = statusToolBar.createControl(statusBar);
        tb.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));

        // Search row: text field + Options button for deep-search toggles.
        Composite searchRow = new Composite(parent, SWT.NONE);
        GridLayout searchLayout = new GridLayout(2, false);
        searchLayout.marginWidth = 0;
        searchLayout.marginHeight = 0;
        searchLayout.horizontalSpacing = 4;
        searchRow.setLayout(searchLayout);
        searchRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        searchText = new Text(searchRow, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        searchText.setMessage("Search workflows...");
        searchText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Button optionsButton = new Button(searchRow, SWT.PUSH);
        optionsButton.setText("Options ▾");
        optionsButton.setToolTipText("Choose which content the search looks inside");
        optionsButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                openSearchOptionsMenu(optionsButton);
            }
        });

        // Create tree viewer
        viewer = new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        viewer.setContentProvider(new WorkflowTreeContentProvider(client, workspacePath));
        viewer.setLabelProvider(new WorkflowTreeLabelProvider());
        viewer.setAutoExpandLevel(2);
        viewer.getControl().setLayoutData(new GridData(GridData.FILL_BOTH));

        // Name filter wired to the search box.
        searchFilter = new WorkflowSearchFilter();
        viewer.addFilter(searchFilter);
        searchText.addModifyListener(e -> applySearch(searchText.getText()));

        // Create the two root nodes: Server and Workspace
        WorkflowNode serverRoot = new WorkflowNode(
                serverRootLabel(currentServer),
                "/", NodeType.ROOT, Location.SERVER);
        WorkflowNode workspaceRoot = new WorkflowNode(
                "Workspace (local)", "/", NodeType.ROOT, Location.WORKSPACE);

        viewer.setInput(new WorkflowNode[] { serverRoot, workspaceRoot });
        System.out.println("[ServerExplorer] Tree viewer initialized with 2 root nodes");

        // Create actions
        createActions();

        // Drag-and-drop between server and workspace branches
        setupDragAndDrop();

        // F2 to rename the selected node.
        viewer.getControl().addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.keyCode == SWT.F2 && renameAction != null && renameAction.isEnabled()) {
                    renameAction.run();
                }
            }
        });

        // Context menu
        hookContextMenu();

        // Toolbar
        contributeToActionBars();

        // Double-click opens workflows, expands/collapses directories
        viewer.addDoubleClickListener(new IDoubleClickListener() {
            @Override
            public void doubleClick(DoubleClickEvent event) {
                IStructuredSelection sel = (IStructuredSelection) event.getSelection();
                if (sel.isEmpty()) return;
                WorkflowNode node = (WorkflowNode) sel.getFirstElement();
                if (node.getType() == NodeType.WORKFLOW) {
                    openWorkflowAction.run();
                } else if (viewer.getExpandedState(node)) {
                    viewer.collapseToLevel(node, 1);
                } else {
                    viewer.expandToLevel(node, 1);
                }
            }
        });

        // Refresh the WORKSPACE branch whenever this view is brought to front.
        // Also use the activation hook as a lazy retry point for subscribing
        // to KNIME Explorer content changes -- that bundle may not be ACTIVE
        // when our view is first created.
        partListener = new IPartListener2() {
            @Override
            public void partActivated(IWorkbenchPartReference partRef) {
                if (ID.equals(partRef.getId())) {
                    viewFocused = true;
                    ensureContentListener();
                    refreshWorkspaceBranch();
                    // Quick re-poll on focus so the user sees fresh state
                    // immediately when they switch back into the view.
                    if (statePollJob != null) {
                        statePollJob.cancel();
                        statePollJob.schedule(0);
                    }
                }
            }

            @Override
            public void partBroughtToTop(IWorkbenchPartReference partRef) {
                if (ID.equals(partRef.getId())) {
                    viewFocused = true;
                    ensureContentListener();
                    refreshWorkspaceBranch();
                }
            }

            @Override
            public void partDeactivated(IWorkbenchPartReference partRef) {
                if (ID.equals(partRef.getId())) {
                    viewFocused = false;
                }
            }

            @Override
            public void partHidden(IWorkbenchPartReference partRef) {
                if (ID.equals(partRef.getId())) {
                    viewFocused = false;
                }
            }
        };
        getSite().getPage().addPartListener(partListener);

        // First attempt to subscribe to KNIME Explorer changes. Retries on
        // view activation if the bundle wasn't active yet.
        // Note: KNIME's GlobalDeleteAction bypasses the content-provider
        // listener and refreshes its TreeViewer directly, so this listener
        // alone can't catch deletes. The filesystem watcher below is what
        // actually picks those up.
        ensureContentListener();

        // Watch the workspace directory at the OS level (inotify on Linux).
        // Picks up workflow creates/deletes regardless of who made them.
        startWorkspaceWatcher();

        // React when the user edits the configured servers / active server
        // in the preferences page.
        serverConfigRemover = ServerConfigStore.addChangeListener(this::onServerConfigChanged);

        // Check server connectivity on startup
        checkServerConnection();

        // Background poller for cross-client change detection.
        startStatePoller();
    }

    private void startStatePoller() {
        statePollJob = new Job("Server Explorer state poll") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                if (monitor.isCanceled() || viewer == null
                        || viewer.getControl().isDisposed()) {
                    return Status.CANCEL_STATUS;
                }
                try {
                    String sig = client.stateSignature();
                    if (sig != null && !sig.isEmpty()
                            && !sig.equals(lastServerSignature)) {
                        boolean firstPoll = lastServerSignature.isEmpty();
                        lastServerSignature = sig;
                        if (!firstPoll) {
                            // Only refresh when we actually saw a change
                            // — the first poll just establishes the baseline.
                            Display d = viewer.getControl().getDisplay();
                            if (d != null && !d.isDisposed()) {
                                d.asyncExec(ServerExplorerView.this::refreshServerBranch);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Connection blip — quietly skip; next tick retries.
                }
                if (!monitor.isCanceled() && viewer != null
                        && !viewer.getControl().isDisposed()) {
                    schedule(viewFocused ? POLL_FOCUSED_MS : POLL_UNFOCUSED_MS);
                }
                return Status.OK_STATUS;
            }
        };
        statePollJob.setSystem(true);
        statePollJob.schedule(2_000L);
    }

    private void refreshServerBranch() {
        if (viewer == null || viewer.getControl().isDisposed()) return;
        Object input = viewer.getInput();
        if (!(input instanceof WorkflowNode[])) return;
        for (WorkflowNode root : (WorkflowNode[]) input) {
            if (root.getLocation() == Location.SERVER) {
                root.clearChildren();
                viewer.refresh(root);
                break;
            }
        }
    }

    /**
     * Apply the user's search text to the tree filter.
     *
     * On the search-start transition we snapshot the user's current
     * expansion state and pre-load the workspace sub-tree so the filter's
     * descendant check has full data. Server children load asynchronously
     * through the content provider; matches inside server folders appear
     * once their browse response arrives.
     *
     * When the search is cleared we restore the snapshot so any folders the
     * user had opened (or left closed) before searching look the same again.
     */
    private void applySearch(String text) {
        if (viewer == null || viewer.getControl().isDisposed()) return;
        if (searchFilter == null) return;

        boolean wasActive = searchFilter.isActive();
        searchFilter.setPattern(text);
        // Reset deep-match set whenever the query changes; a fresh scan
        // will repopulate it when the background job finishes.
        searchFilter.setDeepMatches(Collections.<String>emptySet(),
                Collections.<String>emptySet());
        boolean nowActive = searchFilter.isActive();

        if (!wasActive && nowActive) {
            // Search just started -- capture current expansion BEFORE we
            // call refresh + expand.
            preSearchExpansion = viewer.getExpandedElements();
            preloadWorkspaceTree();
        }

        viewer.refresh();

        if (nowActive) {
            viewer.expandAll();
            // Kick off a deep scan if the user has enabled any deep
            // search options. The result drops back into the filter
            // via setDeepMatches() and we refresh the viewer again.
            if (searchTitles || searchAnnotations) {
                kickDeepScan(text);
            }
        } else if (wasActive) {
            // Search just cleared -- put the tree back the way it was.
            if (preSearchExpansion != null) {
                viewer.collapseAll();
                viewer.setExpandedElements(preSearchExpansion);
            }
            preSearchExpansion = null;
        }
    }

    /**
     * Build the set of {@link Field}s to scan based on the toggles.
     */
    private EnumSet<Field> deepFields() {
        EnumSet<Field> fields = EnumSet.noneOf(Field.class);
        if (searchTitles) fields.add(Field.NODE_TITLE);
        if (searchAnnotations) fields.add(Field.ANNOTATION);
        return fields;
    }

    /**
     * Pop up the search-options menu beneath the Options button.
     */
    private void openSearchOptionsMenu(Button anchor) {
        Menu menu = new Menu(anchor.getShell(), SWT.POP_UP);

        MenuItem itemTitles = new MenuItem(menu, SWT.CHECK);
        itemTitles.setText("Match node titles (user-set names)");
        itemTitles.setSelection(searchTitles);
        itemTitles.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                searchTitles = itemTitles.getSelection();
                applySearch(searchText.getText());
            }
        });

        MenuItem itemAnnots = new MenuItem(menu, SWT.CHECK);
        itemAnnots.setText("Match workflow annotations / sticky notes");
        itemAnnots.setSelection(searchAnnotations);
        itemAnnots.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                searchAnnotations = itemAnnots.getSelection();
                applySearch(searchText.getText());
            }
        });

        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem help = new MenuItem(menu, SWT.PUSH);
        help.setText("(Names are always matched.)");
        help.setEnabled(false);

        Rectangle rect = anchor.getBounds();
        Point pt = anchor.getParent().toDisplay(new Point(rect.x, rect.y + rect.height));
        menu.setLocation(pt);
        menu.setVisible(true);
    }

    /**
     * Run a deep content scan in a background thread for the current
     * query and field selection, then push the result into the filter.
     * Older in-flight scans are discarded via {@link #searchGeneration}.
     */
    private void kickDeepScan(String query) {
        if (query == null || query.isEmpty()) return;
        EnumSet<Field> fields = deepFields();
        if (fields.isEmpty()) return;
        final int gen = searchGeneration.incrementAndGet();
        final String queryFinal = query;
        final String wsPath = workspacePath;
        final ServerClient cli = client;
        new Thread(() -> {
            Set<String> serverHits = Collections.emptySet();
            Set<String> wsHits = Collections.emptySet();
            try {
                if (cli != null) {
                    List<WorkflowNode> srv = cli.deepSearch(queryFinal, fields);
                    serverHits = new HashSet<>(srv.size());
                    for (WorkflowNode n : srv) serverHits.add(n.getPath());
                }
            } catch (Throwable t) {
                System.err.println("[ServerExplorer] server deep search failed: " + t);
            }
            try {
                if (wsPath != null) {
                    wsHits = WorkflowContentIndex.findMatches(new File(wsPath), queryFinal, fields);
                }
            } catch (Throwable t) {
                System.err.println("[ServerExplorer] workspace deep search failed: " + t);
            }
            final Set<String> srvFinal = serverHits;
            final Set<String> wsFinal = wsHits;
            Display.getDefault().asyncExec(() -> {
                // Drop result if the user has typed further since we started.
                if (gen != searchGeneration.get()) return;
                if (viewer == null || viewer.getControl().isDisposed()) return;
                if (searchFilter == null) return;
                searchFilter.setDeepMatches(srvFinal, wsFinal);
                viewer.refresh();
                viewer.expandAll();
            });
        }, "ServerExplorer-DeepSearch").start();
    }

    /**
     * Force-load the entire workspace sub-tree by asking the content
     * provider for children of every directory. Cheap (local FS only).
     * Server side is left lazy because each browse is an HTTP call.
     */
    private void preloadWorkspaceTree() {
        Object input = viewer.getInput();
        if (!(input instanceof WorkflowNode[])) return;
        Object provider = viewer.getContentProvider();
        if (!(provider instanceof WorkflowTreeContentProvider)) return;
        WorkflowTreeContentProvider cp = (WorkflowTreeContentProvider) provider;
        for (WorkflowNode root : (WorkflowNode[]) input) {
            if (root.getLocation() == Location.WORKSPACE) {
                preloadRecurse(root, cp);
            }
        }
    }

    private void preloadRecurse(WorkflowNode node, WorkflowTreeContentProvider cp) {
        if (node.getType() == NodeType.FILE) return;
        // getChildren on the workspace side loads synchronously from disk.
        Object[] kids = cp.getChildren(node);
        if (kids == null) return;
        for (Object k : kids) {
            if (k instanceof WorkflowNode) preloadRecurse((WorkflowNode) k, cp);
        }
    }

    private void refreshWorkspaceBranch() {
        if (viewer == null || viewer.getControl().isDisposed()) return;
        Object input = viewer.getInput();
        if (!(input instanceof WorkflowNode[])) return;
        for (WorkflowNode root : (WorkflowNode[]) input) {
            if (root.getLocation() == Location.WORKSPACE) {
                root.clearChildren();
                viewer.refresh(root);
                break;
            }
        }
    }

    /**
     * Subscribe to KNIME Explorer LOCAL-mount change events. Idempotent;
     * a no-op once a subscription is in place. The callback marshals back
     * to the UI thread to refresh the workspace branch.
     */
    private void ensureContentListener() {
        if (contentListenerRemover != null) return;
        contentListenerRemover = KnimeExplorerRefresher.addLocalContentChangeListener(() -> {
            if (viewer == null || viewer.getControl().isDisposed()) return;
            viewer.getControl().getDisplay().asyncExec(this::refreshWorkspaceBranch);
        });
    }

    /**
     * Watch the top-level workspace directory for create/delete events. KNIME's
     * Explorer doesn't expose a useful change event for our purposes, so we
     * fall back to OS-level notifications. We only watch the top level: every
     * KNIME workflow / workflow group is a top-level entry, and per-file
     * changes inside workflows don't affect what Server Explorer renders.
     *
     * Refresh requests are coalesced -- if many events arrive in a burst
     * (e.g. KNIME deleting many files in a workflow group), only one
     * UI-thread refresh is scheduled.
     */
    private void startWorkspaceWatcher() {
        if (workspacePath == null) return;
        // Never let a bad path bring down the whole view -- the watcher is
        // a background convenience, not a correctness requirement.
        Path dir;
        try {
            dir = Paths.get(workspacePath);
        } catch (Exception e) {
            System.err.println("[ServerExplorer] Skipping watcher, invalid workspace path '"
                    + workspacePath + "': " + e);
            return;
        }
        if (!java.nio.file.Files.isDirectory(dir)) {
            System.err.println("[ServerExplorer] Workspace path not a directory, skipping watcher: " + dir);
            return;
        }
        watcherRunning = true;
        watcherThread = new Thread(() -> runWatchLoop(dir), "ServerExplorer-WorkspaceWatcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        System.out.println("[ServerExplorer] Started workspace watcher on " + dir);
    }

    private void runWatchLoop(Path dir) {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            dir.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);
            while (watcherRunning) {
                WatchKey key = ws.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) continue;
                boolean hadEvents = !key.pollEvents().isEmpty();
                if (hadEvents) {
                    if (viewer != null && !viewer.getControl().isDisposed()) {
                        viewer.getControl().getDisplay().asyncExec(this::refreshWorkspaceBranch);
                    }
                }
                if (!key.reset()) {
                    System.err.println("[ServerExplorer] Workspace watch key invalidated; stopping watcher");
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[ServerExplorer] Workspace watcher failed: " + e);
            e.printStackTrace();
        }
    }

    @Override
    public void dispose() {
        if (statePollJob != null) {
            statePollJob.cancel();
            statePollJob = null;
        }
        if (serverConfigRemover != null) {
            try { serverConfigRemover.run(); } catch (Exception ignore) {}
            serverConfigRemover = null;
        }
        watcherRunning = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }
        if (contentListenerRemover != null) {
            try {
                contentListenerRemover.run();
            } catch (Exception ignore) {
                // bundle may already be stopping
            }
            contentListenerRemover = null;
        }
        if (partListener != null) {
            try {
                getSite().getPage().removePartListener(partListener);
            } catch (Exception ignore) {
                // page may already be torn down
            }
            partListener = null;
        }
        super.dispose();
    }

    private void createActions() {
        uploadAction = new UploadAction(viewer, client, workspacePath);
        downloadAction = new DownloadAction(viewer, client, workspacePath);
        deleteAction = new DeleteAction(viewer, client, workspacePath);
        refreshAction = new RefreshAction(viewer);
        openWorkflowAction = new OpenWorkflowAction(viewer, client, workspacePath);
        newFolderAction = new NewFolderAction(viewer, client, workspacePath);
        renameAction = new RenameAction(viewer, client, workspacePath);
        copyUrlAction = new CopyUrlAction(viewer, client, workspacePath);
        // openServersPrefsAction is created earlier (in the status strip)
        // because the status bar widgets are built before createActions().
        if (openServersPrefsAction == null) {
            openServersPrefsAction = new OpenServersPreferencesAction(getSite().getShell());
        }
    }

    private void setupDragAndDrop() {
        int ops = DND.DROP_MOVE | DND.DROP_COPY;
        Transfer[] transfers = new Transfer[] { LocalSelectionTransfer.getTransfer() };

        viewer.addDragSupport(ops, transfers, new DragSourceAdapter() {
            @Override
            public void dragStart(DragSourceEvent event) {
                IStructuredSelection sel = viewer.getStructuredSelection();
                if (sel.isEmpty()) { event.doit = false; return; }
                Object first = sel.getFirstElement();
                event.doit = first instanceof WorkflowNode
                        && ((WorkflowNode) first).getType() != NodeType.ROOT;
                LocalSelectionTransfer.getTransfer().setSelection(sel);
            }

            @Override
            public void dragSetData(DragSourceEvent event) {
                event.data = LocalSelectionTransfer.getTransfer().getSelection();
            }

            @Override
            public void dragFinished(org.eclipse.swt.dnd.DragSourceEvent event) {
                LocalSelectionTransfer.getTransfer().setSelection(null);
            }
        });

        viewer.addDropSupport(ops, transfers,
                new WorkflowDropAdapter(viewer, client, workspacePath,
                        downloadAction, uploadAction));
    }

    private void hookContextMenu() {
        MenuManager menuMgr = new MenuManager("#PopupMenu");
        menuMgr.setRemoveAllWhenShown(true);
        menuMgr.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager manager) {
                fillContextMenu(manager);
            }
        });
        Menu menu = menuMgr.createContextMenu(viewer.getControl());
        viewer.getControl().setMenu(menu);
    }

    private void fillContextMenu(IMenuManager manager) {
        IStructuredSelection sel = viewer.getStructuredSelection();
        if (sel.isEmpty()) {
            manager.add(refreshAction);
            return;
        }

        WorkflowNode node = (WorkflowNode) sel.getFirstElement();

        // Open action for workflows (both server and workspace)
        if (node.getType() == NodeType.WORKFLOW) {
            manager.add(openWorkflowAction);
            manager.add(new Separator());
        }

        if (node.getLocation() == Location.WORKSPACE
                && (node.getType() == NodeType.WORKFLOW || node.getType() == NodeType.DIRECTORY)) {
            manager.add(uploadAction);
        }
        if (node.getLocation() == Location.SERVER && node.getType() != NodeType.ROOT) {
            manager.add(downloadAction);
        }

        // New Folder is always available; it resolves the selection to the
        // nearest enclosing directory (or root).
        manager.add(new Separator());
        manager.add(newFolderAction);
        if (node.getType() != NodeType.ROOT) {
            manager.add(renameAction);
        }

        if (node.getType() != NodeType.ROOT) {
            manager.add(new Separator());
            manager.add(copyUrlAction);
            manager.add(deleteAction);
        }
        manager.add(new Separator());
        manager.add(refreshAction);
    }

    private void contributeToActionBars() {
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        toolBar.add(refreshAction);
        toolBar.add(new Separator());
        toolBar.add(newFolderAction);
        toolBar.add(uploadAction);
        toolBar.add(downloadAction);
        // openServersPrefsAction lives in the in-view status strip instead.
    }

    private String serverRootLabel(ServerConfig c) {
        if (c == null) return "Server (none configured)";
        return "Server: " + c.getName() + " (" + c.getUrl() + ")";
    }

    /**
     * Called when the {@link ServerConfigStore} reports a change. Updates
     * the existing {@link ServerClient} in place (mutating its baseUrl /
     * allow-self-signed) and rebuilds the visible server-root node so the
     * label and contents reflect the new server.
     *
     * In-place update is intentional: every action and the drop adapter
     * already hold a reference to this ServerClient instance, so mutating
     * fields transparently switches them all without re-wiring. The
     * earlier hide/show approach was fragile -- the preference store fires
     * separate events for P_SERVERS and P_ACTIVE_SERVER, and the second
     * event's deferred asyncExec ran after the view had already been
     * disposed by the first one.
     */
    private void onServerConfigChanged() {
        if (viewer == null || viewer.getControl().isDisposed()) return;
        ServerConfig active = ServerConfigStore.getActive();
        // null-safe equality: only skip if BOTH sides are the same reference
        // or both non-null and equal. Prevents NPE when unconfigured.
        if (active == null && currentServer == null) return;
        if (active != null && active.equals(currentServer)) return;
        System.out.println("[ServerExplorer] Active server changed: "
                + currentServer + " -> " + active);
        currentServer = active;
        if (client != null && active != null) {
            client.reconfigure(active);
        }
        // Different backend -> the next signature won't compare against
        // the old one; treat it as a fresh baseline.
        lastServerSignature = "";
        viewer.getControl().getDisplay().asyncExec(() -> {
            if (viewer == null || viewer.getControl().isDisposed()) return;
            // Rebuild both root nodes: the server-root label needs to
            // reflect the new server, and we want both branches to start
            // fresh.
            WorkflowNode serverRoot = new WorkflowNode(
                    serverRootLabel(currentServer),
                    "/", NodeType.ROOT, Location.SERVER);
            WorkflowNode workspaceRoot = new WorkflowNode(
                    "Workspace (local)", "/", NodeType.ROOT, Location.WORKSPACE);
            viewer.setInput(new WorkflowNode[] { serverRoot, workspaceRoot });
            checkServerConnection();
        });
    }

    private void checkServerConnection() {
        // No server configured -> show a call-to-action instead of a stuck
        // "Connecting..." (previously we synthesized a fallback URL, tried
        // to reach it, and got wedged on DNS/connect failure).
        if (currentServer == null) {
            if (statusLabel != null && !statusLabel.isDisposed()) {
                statusLabel.setText("No server configured — click ⚙ to add one");
                statusLabel.getParent().layout();
            }
            return;
        }
        new Thread(() -> {
            boolean healthy = client.isHealthy();
            viewer.getControl().getDisplay().asyncExec(() -> {
                if (statusLabel == null || statusLabel.isDisposed()) return;
                String url = currentServer != null ? currentServer.getUrl() : "";
                if (healthy) {
                    statusLabel.setText("Connected: " + url);
                } else {
                    statusLabel.setText("Offline: " + url + " (workspace browsing only)");
                }
                statusLabel.getParent().layout();
            });
        }).start();
    }

    @Override
    public void setFocus() {
        viewer.getControl().setFocus();
    }
}

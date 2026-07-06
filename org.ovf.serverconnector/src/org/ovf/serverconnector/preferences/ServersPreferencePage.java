package org.ovf.serverconnector.preferences;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Preference page: Window > Preferences > Server Connector > Servers.
 *
 * Lets the user add, edit, remove, and select an active server. Each
 * server entry has a name, URL (http or https), and an "allow self-signed
 * certificates" flag.
 */
public class ServersPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    public static final String PAGE_ID =
            "org.ovf.serverconnector.preferences.ServersPreferencePage";

    private List<ServerConfig> servers;
    private String activeName;

    private TableViewer table;
    private Button addBtn, editBtn, removeBtn, activeBtn;

    public ServersPreferencePage() {
        setDescription("Configured workflow servers. Click 'Set Active' to choose which one"
                + " the Server Explorer view connects to.");
        noDefaultAndApplyButton();
    }

    @Override
    public void init(IWorkbench workbench) {
        servers = new ArrayList<>(ServerConfigStore.getAll());
        activeName = ServerConfigStore.getActiveName();
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(2, false));
        root.setLayoutData(new GridData(GridData.FILL_BOTH));

        table = new TableViewer(root,
                SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL);
        Table t = table.getTable();
        t.setHeaderVisible(true);
        t.setLinesVisible(true);
        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.heightHint = 180;
        t.setLayoutData(gd);

        addColumn("Active", 60, c -> isActive(c) ? "✓" : "");
        addColumn("Name", 140, c -> c.getName());
        addColumn("URL", 260, c -> c.getUrl());
        addColumn("Self-signed", 90, c -> c.isAllowSelfSigned() ? "yes" : "");

        table.setContentProvider(new IStructuredContentProvider() {
            @Override public Object[] getElements(Object input) {
                return servers.toArray();
            }
            @Override public void inputChanged(Viewer v, Object o, Object n) {}
            @Override public void dispose() {}
        });
        table.setInput(servers);

        // Buttons column
        Composite btns = new Composite(root, SWT.NONE);
        btns.setLayout(new GridLayout(1, false));
        btns.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, false, false));

        addBtn = makeBtn(btns, "Add...", e -> onAdd());
        editBtn = makeBtn(btns, "Edit...", e -> onEdit());
        removeBtn = makeBtn(btns, "Remove", e -> onRemove());
        activeBtn = makeBtn(btns, "Set Active", e -> onSetActive());

        table.addSelectionChangedListener(e -> updateButtons());
        table.addDoubleClickListener(e -> onEdit());
        updateButtons();
        return root;
    }

    private void addColumn(String label, int width, ColumnText getter) {
        TableViewerColumn vc = new TableViewerColumn(table, SWT.NONE);
        TableColumn col = vc.getColumn();
        col.setText(label);
        col.setWidth(width);
        vc.setLabelProvider(new ColumnLabelProvider() {
            @Override public String getText(Object element) {
                ServerConfig c = (ServerConfig) element;
                return getter.get(c);
            }
        });
    }

    private interface ColumnText { String get(ServerConfig c); }

    private Button makeBtn(Composite parent, String text,
            java.util.function.Consumer<SelectionEvent> handler) {
        Button b = new Button(parent, SWT.PUSH);
        b.setText(text);
        b.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
        b.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { handler.accept(e); }
        });
        return b;
    }

    private ServerConfig selected() {
        Object o = table.getStructuredSelection().getFirstElement();
        return o instanceof ServerConfig ? (ServerConfig) o : null;
    }

    private boolean isActive(ServerConfig c) {
        return c != null && c.getName() != null && c.getName().equals(activeName);
    }

    private void updateButtons() {
        boolean has = selected() != null;
        editBtn.setEnabled(has);
        removeBtn.setEnabled(has);
        activeBtn.setEnabled(has);
    }

    private void onAdd() {
        ServerEditDialog dlg = new ServerEditDialog(getShell(), null, "Add Server");
        if (dlg.open() == ServerEditDialog.OK) {
            ServerConfig c = dlg.getResult();
            if (c == null) return;
            if (findByName(c.getName()) != null) {
                setErrorMessage("A server named '" + c.getName() + "' already exists.");
                return;
            }
            servers.add(c);
            if (servers.size() == 1) activeName = c.getName();  // first one becomes active
            table.refresh();
            setErrorMessage(null);
        }
    }

    private void onEdit() {
        ServerConfig sel = selected();
        if (sel == null) return;
        String oldName = sel.getName();
        ServerEditDialog dlg = new ServerEditDialog(getShell(), sel, "Edit Server");
        if (dlg.open() == ServerEditDialog.OK) {
            ServerConfig updated = dlg.getResult();
            if (updated == null) return;
            if (!updated.getName().equals(oldName) && findByName(updated.getName()) != null) {
                setErrorMessage("A server named '" + updated.getName() + "' already exists.");
                return;
            }
            int idx = servers.indexOf(sel);
            if (idx >= 0) servers.set(idx, updated);
            if (oldName.equals(activeName)) activeName = updated.getName();
            table.refresh();
            setErrorMessage(null);
        }
    }

    private void onRemove() {
        ServerConfig sel = selected();
        if (sel == null) return;
        servers.remove(sel);
        if (sel.getName().equals(activeName)) {
            activeName = servers.isEmpty() ? "" : servers.get(0).getName();
        }
        table.refresh();
    }

    private void onSetActive() {
        ServerConfig sel = selected();
        if (sel == null) return;
        activeName = sel.getName();
        table.refresh();
    }

    private ServerConfig findByName(String name) {
        for (ServerConfig c : servers) {
            if (c.getName().equals(name)) return c;
        }
        return null;
    }

    @Override
    public boolean performOk() {
        ServerConfigStore.setAll(servers);
        ServerConfigStore.setActiveName(activeName);
        return super.performOk();
    }
}

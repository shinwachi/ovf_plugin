package org.knime.serverconnector.preferences;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Add / edit dialog for a single {@link ServerConfig}. Supports both
 * remote HTTP(S) servers and local filesystem mounts; the second URL
 * row changes label + a Browse button appears for the local type.
 */
public class ServerEditDialog extends Dialog {

    private final ServerConfig editing;
    private final String titleText;

    private Text nameText;
    private Text urlText;
    private Button selfSignedCheck;
    private Button typeRemoteRadio;
    private Button typeLocalRadio;
    private Button browseButton;
    private Label urlLabel;

    private String resultName;
    private String resultUrl;
    private boolean resultSelfSigned;
    private ServerConfig.Type resultType;

    public ServerEditDialog(Shell parent, ServerConfig config, String title) {
        super(parent);
        this.editing = config != null ? config
                : new ServerConfig("", "https://", false, ServerConfig.Type.REMOTE);
        this.titleText = title;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(titleText);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite grid = new Composite(area, SWT.NONE);
        grid.setLayout(new GridLayout(3, false));
        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.widthHint = 460;
        grid.setLayoutData(gd);

        // Name row
        new Label(grid, SWT.NONE).setText("Name:");
        nameText = new Text(grid, SWT.BORDER);
        GridData nameGd = new GridData(GridData.FILL_HORIZONTAL);
        nameGd.horizontalSpan = 2;
        nameText.setLayoutData(nameGd);
        nameText.setText(nullToEmpty(editing.getName()));

        // Type row
        new Label(grid, SWT.NONE).setText("Type:");
        Composite typeRow = new Composite(grid, SWT.NONE);
        GridLayout typeLayout = new GridLayout(2, false);
        typeLayout.marginWidth = 0;
        typeLayout.marginHeight = 0;
        typeRow.setLayout(typeLayout);
        GridData typeGd = new GridData(GridData.FILL_HORIZONTAL);
        typeGd.horizontalSpan = 2;
        typeRow.setLayoutData(typeGd);
        typeRemoteRadio = new Button(typeRow, SWT.RADIO);
        typeRemoteRadio.setText("Remote (HTTP/HTTPS)");
        typeLocalRadio = new Button(typeRow, SWT.RADIO);
        typeLocalRadio.setText("Local drive");
        boolean isLocal = editing.getType() == ServerConfig.Type.LOCAL;
        typeRemoteRadio.setSelection(!isLocal);
        typeLocalRadio.setSelection(isLocal);

        // URL / path row
        urlLabel = new Label(grid, SWT.NONE);
        urlLabel.setText("URL:");
        urlText = new Text(grid, SWT.BORDER);
        urlText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        urlText.setText(nullToEmpty(editing.getUrl()));

        browseButton = new Button(grid, SWT.PUSH);
        browseButton.setText("Browse...");
        browseButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                DirectoryDialog dd = new DirectoryDialog(getShell());
                dd.setText("Choose local mount directory");
                String current = urlText.getText().trim();
                if (current.startsWith("file://")) current = current.substring(7);
                if (!current.isEmpty()) dd.setFilterPath(current);
                String chosen = dd.open();
                if (chosen != null) urlText.setText(chosen);
            }
        });

        // Self-signed row
        new Label(grid, SWT.NONE).setText("");
        selfSignedCheck = new Button(grid, SWT.CHECK);
        GridData ssGd = new GridData(GridData.FILL_HORIZONTAL);
        ssGd.horizontalSpan = 2;
        selfSignedCheck.setLayoutData(ssGd);
        selfSignedCheck.setText("Allow self-signed / untrusted HTTPS certificates");
        selfSignedCheck.setSelection(editing.isAllowSelfSigned());

        ModifyListener validator = e -> validate();
        nameText.addModifyListener(validator);
        urlText.addModifyListener(validator);

        SelectionAdapter typeChanged = new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                applyTypeUi();
                validate();
            }
        };
        typeRemoteRadio.addSelectionListener(typeChanged);
        typeLocalRadio.addSelectionListener(typeChanged);
        applyTypeUi();

        // Test connection row
        Composite buttons = new Composite(area, SWT.NONE);
        buttons.setLayout(new GridLayout(1, false));
        buttons.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        Button test = new Button(buttons, SWT.PUSH);
        test.setText("Test connection");
        test.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                testConnection();
            }
        });

        return area;
    }

    private void applyTypeUi() {
        boolean local = typeLocalRadio.getSelection();
        urlLabel.setText(local ? "Path:" : "URL:");
        urlText.setMessage(local
                ? "/path/to/shared/workflows"
                : "https://host:port  or  http://host:port");
        browseButton.setVisible(local);
        ((GridData) browseButton.getLayoutData() != null
                ? (GridData) browseButton.getLayoutData()
                : new GridData()).exclude = !local;
        selfSignedCheck.setEnabled(!local);
        urlLabel.getParent().layout(true, true);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        super.createButtonsForButtonBar(parent);
        validate();
    }

    private void validate() {
        Button ok = getButton(IDialogConstants.OK_ID);
        if (ok == null) return;
        boolean local = typeLocalRadio.getSelection();
        String urlStr = urlText.getText().trim();
        boolean valid = !nameText.getText().trim().isEmpty()
                && (local ? !urlStr.isEmpty() : isValidUrl(urlStr));
        ok.setEnabled(valid);
    }

    private boolean isValidUrl(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return (lower.startsWith("http://") || lower.startsWith("https://"))
                && s.length() > "https://".length();
    }

    private void testConnection() {
        boolean local = typeLocalRadio.getSelection();
        String urlStr = urlText.getText().trim();
        boolean selfSigned = selfSignedCheck.getSelection();
        if (!local && !isValidUrl(urlStr)) {
            org.eclipse.jface.dialogs.MessageDialog.openError(getShell(),
                    "Test connection", "URL must start with http:// or https://");
            return;
        }
        if (local && urlStr.isEmpty()) {
            org.eclipse.jface.dialogs.MessageDialog.openError(getShell(),
                    "Test connection", "Pick a local directory.");
            return;
        }
        try {
            ServerConfig probe = new ServerConfig("__probe", urlStr, selfSigned,
                    local ? ServerConfig.Type.LOCAL : ServerConfig.Type.REMOTE);
            org.knime.serverconnector.client.ServerClient c =
                    new org.knime.serverconnector.client.ServerClient(probe);
            c.testConnection();
            org.eclipse.jface.dialogs.MessageDialog.openInformation(getShell(),
                    "Test connection",
                    "Connected successfully to " + urlStr);
        } catch (Throwable ex) {
            String hint = diagnose(urlStr, selfSigned, ex);
            String detail = ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            org.eclipse.jface.dialogs.MessageDialog.openError(getShell(),
                    "Test connection",
                    "Connection failed.\n\n" + detail
                            + (hint.isEmpty() ? "" : "\n\nLikely cause: " + hint));
        }
    }

    /**
     * Best-effort interpretation of common connection failures.
     */
    private static String diagnose(String url, boolean selfSigned, Throwable ex) {
        String lower = url.toLowerCase();
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        String type = ex.getClass().getName();

        boolean isSslError = type.contains("javax.net.ssl") || msg.contains("ssl")
                || msg.contains("unrecognized_name") || msg.contains("handshake")
                || msg.contains("certificate") || msg.contains("trust");
        boolean isUnrecognizedSsl = msg.contains("unrecognized ssl message")
                || msg.contains("plaintext connection")
                || msg.contains("not an ssl/tls record");

        if (lower.startsWith("https://") && isUnrecognizedSsl) {
            return "the server at " + url + " is not speaking TLS on that port. "
                    + "Try http:// instead, or point to an HTTPS endpoint.";
        }
        if (lower.startsWith("https://") && isSslError && !selfSigned) {
            return "TLS certificate could not be validated. "
                    + "If this is a self-signed or internal-CA server, enable "
                    + "'Allow self-signed certificates' above.";
        }
        if (type.contains("ConnectException") || msg.contains("connection refused")) {
            return "nothing is listening on that host/port.";
        }
        if (type.contains("UnknownHostException")) {
            return "the host name could not be resolved.";
        }
        if (type.contains("SocketTimeoutException") || msg.contains("timed out")) {
            return "the connection timed out. Check firewall / VPN.";
        }
        return "";
    }

    @Override
    protected void okPressed() {
        resultName = nameText.getText().trim();
        resultUrl = urlText.getText().trim();
        resultType = typeLocalRadio.getSelection()
                ? ServerConfig.Type.LOCAL : ServerConfig.Type.REMOTE;
        if (resultType == ServerConfig.Type.REMOTE) {
            // Strip any accidental trailing slash to keep base URLs canonical.
            while (resultUrl.endsWith("/")) {
                resultUrl = resultUrl.substring(0, resultUrl.length() - 1);
            }
        }
        resultSelfSigned = selfSignedCheck.getSelection();
        super.okPressed();
    }

    public ServerConfig getResult() {
        if (resultName == null) return null;
        return new ServerConfig(resultName, resultUrl, resultSelfSigned, resultType);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

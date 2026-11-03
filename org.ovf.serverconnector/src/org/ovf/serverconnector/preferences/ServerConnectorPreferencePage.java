package org.ovf.serverconnector.preferences;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import org.ovf.serverconnector.InstallInfo;
import org.ovf.serverconnector.ServerConnectorActivator;

/**
 * Preference page accessed via File > Preferences > Server Connector.
 */
public class ServerConnectorPreferencePage extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage {

    public ServerConnectorPreferencePage() {
        super(GRID);
        setDescription("Server Explorer behavior\n\n" + InstallInfo.describe());
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(ServerConnectorActivator.getInstance().getPreferenceStore());
    }

    @Override
    protected void createFieldEditors() {
        addField(new BooleanFieldEditor(
                PreferenceConstants.P_HIDE_SUCCESS_DIALOGS,
                "Hide \"Download Complete\" / \"Upload Complete\" dialogs",
                getFieldEditorParent()));
        addField(new BooleanFieldEditor(
                PreferenceConstants.P_HIDE_RENAME_PROMPTS,
                "Skip rename prompts (use source name)",
                getFieldEditorParent()));
        addField(new BooleanFieldEditor(
                PreferenceConstants.P_HIDE_DELETE_CONFIRM,
                "Skip delete confirmation",
                getFieldEditorParent()));
        org.eclipse.ui.PlatformUI.getWorkbench().getHelpSystem().setHelp(getFieldEditorParent(),
                org.ovf.serverconnector.actions.OpenHelpAction.CONTEXT_SETTINGS);
    }
}

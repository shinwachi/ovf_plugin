package org.ovf.serverconnector.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import org.ovf.serverconnector.ServerConnectorActivator;

/**
 * Sets default values for Server Connector preferences.
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = ServerConnectorActivator.getInstance().getPreferenceStore();
        store.setDefault(PreferenceConstants.P_HIDE_SUCCESS_DIALOGS, false);
        store.setDefault(PreferenceConstants.P_HIDE_RENAME_PROMPTS, false);
        store.setDefault(PreferenceConstants.P_HIDE_DELETE_CONFIRM, false);
        store.setDefault(PreferenceConstants.P_SERVERS, "");
        store.setDefault(PreferenceConstants.P_ACTIVE_SERVER, "");
    }
}

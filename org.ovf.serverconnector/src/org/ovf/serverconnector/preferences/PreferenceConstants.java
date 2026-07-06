package org.ovf.serverconnector.preferences;

/**
 * Preference keys for the Server Connector plugin.
 */
public final class PreferenceConstants {

    private PreferenceConstants() {}

    /** Hide the "Download Complete" / "Upload Complete" info dialogs. */
    public static final String P_HIDE_SUCCESS_DIALOGS = "serverconnector.hideSuccessDialogs";

    /** Skip the rename prompts shown before download / upload. */
    public static final String P_HIDE_RENAME_PROMPTS = "serverconnector.hideRenamePrompts";

    /** Skip the "Delete X from ...?" confirmation dialog. */
    public static final String P_HIDE_DELETE_CONFIRM = "serverconnector.hideDeleteConfirm";

    /** JSON array of configured servers (see {@code ServerConfigStore}). */
    public static final String P_SERVERS = "serverconnector.servers";

    /** Name of the active server entry. */
    public static final String P_ACTIVE_SERVER = "serverconnector.activeServer";
}

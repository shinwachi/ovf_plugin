package org.ovf.serverconnector.actions;

import java.util.function.Predicate;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

/**
 * Asks what to do when a download or upload would land on an item that
 * already has the same name. Always shown: replacing a workflow can't be
 * undone, so none of the "don't show again" settings skip it.
 */
final class OverwritePrompt {

    static final int REPLACE = 0;
    static final int KEEP_BOTH = 1;
    static final int CANCEL = 2;

    private OverwritePrompt() {}

    /**
     * @param where where the existing item is, e.g. "your workspace"
     * @return {@link #REPLACE}, {@link #KEEP_BOTH} or {@link #CANCEL}
     *     (also for Esc or closing the dialog)
     */
    static int ask(Shell shell, String title, String name, String where, String newName) {
        MessageDialog dlg = new MessageDialog(shell, title, null,
                "'" + name + "' already exists in " + where + ".\n\n"
                        + "Replace it, or keep both and name the new one '" + newName + "'?",
                MessageDialog.WARNING,
                new String[] { "Replace", "Keep both", "Cancel" }, CANCEL);
        int r = dlg.open();
        return r == REPLACE || r == KEEP_BOTH ? r : CANCEL;
    }

    /** First of "name (2)", "name (3)", ... that {@code taken} rejects. */
    static String freeName(String name, Predicate<String> taken) {
        for (int i = 2; ; i++) {
            String candidate = name + " (" + i + ")";
            if (!taken.test(candidate)) return candidate;
        }
    }
}

#!/usr/bin/env bash
# Install, check or remove the OVF Server Explorer plugin for KNIME on Linux.
#
#   ./install-ovf.sh                        ask for everything
#   ./install-ovf.sh --knime DIR --check    report only, change nothing
#   ./install-ovf.sh --knime DIR --mode admin|user [--uninstall] [--yes]
#   ./install-ovf.sh --knime DIR --mode admin --apply-permissions
#
# admin: into the KNIME folder, for everyone. Run with sudo.
# user:  into your home directory, for you only. Needs no special rights.
set -u
EXPECT_MAJOR=@KNIME_MAJOR@
IU=org.ovf.serverconnector.feature.feature.group
HERE="$(cd "$(dirname "$0")" && pwd)"
MIN_FREE_MB=100

KNIME="" MODE="" CHECK=0 UNINSTALL=0 YES=0 APPLY=0
while [ $# -gt 0 ]; do
    case "$1" in
        --knime) KNIME="$2"; shift ;;
        --mode) MODE="$2"; shift ;;
        --check) CHECK=1 ;;
        --uninstall) UNINSTALL=1 ;;
        --apply-permissions) APPLY=1 ;;
        --yes) YES=1 ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done

die() { echo "ERROR: $*" >&2; exit 1; }
confirm() { [ "$YES" = 1 ] && return 0; read -r -p "$1 [y/N] " a; [ "$a" = y ] || [ "$a" = Y ]; }

if [ -z "$KNIME" ]; then
    for d in /opt/knime /opt/knime_* /usr/local/knime* "$HOME"/knime*; do
        [ -f "$d/knime" ] && { echo "Found KNIME at $d"; KNIME="$d"; break; }
    done
    [ -t 0 ] && [ "$YES" = 0 ] && {
        read -r -p "KNIME folder (the one holding the 'knime' program) [$KNIME]: " a
        [ -n "$a" ] && KNIME="$a"
    }
fi
[ -n "$KNIME" ] || die "KNIME folder not found; pass --knime DIR"
KNIME="$(cd "$KNIME" 2>/dev/null && pwd -P)" || die "no such folder"
[ -f "$KNIME/knime" ] && [ -d "$KNIME/plugins" ] && [ -d "$KNIME/configuration" ] \
    || die "$KNIME is not a KNIME folder (expected knime, plugins/, configuration/)"

CORE="$(ls -d "$KNIME"/plugins/org.knime.core_* 2>/dev/null | head -1)"
MAJOR="$(basename "$CORE" | sed 's/org.knime.core_\([0-9]*\).*/\1/')"
[ "$MAJOR" = "$EXPECT_MAJOR" ] \
    || die "this installer is for KNIME $EXPECT_MAJOR.x but $KNIME is KNIME $MAJOR.x; get the matching zip"

# Can this account create new files, and change the ones KNIME needs to lock?
LOCK="$KNIME/configuration/org.eclipse.osgi/.manager/.fileTableLock"
PROBE="$KNIME/configuration/.ovf_probe.$$"
if touch "$PROBE" 2>/dev/null; then
    rm -f "$PROBE"
    STATE=writable
    for f in "$LOCK" "$KNIME/configuration/config.ini"; do
        [ ! -e "$f" ] || [ -w "$f" ] || STATE=broken
    done
else
    STATE=readonly
fi
ID="$(ls -d "$HOME"/.eclipse/*_linux_gtk_x86_64 2>/dev/null | head -1)"
[ -n "$ID" ] || ID="$HOME/.eclipse/<id>_linux_gtk_x86_64"

# knime.ini can move KNIME's configuration elsewhere. The director doesn't read
# knime.ini, so a just-for-me install has to be told, or KNIME never sees the plugin.
CONF="$(tr -d '\r' < "$KNIME/knime.ini" 2>/dev/null | awk '
    prev == "-configuration" { print; exit }
    /^-Dosgi\.configuration\.area=/ { sub(/^[^=]*=/, ""); print; exit }
    { prev = $0 }')"

where_files() {
    cat <<TXT

Where the files are
  KNIME folder      $KNIME
    Program files, shared by everyone on this machine.
    An all-users install puts the plugin in its plugins/ folder.
  Your private area $ID
    Created by KNIME. A just-for-me install puts the plugin in plugins/ here.
  Your workspace    the folder KNIME asks for at startup, by default ~/knime-workspace
    Your layout and settings: .metadata/. Each person needs their own.

Recommended permissions
  KNIME folder      owner root; everyone else read and execute only, for all
                    files in it:
                      sudo chown -R root:root '$KNIME'
                      sudo chmod -R go-w,a+rX '$KNIME'
  Private area      only you; KNIME creates it this way. Don't share it.
  Workspace         only you. Two people must not open the same workspace.

  Avoid: letting users write to the KNIME folder. One user can then remove the
  plugin for everyone, and if the files of one user can't be changed by the
  next, the next user's KNIME may not start.
TXT
}

echo
echo "KNIME folder: $KNIME (KNIME $MAJOR.x)"
case "$STATE" in
    readonly) echo "This account:  can't write to it. This is the recommended setup." ;;
    writable) echo "This account:  can write to it." ;;
    broken)   echo "This account:  can create files in it but can't change KNIME's own files."
              echo "               KNIME misbehaves like this (failed installs, missing nodes)."
              echo "               An administrator needs to fix the permissions below." ;;
esac
[ -z "$CONF" ] || echo "knime.ini:     keeps KNIME's configuration in $CONF"
if [ "$CHECK" = 1 ]; then where_files; exit 0; fi

if [ -z "$MODE" ]; then
    [ "$STATE" = readonly ] && [ "$(id -u)" != 0 ] && REC="2" || REC="1"
    echo
    echo "Who should get the plugin?"
    echo "  1) All users (admin): into the KNIME folder. Needs sudo."
    echo "  2) Just me (user):    into your home directory. Others won't see it."
    read -r -p "Choice [$REC]: " a
    case "${a:-$REC}" in 1) MODE=admin ;; 2) MODE=user ;; *) die "enter 1 or 2" ;; esac
fi

DEST=() CONFARG=()
case "$MODE" in
    admin)
        [ "$STATE" = writable ] || die "all-users install needs write access. Run: sudo $0 --knime '$KNIME' --mode admin"
        # The director would rewrite that setting in knime.ini, and KNIME then won't start.
        [ -z "$CONF" ] || die "knime.ini sets KNIME's configuration to $CONF. An all-users install would damage that setting and KNIME would no longer start. Take the setting out of $KNIME/knime.ini, run this again, then put it back."
        DEST=(-destination "$KNIME" -profile KNIMEProfile)
        ;;
    user)
        [ "$STATE" != broken ] || die "KNIME can't manage its own files for this account. Ask an administrator to fix the permissions. See: $0 --knime '$KNIME' --check"
        [ "$(id -u)" != 0 ] || die "just-for-me install must be run as that user, not root"
        [ -z "$CONF" ] || CONFARG=(-configuration "$CONF")
        if [ "$STATE" = readonly ] && [ -z "$CONF" ]; then
            # KNIME keeps a read-only install's private files here, and says nothing if it can't.
            PRIVATE_ROOT="$HOME/.eclipse"
            [ -e "$PRIVATE_ROOT" ] || PRIVATE_ROOT="$HOME"
            [ -w "$PRIVATE_ROOT" ] || die "KNIME needs to write to $PRIVATE_ROOT and this account can't. Fix that folder's permissions, then run this again."
        fi
        if [ "$STATE" = writable ] && [ -z "$CONF" ]; then
            echo
            echo "You can write to this KNIME folder, so KNIME will install there, not in"
            echo "your home directory. Anyone else using this KNIME will get the plugin too."
            confirm "Continue?" || exit 1
        fi
        ;;
    *) die "--mode must be admin or user" ;;
esac

if pgrep -f "^$KNIME/" >/dev/null 2>&1; then
    echo "KNIME appears to be running from this folder. People need to restart it afterwards."
    [ "$MODE" = admin ] && { confirm "Continue anyway?" || exit 1; }
fi

JAVA="$(ls -d "$KNIME"/plugins/org.knime.binary.jre.linux.x86_64_*/jre/bin/java 2>/dev/null | head -1)"
[ -n "$JAVA" ] || JAVA="$(ls -d "$KNIME"/plugins/org.knime.binary.jre.linux.x86_64_*/bin/java 2>/dev/null | head -1)"
LAUNCHER="$(ls "$KNIME"/plugins/org.eclipse.equinox.launcher_*.jar | head -1)"
SITE="$(ls "$HERE"/ovf-serverconnector-*-updatesite.zip 2>/dev/null | head -1)"
[ -x "$JAVA" ] || die "KNIME's bundled Java not found"
[ -n "$SITE" ] || die "update-site zip not found next to this script"

director() {
    "$JAVA" -jar "$LAUNCHER" -debug -nosplash "${CONFARG[@]}" -application org.eclipse.equinox.p2.director \
        -data @noDefault "${DEST[@]}" -repository "jar:file://$SITE!/" "$@" >"$LOG" 2>&1
    RC=$?
    grep -E '^(Installing|Uninstalling|Operation completed)|^Cannot|read only|Permission denied|locked' "$LOG" | sort -u | cut -c1-200
}

# What the director said went wrong: the lines after "Installation failed."
failure_reason() {
    sed -n '/^Installation failed\.$/,/^Application failed/p' "$LOG" \
        | sed -e '1d' -e '/^Application failed/d' -e '/session context was/d' -e '/^There were errors/d' -e 's/^[[:space:]]*//' -e '/^$/d' | cut -c1-300
}

explain_failure() {
    local reason jar
    reason="$(failure_reason)"
    echo
    if [ -z "$reason" ]; then
        echo "KNIME's installer stopped without giving a reason."
        echo "Another install may be running, or this account can't write where it needs to."
        return
    fi
    echo "KNIME's installer says:"
    echo "$reason" | sed 's/^/  /'
    echo
    jar="$(echo "$reason" | sed -n 's/.*artifact file for osgi\.bundle,\([^,]*\),\([^ ]*\) was not found.*/\1_\2.jar/p' | head -1)"
    case "$jar" in
        "") ;;
        org.ovf.*)
            echo "The plugin was installed for everyone, but its jar is gone from the KNIME folder."
            echo "An administrator needs to run: sudo $0 --knime '$KNIME' --mode admin" ;;
        *)
            echo "$jar is missing, and it belongs to another extension, not this plugin."
            echo "No just-for-me install can work until an administrator restores that jar or"
            echo "removes that extension. An all-users install still works." ;;
    esac
    case "$reason" in
        *"required items could not be found"*)
            echo "If another install was running at the same time, wait for it and run this again."
            echo "Otherwise this build may not match this KNIME." ;;
        *"Unable to load repositories"*)
            if command -v unzip >/dev/null && unzip -tqq "$SITE" >/dev/null 2>&1; then
                echo "The update-site zip is fine, so another install was probably running at the"
                echo "same time. Wait for it to finish, then run this again."
            else
                echo "The update-site zip next to this script can't be read: $SITE"
                echo "It may be damaged. Download the installer zip again."
            fi ;;
    esac
}

check_director() {
    if [ $RC != 0 ] || ! grep -q '^Operation completed' "$LOG"; then
        explain_failure
        echo "Full log: $LOG"
        die "the install failed"
    fi
}

not_loaded_notice() {
    echo "Your install record no longer includes the plugin, because an administrator changed"
    echo "this KNIME. KNIME may still offer it in a Migration wizard at startup: choose Cancel,"
    echo "then Yes, and it stops asking."
}

# The jar a configuration folder tells KNIME to load. Paths in bundles.info are
# relative to the KNIME folder and URL-encoded. Empty if it doesn't list the plugin.
listed_jar() {
    local rel
    rel="$(grep -h '^org\.ovf\.serverconnector,' "$1/org.eclipse.equinox.simpleconfigurator/bundles.info" 2>/dev/null \
        | sort -t, -k2 -V | tail -1 | cut -d, -f3)"
    rel="${rel#file:}"
    [ -n "$rel" ] || return 0
    rel="$(printf '%b' "${rel//%/\\x}")"
    case "$rel" in /*) ;; *) rel="$KNIME/$rel" ;; esac
    echo "$(cd "$(dirname "$rel")" 2>/dev/null && pwd -P || dirname "$rel")/$(basename "$rel")"
}

jar_ok() { [ -s "$JAR" ] && { ! command -v unzip >/dev/null || unzip -tqq "$JAR" >/dev/null 2>&1; }; }

echo
echo "Running the KNIME installer..."
LOG="$(mktemp)" || die "can't create a temporary file in ${TMPDIR:-/tmp}. Free some space there, or set TMPDIR to a folder you can write to."
if [ "$UNINSTALL" = 1 ]; then OP=(-uninstallIU "$IU"); else OP=(-installIU "$IU"); fi
director "${OP[@]}"
if [ "$UNINSTALL" = 0 ] && grep -q 'conflicting dependency' "$LOG"; then
    # An older version is installed. p2 won't hold two, so swap them in one step.
    echo "Replacing the installed version..."
    director -uninstallIU "$IU" -installIU "$IU"
fi

AREA="$KNIME/configuration"
if [ "$MODE" = user ]; then
    AREA="$(sed -n '/^Configuration location:/{n;s/^ *file://;p;q}' "$LOG")"
    AREA="$(printf '%b' "${AREA//%/\\x}")"
    AREA="${AREA%/}"
    [ -z "$AREA" ] || [ "$AREA" = "$KNIME/configuration" ] || ID="$(dirname "$AREA")"
fi

# Nothing in the install record to remove. That's fine unless a copy is still listed.
NOT_IN_PROFILE=0
[ "$UNINSTALL" = 1 ] && [ $RC != 0 ] && grep -q '^The installable unit .* has not been found' "$LOG" && NOT_IN_PROFILE=1
[ "$NOT_IN_PROFILE" = 1 ] || check_director

# The director's success only means its records agree. Check what KNIME will load.
echo
if [ "$UNINSTALL" = 1 ]; then
    JAR="$(listed_jar "$KNIME/configuration")"
    if [ -n "$JAR" ]; then
        echo "The plugin is still installed: $JAR"
        echo "That copy was installed for everyone, so KNIME will keep loading it."
        echo "To remove it, an administrator runs:"
        echo "  sudo $0 --knime '$KNIME' --mode admin --uninstall"
        rm -f "$LOG"
        exit 1
    fi
    JAR="$(listed_jar "$AREA")"
    if [ -n "$JAR" ] && [ "$NOT_IN_PROFILE" = 1 ]; then
        not_loaded_notice
    elif [ -n "$JAR" ]; then
        # Seen after an admin installed and removed the plugin over a just-for-me copy.
        echo "The plugin is still listed for you, and KNIME will keep loading it: $JAR"
        echo "To clear it, quit KNIME and delete your private area. That also removes any other"
        echo "extensions you installed for yourself in this KNIME: $ID"
        rm -f "$LOG"
        exit 1
    elif [ "$NOT_IN_PROFILE" = 1 ]; then
        echo "The plugin isn't installed for this account, so there's nothing to remove."
    else
        echo "Removed. If a Server Explorer tab now shows an error, close the tab."
    fi
else
    JAR="$(listed_jar "$AREA")"
    if ! jar_ok; then
        # p2 still lists the plugin, so installing again changes nothing.
        echo "KNIME lists the plugin but its jar is missing or damaged: ${JAR:-no jar listed}"
        echo "Reinstalling..."
        director -uninstallIU "$IU"
        check_director
        director -installIU "$IU"
        check_director
        JAR="$(listed_jar "$AREA")"
        if ! jar_ok; then
            echo "Full log: $LOG"
            die "KNIME won't find the plugin: ${JAR:-not listed in $AREA}. If it was installed for everyone, an administrator needs to run: sudo $0 --knime '$KNIME' --mode admin"
        fi
    fi
    if [ "$MODE" = admin ]; then echo "Installed for everyone: $JAR"; else echo "Installed: $JAR"; fi
    if [ "$MODE" = user ] && [ "$AREA" != "$KNIME/configuration" ] && [ -z "$(listed_jar "$KNIME/configuration")" ]; then
        echo "This copy is yours only. If an administrator later changes this KNIME, the plugin"
        echo "stops loading: the tab turns red and KNIME offers a Migration wizard. Finish the"
        echo "wizard or run this installer again. An all-users install doesn't have this problem."
    fi
    FREE="$(df -Pk "$AREA" 2>/dev/null | awk 'NR==2 { print int($4 / 1024) }')"
    if [ -n "$FREE" ] && [ "$FREE" -lt "$MIN_FREE_MB" ]; then
        echo "WARNING: only $FREE MB free for $AREA. The plugin is installed, but KNIME"
        echo "may not start until you free some space ($MIN_FREE_MB MB or more)."
    else
        echo "Restart KNIME. The Server Explorer tab appears next to KNIME Explorer."
        [ "$EXPECT_MAJOR" = 5 ] && echo "It's in the classic interface. In the Modern UI: Menu > Switch to classic user interface"
        echo "If you closed it before: View > Other... > KNIME Views > Server Explorer"
    fi
fi
rm -f "$LOG"
where_files

if [ "$MODE" = admin ] && [ "$APPLY" = 1 ]; then
    echo
    echo "Applying the recommended KNIME folder permissions..."
    chown -R root:root "$KNIME" && chmod -R go-w,a+rX "$KNIME" && echo "Done." || die "couldn't set permissions"
elif [ "$MODE" = admin ]; then
    echo
    echo "Permissions were not changed. Run the two commands above, or re-run with --apply-permissions."
fi

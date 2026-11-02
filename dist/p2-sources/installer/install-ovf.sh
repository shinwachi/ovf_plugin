#!/usr/bin/env bash
# Install, check or remove the OVF Server Explorer plugin for KNIME on Linux.
#
#   ./install-ovf.sh                        ask for everything, including which KNIME
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

is_knime() { [ -f "$1/knime" ] && [ -d "$1/plugins" ] && [ -d "$1/configuration" ]; }

knime_version() {
    basename "$(ls -d "$1"/plugins/org.knime.core_* 2>/dev/null | head -1)" \
        | sed -n 's/^org\.knime\.core_\([0-9]*\.[0-9]*\.[0-9]*\).*/\1/p'
}

# The private area KNIME uses for a read-only install. Its name holds the hash of
# the install path (java.lang.String.hashCode), and the product id and version
# when the folder has an .eclipseproduct file.
private_area() {
    local s="$1" i c h=0 prefix=""
    for ((i = 0; i < ${#s}; i++)); do
        printf -v c %d "'${s:i:1}"
        h=$(( (h * 31 + c) & 0xFFFFFFFF ))
    done
    [ "$h" -lt 2147483648 ] || h=$(( 4294967296 - h ))
    if [ -f "$1/.eclipseproduct" ]; then
        prefix="$(tr -d '\r' < "$1/.eclipseproduct" | awk -F= '$1 == "id" { i = $2 } $1 == "version" { v = $2 } END { if (i != "") print i "_" v "_" }')"
    fi
    echo "$HOME/.eclipse/${prefix}${h}_linux_gtk_x86_64"
}

listed_version() {
    sed -n 's/^org\.ovf\.serverconnector,\([^,]*\),.*/\1/p' "$1/org.eclipse.equinox.simpleconfigurator/bundles.info" 2>/dev/null | head -1
}

# One line about a KNIME folder; returns 0 if this installer can use it.
describe_knime() {
    local ver major v
    ver="$(knime_version "$1")"; major="${ver%%.*}"
    if [ "$major" != "$EXPECT_MAJOR" ]; then
        case "$major" in
            4) printf 'KNIME %s, needs the 4.1.x installer' "$ver" ;;
            5) printf 'KNIME %s, needs the 5.x installer' "$ver" ;;
            *) printf 'KNIME %s, not supported' "${ver:-version unknown}" ;;
        esac
        return 1
    fi
    v="$(listed_version "$1/configuration")"
    if [ -n "$v" ]; then printf 'KNIME %s, installed for everyone (%s)' "$ver" "$v"; return 0; fi
    v="$(listed_version "$(private_area "$1")/configuration")"
    if [ -n "$v" ]; then printf 'KNIME %s, installed for you (%s)' "$ver" "$v"; return 0; fi
    printf 'KNIME %s, not installed' "$ver"
}

find_knimes() {
    local roots=(/opt /usr/local /srv "$HOME") h
    if [ -n "${SUDO_USER:-}" ]; then
        h="$(getent passwd "$SUDO_USER" | cut -d: -f6)"
        [ -z "$h" ] || roots+=("$h")
    fi
    find "${roots[@]}" -maxdepth 4 -name '.*' -prune -o -type f -name knime -perm /111 -print 2>/dev/null \
        | while read -r f; do
            d="$(cd "$(dirname "$f")" && pwd -P)" && is_knime "$d" && echo "$d"
        done | sort -uV
}

# Returns 0 and sets KNIME if $1 is a KNIME folder this installer can use.
use_knime() {
    if ! is_knime "$1"; then echo "$1 is not a KNIME folder (expected knime, plugins/, configuration/)."; return 1; fi
    if ! describe_knime "$1" >/dev/null; then echo "$1: $(describe_knime "$1")."; return 1; fi
    KNIME="$1"
}

# Returns 0 and sets KNIME from a file picker. Without a desktop, asks for the path.
pick_knime() {
    local f rc=127
    echo "Select the program called knime in the KNIME folder."
    if [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
        if command -v zenity >/dev/null 2>&1; then
            f="$(zenity --file-selection --title="Select the knime program" --filename="/opt/" 2>/dev/null)"; rc=$?
        elif command -v kdialog >/dev/null 2>&1; then
            f="$(kdialog --title "Select the knime program" --getopenfilename /opt 2>/dev/null)"; rc=$?
        fi
    fi
    case "$rc" in
        0) ;;
        127|255)
            echo "No file picker can open here. It needs a desktop, and zenity or kdialog."
            read -r -p "Type the KNIME folder, or press Enter to go back: " f || die "no KNIME folder chosen"
            [ -n "$f" ] || return 1
            f="${f/#\~/$HOME}" ;;
        *) echo "Nothing selected."; return 1 ;;
    esac
    [ -d "$f" ] || f="$(dirname "$f")"
    use_knime "$f"
}

# Lists what a scan finds. Returns 0 once one is chosen, 1 to go back.
scan_knimes() {
    local found=() usable=() d i a default="" n=0
    echo "Scanning this computer..."
    mapfile -t found < <(find_knimes)
    for d in ${found[@]+"${found[@]}"}; do
        describe_knime "$d" >/dev/null && usable+=("$d")
    done
    echo
    if [ "${#found[@]}" -gt 0 ]; then
        echo "KNIME folders on this machine:"
        for d in "${found[@]}"; do
            n=$((n + 1))
            printf '  %d) %s\n     %s\n' "$n" "$d" "$(describe_knime "$d")"
        done
    else
        echo "No KNIME folder found in the usual places."
    fi
    [ "${#usable[@]}" != 1 ] || for i in "${!found[@]}"; do
        [ "${found[$i]}" = "${usable[0]}" ] && default=$((i + 1))
    done
    while :; do
        read -r -p "Which KNIME? Type a number, p for a file picker, or a folder path${default:+ [$default]}: " a || die "no KNIME folder chosen"
        a="${a:-$default}"
        case "$a" in
            '') continue ;;
            p|P) if pick_knime; then return 0; fi; continue ;;
            *[!0-9]*) d="${a/#\~/$HOME}" ;;
            *) if [ "$a" -ge 1 ] && [ "$a" -le "${#found[@]}" ]; then d="${found[$((a - 1))]}"; else echo "Enter a number from the list, p, or a folder path."; continue; fi ;;
        esac
        if use_knime "$d"; then
            [ "${#found[@]}" -lt 2 ] || OTHERS=1
            return 0
        fi
    done
}

# Several KNIME versions can live on one machine, so never assume which one is meant.
choose_knime() {
    local usable=() d a
    if [ "$YES" = 1 ] || [ ! -t 0 ]; then
        while read -r d; do
            describe_knime "$d" >/dev/null && usable+=("$d")
        done < <(find_knimes)
        if [ "${#usable[@]}" = 1 ]; then
            KNIME="${usable[0]}"
            echo "Found KNIME at $KNIME"
            return
        fi
        [ "${#usable[@]}" -gt 1 ] || die "no KNIME folder found for this installer; pass --knime DIR"
        echo "Found several KNIME folders:" >&2
        printf '  %s\n' "${usable[@]}" >&2
        die "say which one with --knime DIR"
    fi
    while :; do
        echo
        echo "How do you want to find KNIME?"
        echo "  1) Scan this computer and choose from a list"
        echo "  2) Select it myself with a file picker"
        read -r -p "Choice, or a folder path [1]: " a || die "no KNIME folder chosen"
        case "${a:-1}" in
            1) if scan_knimes; then return; fi ;;
            2) if pick_knime; then return; fi ;;
            *) if use_knime "${a/#\~/$HOME}"; then return; fi ;;
        esac
    done
}

OTHERS=0
if [ -z "$KNIME" ]; then
    choose_knime
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
ID="$(private_area "$KNIME")"

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
    Created by KNIME, one per KNIME folder. A just-for-me install puts the plugin
    in plugins/ here.
  Your workspace    the folder KNIME asks for at startup, by default ~/knime-workspace
    Your layout and settings: .metadata/. Each person needs their own. Installing
    doesn't change it and it needs no update. Your server list is saved in it.

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
echo "KNIME folder: $KNIME (KNIME $(knime_version "$KNIME"))"
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
        [ "$OTHERS" = 0 ] || echo "Other KNIME folders on this machine weren't changed. To add it to one, run this again."
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

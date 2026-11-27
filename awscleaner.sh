#!/usr/bin/env bash

set -u
umask 077

APP_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P) || exit 1
cd -- "$APP_DIR" || exit 1

readonly APP_DIR
readonly CONFIG_FILE="$APP_DIR/config.yml"
readonly ENV_FILE="$APP_DIR/awscleaner.env"
readonly LOG_FILE="$APP_DIR/output.log"
readonly RUN_DIR="$APP_DIR/.awscleaner"
readonly PID_FILE="$RUN_DIR/java.pid"

jar_path() {
    if [[ -f "$APP_DIR/AWSCleaner.jar" ]]; then
        printf '%s\n' "$APP_DIR/AWSCleaner.jar"
    elif [[ -f "$APP_DIR/target/AWSCleaner.jar" ]]; then
        printf '%s\n' "$APP_DIR/target/AWSCleaner.jar"
    else
        echo "Place AWSCleaner.jar in $APP_DIR." >&2
        return 1
    fi
}

require_java() {
    if ! command -v java >/dev/null; then
        echo "Java 17 is required. Install it and make java available on PATH." >&2
        return 1
    fi
}

initialize_config() {
    local jar
    jar=$(jar_path) || return 1
    require_java || return 1
    java -jar "$jar" --config "$CONFIG_FILE" --init-config
}

load_environment() {
    [[ -e "$ENV_FILE" ]] || return 0
    if [[ ! -f "$ENV_FILE" || ! -r "$ENV_FILE" ]]; then
        echo "Cannot read $ENV_FILE." >&2
        return 1
    fi

    local line key value line_number=0
    while IFS= read -r line || [[ -n "$line" ]]; do
        ((line_number += 1))
        line=${line%$'\r'}
        [[ "$line" =~ ^[[:space:]]*($|#) ]] && continue
        if [[ ! "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
            echo "Invalid assignment at awscleaner.env line $line_number. Use NAME=value." >&2
            return 1
        fi
        key=${BASH_REMATCH[1]}
        value=${BASH_REMATCH[2]}
        case "$value" in
            \"*\"|\'*\') value=${value:1:-1} ;;
        esac
        if ! export "$key=$value" 2>/dev/null; then
            echo "Cannot load the environment setting at awscleaner.env line $line_number." >&2
            return 1
        fi
    done < "$ENV_FILE"
    return 0
}

process_start_time() {
    local stat
    local -a fields
    { IFS= read -r stat < "/proc/$1/stat"; } 2>/dev/null || return 1
    read -r -a fields <<< "${stat##*) }"
    [[ "${fields[0]-}" != Z && "${fields[19]-}" =~ ^[0-9]+$ ]] || return 1
    printf '%s\n' "${fields[19]}"
}

get_pid() {
    local pid started extra index
    local -a arguments=()
    [[ -r "$PID_FILE" ]] || return 1
    read -r pid started extra < "$PID_FILE" || return 1
    [[ "$pid" =~ ^[1-9][0-9]*$ && "$started" =~ ^[0-9]+$ && -z "$extra" ]] || return 1
    [[ $(process_start_time "$pid") == "$started" ]] || return 1
    { mapfile -d '' -t arguments < "/proc/$pid/cmdline"; } 2>/dev/null || return 1

    for ((index = 0; index + 3 < ${#arguments[@]}; index++)); do
        if [[ "${arguments[index]}" == -jar
            && ( "${arguments[index + 1]}" == "$APP_DIR/AWSCleaner.jar"
                || "${arguments[index + 1]}" == "$APP_DIR/target/AWSCleaner.jar" )
            && "${arguments[index + 2]}" == --config
            && "${arguments[index + 3]}" == "$CONFIG_FILE" ]]; then
            printf '%s\n' "$pid"
            return 0
        fi
    done
    return 1
}

with_lock() (
    mkdir -p -- "$RUN_DIR" || exit 1
    exec 9>"$RUN_DIR/manager.lock" || exit 1
    if ! flock -n 9; then
        echo "Another AWSCleaner command is in progress." >&2
        exit 1
    fi
    "$@"
)

start_app() {
    local pid started jar
    if pid=$(get_pid); then
        echo "AWSCleaner is already running | PID: $pid"
        return 0
    fi

    jar=$(jar_path) || return 1
    load_environment || return 1
    require_java || return 1
    java -jar "$jar" --config "$CONFIG_FILE" --check-config || return 1
    : >> "$LOG_FILE" || return 1

    nohup java -jar "$jar" --config "$CONFIG_FILE" >> "$LOG_FILE" 2>&1 </dev/null 9>&- &
    pid=$!
    if ! started=$(process_start_time "$pid"); then
        echo "AWSCleaner exited during startup. Check Show Logs." >&2
        return 1
    fi
    if ! printf '%s %s\n' "$pid" "$started" > "$PID_FILE"; then
        kill -TERM "$pid" 2>/dev/null
        return 1
    fi

    sleep 1
    if pid=$(get_pid); then
        echo "AWSCleaner is running | PID: $pid"
    else
        rm -f -- "$PID_FILE"
        echo "AWSCleaner exited during startup. Check Show Logs." >&2
        return 1
    fi
}

stop_app() {
    local pid elapsed
    if ! pid=$(get_pid); then
        rm -f -- "$PID_FILE"
        echo "AWSCleaner is stopped."
        return 0
    fi

    echo "Stopping AWSCleaner | PID: $pid"
    if ! kill -TERM "$pid" 2>/dev/null && get_pid >/dev/null; then
        echo "Cannot stop AWSCleaner | PID: $pid" >&2
        return 1
    fi
    for ((elapsed = 0; elapsed < 30; elapsed++)); do
        if ! get_pid >/dev/null; then
            rm -f -- "$PID_FILE" || return 1
            echo "AWSCleaner stopped."
            return 0
        fi
        sleep 1
    done
    echo "AWSCleaner is still stopping | PID: $pid" >&2
    return 1
}

restart_app() {
    stop_app && start_app
}

status_app() {
    local pid
    if pid=$(get_pid); then
        echo "Status: RUNNING | PID: $pid"
    else
        echo "Status: STOPPED"
    fi
}

show_logs() {
    if [[ -f "$LOG_FILE" ]]; then
        tail -n 100 -- "$LOG_FILE"
    else
        echo "No console log yet."
    fi
}

edit_config() {
    if ! command -v nano >/dev/null; then
        echo "Install nano to edit config.yml from the menu." >&2
        return 1
    fi
    [[ -f "$CONFIG_FILE" ]] || initialize_config || return 1
    nano -- "$CONFIG_FILE"
}

first_menu=true
while true; do
    if [[ -t 1 && "${TERM:-dumb}" != dumb ]]; then
        clear
    fi
    if [[ "$first_menu" == true && ! -f "$CONFIG_FILE" ]]; then
        initialize_config
    fi
    first_menu=false
    printf '%s\n' '======== AWSCleaner =========' \
        '1) Start' '2) Stop' '3) Restart' '4) Status' '5) Show Logs' '6) Edit Config' '0) Exit'

    read -r -p 'Select an option: ' choice || break
    case "$choice" in
        1) with_lock start_app ;;
        2) with_lock stop_app ;;
        3) with_lock restart_app ;;
        4) with_lock status_app ;;
        5) show_logs ;;
        6) edit_config ;;
        0) break ;;
        *) echo "Invalid option." ;;
    esac

    echo
    read -r -p 'Press Enter to continue...' || break
done

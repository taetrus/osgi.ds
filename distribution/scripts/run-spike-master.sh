#!/bin/bash
# Isolation spike — MASTER app (App-1). Owns the catalog + selection; exports
# ICatalogService on ecftcp://localhost:3289/catalog. Start this FIRST, then
# run-spike-detail.sh in another terminal.
#
# Multi-frame grid: set SPIKE_FRAMES=N (default 2) to open N tiled master windows.
# The detail reads N from the master's published layout, so only set it here, e.g.:
#   SPIKE_FRAMES=3 ./run-spike-master.sh
#   ./run-spike-detail.sh
# (-Dspike.frames=N also works, but only if placed BEFORE -jar — the env var is the
#  supported path since these scripts append args after -jar.)

PRODUCT_UID="com.kk.pde.ds.spike.master.product"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -d "$SCRIPT_DIR/plugins" ]; then
    PRODUCT_DIR="$SCRIPT_DIR"
else
    BASE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
    case "$(uname -s)" in
        Darwin)
            PRODUCT_DIR="$BASE_DIR/target/products/$PRODUCT_UID/macosx/cocoa/x86_64/Eclipse.app/Contents/Eclipse"
            ;;
        Linux)
            PRODUCT_DIR="$BASE_DIR/target/products/$PRODUCT_UID/linux/gtk/x86_64"
            ;;
        *)
            echo "ERROR: Unsupported OS. On Windows, use run-spike-master.bat instead."
            exit 1
            ;;
    esac
fi

OSGI_JAR=$(ls "$PRODUCT_DIR/plugins/org.eclipse.osgi_"*.jar 2>/dev/null | head -1)

if [ -z "$OSGI_JAR" ]; then
    echo "ERROR: Could not find org.eclipse.osgi jar in: $PRODUCT_DIR/plugins/"
    echo "Hint: run 'mvn clean verify' from the project root first."
    exit 1
fi

echo "Starting spike MASTER (App-1): $OSGI_JAR"
cd "$PRODUCT_DIR"
java "$@" -jar "$OSGI_JAR" -configuration configuration -console -consoleLog
